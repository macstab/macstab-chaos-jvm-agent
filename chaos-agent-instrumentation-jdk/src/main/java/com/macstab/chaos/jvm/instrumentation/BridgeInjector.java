package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.core.AgentLog;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Writes the bootstrap bridge classes into a jar and appends it to the bootstrap class path, once
 * per JVM, so that advice woven into JDK classes can reach the agent ({@code docs/adr/0001}).
 */
final class BridgeInjector {
  private static final AgentLog LOGGER =
      AgentLog.named(JdkInstrumentationInstaller.class.getName());

  private static final String BRIDGE_JAR_PREFIX = "macstab-chaos-bootstrap-bridge";
  private static final String BRIDGE_JAR_SUFFIX = ".jar";

  /**
   * Where the agent jar keeps the bridge classes: under a path that is not their class name, so no
   * class loader that reads the agent jar defines a copy of its own ({@code
   * chaos-agent-bootstrap/build.gradle.kts}).
   */
  static final String BRIDGE_LAYOUT = "META-INF/macstab-chaos-bridge/";

  private static final String BOOTSTRAP_DISPATCHER =
      "com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher";

  private static final String CLAIM_REVISION = "claimRevision";

  /** Computed once; see {@link #revision()}. */
  private static volatile @Nullable String revision;

  private BridgeInjector() {}

  /**
   * Packages the bootstrap bridge classes into a temporary JAR and appends it to the bootstrap
   * classpath.
   *
   * <p>The classes written are {@code BootstrapDispatcher} and every other agent type that inlined
   * advice references (the scheduled-task wrappers and the URL, JDBC and client-call extractors):
   * see {@link #BRIDGE_CLASSES}.
   *
   * <p>They are read from the agent classloader's resources (the same JAR that contains this class;
   * the agent jar keeps them under {@value #BRIDGE_LAYOUT}, the plain {@code chaos-agent-
   * instrumentation-jdk} classes under their class names) and written verbatim into the temp JAR,
   * which is unlinked as soon as it is appended (on Windows, where an open file cannot be deleted,
   * at exit or by the next start's sweep). After the JAR is written, {@link
   * Instrumentation#appendToBootstrapClassLoaderSearch} makes its contents visible to the bootstrap
   * classloader, allowing instrumented JDK classes — which are loaded by the bootstrap classloader
   * — to call {@code BootstrapDispatcher} static methods.
   *
   * <p>Nothing is appended when the bootstrap class loader already resolves {@code
   * BootstrapDispatcher}: another copy of the agent, loaded by another class loader, injected the
   * bridge, or it was placed there with {@code -Xbootclasspath/a}. A second jar would only be
   * shadowed by the first.
   *
   * <p>The jar is written to {@code bridgeDir} when it is given, and only there: the operator chose
   * it, and a jar the bootstrap class loader reads must not move to a directory nobody chose.
   * Otherwise it goes to {@code java.io.tmpdir}, and when that is not writable (a read-only
   * container file system) to {@code /dev/shm} where it exists.
   *
   * @param instrumentation the {@link Instrumentation} handle used to append to the bootstrap
   *     classpath
   * @param bridgeDir the directory configured for the jar, already checked by the caller; {@code
   *     null} for the default directories
   * @throws IllegalStateException wrapping the underlying {@link java.io.IOException} (which names
   *     every directory tried) if the temporary JAR cannot be created, if a required class resource
   *     is missing from the agent JAR, or if appending to the bootstrap classpath fails
   */
  static void injectBridge(final Instrumentation instrumentation, final @Nullable Path bridgeDir) {
    // Idempotency: appendToBootstrapClassLoaderSearch has no unwind operation — once a JAR
    // is appended, its entries stay visible to the bootstrap classloader for the JVM's
    // lifetime, and the bootstrap classloader caches the classes it has loaded. A second
    // install() call (dynamic-attach test runners that cycle install→uninstall→install)
    // that rebuilds and re-appends a fresh temp JAR would:
    //   (1) leak file descriptors in BRIDGE_JARS on every cycle,
    //   (2) shadow the already-loaded BootstrapDispatcher with a new Class object on a
    //       newer classpath entry for any class still resolvable lazily (inner classes),
    // neither of which is desirable. The bridge classes are fixed bytecode — once injected,
    // they never change — so skipping subsequent calls is both safe and correct.
    if (!BRIDGE_INJECTED.compareAndSet(false, true)) {
      return;
    }
    if (onBootClassPath()) {
      try {
        // Another copy appended the bridge after this install's own check.
        requireOwnRevision();
      } catch (final RuntimeException foreign) {
        BRIDGE_INJECTED.set(false);
        throw foreign;
      }
      return;
    }
    boolean appended = false;
    try {
      final Path bridgeJar = createBridgeJar(bridgeDir);
      bridgeJar.toFile().deleteOnExit();
      try (JarOutputStream jarOutputStream =
          new JarOutputStream(Files.newOutputStream(bridgeJar))) {
        for (final String bridgeClass : BRIDGE_CLASSES) {
          writeClass(jarOutputStream, bridgeClass);
        }
      }
      // Retain the JarFile in a static field. appendToBootstrapClassLoaderSearch does not
      // document whether the JVM keeps a strong reference to the Java-side JarFile; on
      // HotSpot the underlying native classpath entry is kept open, but the Java wrapper
      // becomes unreachable as soon as this method returns. If the JarFile is finalized and
      // its ZipFile closed, later bootstrap loads of ScheduledRunnableWrapper /
      // ScheduledCallableWrapper / BootstrapDispatcher classes can fail with
      // ZipException("ZipFile closed") at the first intercepted schedule() call — hours or
      // days into an otherwise-healthy run. Holding the reference is cheap (one file handle
      // per JVM) and eliminates the finalizer race. Note that BRIDGE_JARS is NEVER cleared
      // on uninstall — the bootstrap classloader has already loaded classes from the JAR and
      // releasing the JarFile would let its ZipFile finalize, breaking every future class
      // load that resolves lazily through the bootstrap classpath entry.
      final java.util.jar.JarFile jarFile = new java.util.jar.JarFile(bridgeJar.toFile());
      BRIDGE_JARS.add(jarFile);
      instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
      appended = true;
      claim();
      // The bootstrap class path entry is opened when it is appended, and the JarFile above holds
      // its own handle, so on POSIX the file can be unlinked now and nothing is left in the temp
      // directory, even when the JVM is killed (deleteOnExit alone would leave one jar per kill
      // -9). Windows refuses to delete an open file: there the jar stays until
      // a later start sweeps it (see sweepStaleBridgeJars).
      try {
        Files.deleteIfExists(bridgeJar);
      } catch (final IOException inUse) {
        LOGGER.fine(() -> "bridge jar kept until exit: " + bridgeJar);
      }
    } catch (final IOException exception) {
      // Failure path: clear the injected flag so a subsequent install() can retry rather
      // than silently returning success when the bridge was never actually in place.
      BRIDGE_INJECTED.set(false);
      throw new IllegalStateException("failed to inject bootstrap bridge", exception);
    } catch (final RuntimeException | Error failure) {
      // The same for an unchecked failure, unless the jar is already on the bootstrap class path:
      // that cannot be undone, and appending a second copy is what the flag prevents.
      if (!appended) {
        BRIDGE_INJECTED.set(false);
      }
      throw failure;
    }
  }

  /**
   * Refuses the install when the bootstrap class loader already resolves a bridge that is not this
   * copy's revision: claimed by another agent version, or not claimed at all (a bridge placed with
   * {@code -Xbootclasspath/a}, or appended by an agent version that predates the claim, whose
   * dispatcher has no {@code claimRevision}). The advice and the delegate of this copy would run
   * against another copy's bridge classes. Reads only: nothing is claimed or appended.
   *
   * @throws IllegalStateException if the bridge belongs to another revision, or its revision cannot
   *     be read
   */
  static void requireOwnRevision() {
    final Class<?> dispatcher;
    try {
      dispatcher = Class.forName(BOOTSTRAP_DISPATCHER, true, null);
    } catch (final ClassNotFoundException notYet) {
      return;
    }
    @Nullable String claimed;
    try {
      claimed =
          (String) dispatcher.getMethod(CLAIM_REVISION, String.class).invoke(null, (Object) null);
    } catch (final NoSuchMethodException predatesTheClaim) {
      claimed = null;
    } catch (final ReflectiveOperationException exception) {
      throw new IllegalStateException("failed to read the bridge revision", exception);
    }
    final String own = revision();
    if (!own.equals(claimed)) {
      throw mismatch(claimed, own);
    }
  }

  /** Claims the bridge this copy appended; a claim that loses a race to another revision fails. */
  private static void claim() {
    final String own = revision();
    final @Nullable String claimed;
    try {
      claimed =
          (String)
              Class.forName(BOOTSTRAP_DISPATCHER, true, null)
                  .getMethod(CLAIM_REVISION, String.class)
                  .invoke(null, own);
    } catch (final ReflectiveOperationException exception) {
      throw new IllegalStateException("failed to claim the bridge revision", exception);
    }
    if (claimed != null && !claimed.equals(own)) {
      throw mismatch(claimed, own);
    }
  }

  private static IllegalStateException mismatch(final @Nullable String claimed, final String own) {
    return new IllegalStateException(
        "a chaos agent of revision "
            + (claimed == null ? "unknown" : claimed)
            + " is already installed in this JVM, and this copy is revision "
            + own
            + "; restart with one agent version");
  }

  /**
   * This copy's bridge revision: the first 16 hex digits of the SHA-256 of the {@link
   * #BRIDGE_CLASSES} bytes, in list order, as {@link #injectBridge} writes them.
   *
   * @throws IllegalStateException if a bridge class cannot be read
   */
  static String revision() {
    String computed = revision;
    if (computed == null) {
      try {
        final java.security.MessageDigest digest =
            java.security.MessageDigest.getInstance("SHA-256");
        for (final String bridgeClass : BRIDGE_CLASSES) {
          try (InputStream classFile = openClass(bridgeClass)) {
            digest.update(classFile.readAllBytes());
          }
        }
        computed = java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 16);
      } catch (final IOException | java.security.NoSuchAlgorithmException exception) {
        throw new IllegalStateException("failed to read the bridge classes", exception);
      }
      revision = computed;
    }
    return computed;
  }

  /** Returns whether the bootstrap class loader already resolves {@code BootstrapDispatcher}. */
  private static boolean onBootClassPath() {
    try {
      Class.forName(BOOTSTRAP_DISPATCHER, false, null);
      return true;
    } catch (final ClassNotFoundException notYet) {
      return false;
    }
  }

  /**
   * Tracks whether {@link #injectBridge} has successfully installed the bootstrap bridge JAR.
   * JVM-wide single-shot because {@code Instrumentation.appendToBootstrapClassLoaderSearch} has no
   * reverse operation.
   */
  private static final java.util.concurrent.atomic.AtomicBoolean BRIDGE_INJECTED =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /**
   * Strong-reference holder for the injected bridge {@link java.util.jar.JarFile}. See the call
   * site above for the rationale.
   */
  private static final java.util.List<java.util.jar.JarFile> BRIDGE_JARS =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  /** Bridge jars older than this are left over from earlier JVMs and are removed. */
  private static final java.time.Duration STALE_BRIDGE_JAR_AGE = java.time.Duration.ofDays(1);

  /** Where the jar goes when {@code java.io.tmpdir} is not writable (Linux). */
  private static final Path SHARED_MEMORY = Path.of("/dev/shm");

  /**
   * Creates the bridge jar's file in the first directory that takes it: {@code bridgeDir} alone
   * when it is set, else {@code java.io.tmpdir}, then {@code /dev/shm} where it exists. Each
   * directory is swept of stale bridge jars first.
   *
   * <p>Package-private as a test seam.
   *
   * @throws IOException naming every directory tried and why it failed, each failure suppressed
   */
  static Path createBridgeJar(final @Nullable Path bridgeDir) throws IOException {
    final java.util.Set<Path> candidates = new java.util.LinkedHashSet<>();
    if (bridgeDir != null) {
      candidates.add(bridgeDir);
    } else {
      candidates.add(Path.of(System.getProperty("java.io.tmpdir")));
      if (Files.isDirectory(SHARED_MEMORY)) {
        candidates.add(SHARED_MEMORY);
      }
    }
    final java.util.StringJoiner tried = new java.util.StringJoiner("; ");
    final java.util.List<IOException> failures = new java.util.ArrayList<>();
    for (final Path directory : candidates) {
      sweepStaleBridgeJars(directory, System.getProperty("user.name", ""));
      try {
        return createSecureTempFile(directory);
      } catch (final IOException failed) {
        tried.add(directory + ": " + failed);
        failures.add(failed);
      }
    }
    final IOException none = new IOException("no directory takes the bridge jar: " + tried);
    failures.forEach(none::addSuppressed);
    throw none;
  }

  /**
   * Removes bridge jars of earlier JVMs that could not delete their own (Windows, where an open
   * file cannot be deleted, or JVMs killed before exit). Only regular files with the bridge prefix
   * that are older than a day are considered and, where the file system has a {@code posix} view,
   * only those owned by the JVM's user: a directory without the sticky bit would otherwise let the
   * sweep delete another user's files. A jar still in use by a running JVM is locked on Windows and
   * the delete simply fails. Failures are ignored: the sweep is housekeeping only.
   *
   * <p>Package-private as a test seam: the JVM's user name is passed in.
   *
   * @param directory the directory the bridge jar is written to
   * @param jvmUser the JVM's user name ({@code user.name}); a blank or unknown ({@code ?}) name
   *     deletes nothing on a {@code posix} file system, since no owner can be confirmed
   */
  static void sweepStaleBridgeJars(final Path directory, final String jvmUser) {
    final java.time.Instant cutoff = java.time.Instant.now().minus(STALE_BRIDGE_JAR_AGE);
    final boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
    try (java.nio.file.DirectoryStream<Path> candidates =
        Files.newDirectoryStream(directory, BRIDGE_JAR_PREFIX + "*" + BRIDGE_JAR_SUFFIX)) {
      for (final Path candidate : candidates) {
        try {
          if (Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
              && Files.getLastModifiedTime(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                  .toInstant()
                  .isBefore(cutoff)
              && (!posix
                  || Files.getOwner(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                      .getName()
                      .equals(jvmUser))) {
            Files.deleteIfExists(candidate);
          }
        } catch (final IOException | SecurityException | UnsupportedOperationException skipped) {
          // In use, owned by another user, or already gone.
        }
      }
    } catch (final IOException | SecurityException unavailable) {
      LOGGER.fine(() -> "bridge jar sweep skipped: " + unavailable);
    }
  }

  /**
   * Creates the bridge jar's file in {@code directory}, owner-only (0600) on POSIX file systems:
   * the bootstrap classloader loads its code, so a file another local user could replace before the
   * JVM opens it would let them run code at bootstrap level. Other file systems (Windows) get the
   * default permissions.
   */
  private static Path createSecureTempFile(final Path directory) throws IOException {
    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      final java.nio.file.attribute.FileAttribute<?> ownerOnly =
          java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
              java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      return Files.createTempFile(directory, BRIDGE_JAR_PREFIX, BRIDGE_JAR_SUFFIX, ownerOnly);
    }
    return Files.createTempFile(directory, BRIDGE_JAR_PREFIX, BRIDGE_JAR_SUFFIX);
  }

  /**
   * The class files of the bootstrap bridge jar. JDK classes woven by the agent resolve the types
   * their advice references through the bootstrap class loader, and so do library classes whose
   * loader cannot see the agent's, so every agent type that inlined advice or the bridge itself
   * references must be in this list; {@code ArchitectureTest} checks both.
   */
  static final java.util.List<String> BRIDGE_CLASSES =
      java.util.List.of(
          "com/macstab/chaos/jvm/instrumentation/bridge/BootstrapDispatcher.class",
          "com/macstab/chaos/jvm/instrumentation/bridge/SessionHeaderPolicy.class",
          "com/macstab/chaos/jvm/instrumentation/bridge/ChannelBuffers.class",
          "com/macstab/chaos/jvm/instrumentation/bridge/OperationTags.class",
          "com/macstab/chaos/jvm/instrumentation/ScheduledRunnableWrapper.class",
          "com/macstab/chaos/jvm/instrumentation/ScheduledCallableWrapper.class",
          // Advice woven into JDK module classes (e.g. jdk.internal.net.http.HttpClientImpl in
          // the platform class loader) references these types; without them the verifier raises
          // NoClassDefFoundError when the JDK class is first used.
          "com/macstab/chaos/jvm/instrumentation/HttpUrlExtractor.class",
          "com/macstab/chaos/jvm/instrumentation/HttpUrlExtractor$1.class",
          // JDBC advice woven into HikariPool, c3p0 and java.sql types references these: the
          // inlined references resolve through the bootstrap class path.
          "com/macstab/chaos/jvm/instrumentation/JdbcTargetExtractor.class",
          "com/macstab/chaos/jvm/instrumentation/JdbcTargetExtractor$1.class",
          // Client-library advice (Kafka, gRPC, Redis, AWS SDK, Netty) references this. A library
          // defined by a loader that reaches only the platform and boot loaders (a plugin or module
          // loader) cannot see the agent's loader, so it must come from the bootstrap class path.
          "com/macstab/chaos/jvm/instrumentation/ClientCallExtractor.class",
          "com/macstab/chaos/jvm/instrumentation/ClientCallExtractor$1.class",
          "com/macstab/chaos/jvm/instrumentation/ClientCallExtractor$2.class");

  /**
   * Writes the class file {@code resourcePath} into the bridge jar under its class name, read from
   * the agent jar's {@value #BRIDGE_LAYOUT}, or from the class name itself for the plain {@code
   * chaos-agent-instrumentation-jdk} classes (in-repo tests, benchmarks).
   */
  private static void writeClass(final JarOutputStream jarOutputStream, final String resourcePath)
      throws IOException {
    final JarEntry jarEntry = new JarEntry(resourcePath);
    jarOutputStream.putNextEntry(jarEntry);
    try (InputStream classFile = openClass(resourcePath)) {
      classFile.transferTo(jarOutputStream);
    }
    jarOutputStream.closeEntry();
  }

  /**
   * Opens the bridge class file {@code resourcePath} from the agent jar's {@value #BRIDGE_LAYOUT},
   * or from the class name itself for the plain {@code chaos-agent-instrumentation-jdk} classes.
   */
  private static InputStream openClass(final String resourcePath) throws IOException {
    final ClassLoader agentLoader = JdkInstrumentationInstaller.class.getClassLoader();
    InputStream inputStream = agentLoader.getResourceAsStream(BRIDGE_LAYOUT + resourcePath);
    if (inputStream == null) {
      inputStream = agentLoader.getResourceAsStream(resourcePath);
    }
    if (inputStream == null) {
      throw new IOException("missing bridge resource " + resourcePath);
    }
    return inputStream;
  }
}

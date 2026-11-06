package com.macstab.chaos.jvm.instrumentation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

/**
 * Writes the bootstrap bridge classes into a jar and appends it to the bootstrap class path, once
 * per JVM, so that advice woven into JDK classes can reach the agent ({@code docs/adr/0001}).
 */
final class BridgeInjector {
  private static final Logger LOGGER =
      Logger.getLogger(JdkInstrumentationInstaller.class.getName());

  private static final String BRIDGE_JAR_PREFIX = "macstab-chaos-bootstrap-bridge";
  private static final String BRIDGE_JAR_SUFFIX = ".jar";

  private BridgeInjector() {}

  /**
   * Packages the bootstrap bridge classes into a temporary JAR and appends it to the bootstrap
   * classpath.
   *
   * <p>The classes written are {@code BootstrapDispatcher} and the types that advice woven into JDK
   * classes references (the scheduled-task wrappers and the API exceptions it throws).
   *
   * <p>They are read from the agent classloader's resources (the same JAR that contains this class)
   * and written verbatim into the temp JAR, which is unlinked as soon as it is appended (on
   * Windows, where an open file cannot be deleted, at exit or by the next start's sweep). After the
   * JAR is written, {@link Instrumentation#appendToBootstrapClassLoaderSearch} makes its contents
   * visible to the bootstrap classloader, allowing instrumented JDK classes — which are loaded by
   * the bootstrap classloader — to call {@code BootstrapDispatcher} static methods.
   *
   * @param instrumentation the {@link Instrumentation} handle used to append to the bootstrap
   *     classpath
   * @throws IllegalStateException wrapping the underlying {@link java.io.IOException} if the
   *     temporary JAR cannot be created, if a required class resource is missing from the agent
   *     JAR, or if appending to the bootstrap classpath fails
   */
  static void injectBridge(final Instrumentation instrumentation) {
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
    boolean appended = false;
    try {
      sweepStaleBridgeJars();
      final Path bridgeJar = createSecureTempFile();
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

  /**
   * Removes bridge jars of earlier JVMs that could not delete their own (Windows, where an open
   * file cannot be deleted, or JVMs killed before exit). Only files with the bridge prefix that are
   * older than a day are considered; a jar still in use by a running JVM is locked on Windows and
   * the delete simply fails. Failures are ignored: the sweep is housekeeping only.
   */
  private static void sweepStaleBridgeJars() {
    final Path tmpPath = Path.of(System.getProperty("java.io.tmpdir"));
    final java.time.Instant cutoff = java.time.Instant.now().minus(STALE_BRIDGE_JAR_AGE);
    try (java.nio.file.DirectoryStream<Path> candidates =
        Files.newDirectoryStream(tmpPath, BRIDGE_JAR_PREFIX + "*" + BRIDGE_JAR_SUFFIX)) {
      for (final Path candidate : candidates) {
        try {
          if (Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
              && Files.getLastModifiedTime(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                  .toInstant()
                  .isBefore(cutoff)) {
            Files.deleteIfExists(candidate);
          }
        } catch (final IOException | SecurityException skipped) {
          // In use, owned by another user, or already gone.
        }
      }
    } catch (final IOException | SecurityException unavailable) {
      LOGGER.fine(() -> "bridge jar sweep skipped: " + unavailable);
    }
  }

  /**
   * Creates the bridge jar's file, owner-only (0600) on POSIX file systems: the bootstrap
   * classloader loads its code, so a file another local user could replace before the JVM opens it
   * would let them run code at bootstrap level. Other file systems (Windows) get the default
   * permissions.
   */
  private static Path createSecureTempFile() throws IOException {
    final String tmpDir = System.getProperty("java.io.tmpdir");
    final Path tmpPath = Path.of(tmpDir);
    if (Files.getFileStore(tmpPath).supportsFileAttributeView("posix")) {
      final java.nio.file.attribute.FileAttribute<?> ownerOnly =
          java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
              java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      return Files.createTempFile(BRIDGE_JAR_PREFIX, BRIDGE_JAR_SUFFIX, ownerOnly);
    }
    return Files.createTempFile(BRIDGE_JAR_PREFIX, BRIDGE_JAR_SUFFIX);
  }

  /**
   * The class files of the bootstrap bridge jar. JDK classes woven by the agent resolve the types
   * their advice references through the bootstrap class loader, so every agent type the bridge
   * references must be in this list; {@code ArchitectureTest} checks that it is closed.
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
          "com/macstab/chaos/jvm/api/ChaosHttpSuppressException.class",
          "com/macstab/chaos/jvm/instrumentation/HttpUrlExtractor.class",
          "com/macstab/chaos/jvm/instrumentation/HttpUrlExtractor$1.class",
          // JDBC advice woven into HikariPool, c3p0 and java.sql types references these: the
          // inlined references resolve through the bootstrap class path.
          "com/macstab/chaos/jvm/api/ChaosJdbcSuppressException.class",
          "com/macstab/chaos/jvm/instrumentation/JdbcTargetExtractor.class",
          "com/macstab/chaos/jvm/instrumentation/JdbcTargetExtractor$1.class");

  private static void writeClass(final JarOutputStream jarOutputStream, final String resourcePath)
      throws IOException {
    final JarEntry jarEntry = new JarEntry(resourcePath);
    jarOutputStream.putNextEntry(jarEntry);
    try (InputStream inputStream =
        JdkInstrumentationInstaller.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new IOException("missing bridge resource " + resourcePath);
      }
      inputStream.transferTo(jarOutputStream);
    }
    jarOutputStream.closeEntry();
  }
}

package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.core.AgentLog;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.startup.AgentArgs;
import com.macstab.chaos.jvm.startup.AgentSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.logging.Level;
import org.jspecify.annotations.Nullable;

/**
 * The status and hits files, in the native library's format, so tools that read the library's
 * reports read the agent's the same way.
 *
 * <ul>
 *   <li><b>Status file</b> {@code <dir>/.chaos-jvm.status}, rewritten after every load of the
 *       startup plan and every reload: {@code state=ok} with {@code scenarios=<n>}, or {@code
 *       state=error} with {@code line} and {@code column} (1-based, {@code 0} without a position)
 *       and {@code reason}; then {@code source} and {@code pid}. Without a startup plan it is not
 *       written.
 *   <li><b>Hits file</b> {@code <dir>/.chaos-jvm.hits.<pid>}, one line per scenario id that
 *       applied, sorted by id: {@code <applied count>\t<scenario id>}. The count is cumulative
 *       since the reports started: a stopped or reloaded scenario keeps its count, and the same id
 *       in several sessions is one line with their sum. Written after each of the first 64
 *       applications, then at most every 100 ms while applications happen, and at exit; not at all
 *       in a JVM where no scenario ever applied.
 * </ul>
 *
 * <p>Both are written to a staging file and renamed over the report, so a reader never sees a
 * partial file. The writes run with the dispatch depth raised: a file I/O scenario never applies to
 * the agent's own reports. {@code <dir>} is {@code java.io.tmpdir} ({@code /tmp} on Linux, like the
 * library) unless {@code reportsDir} is set. Both files are off unless {@code reports=on}: an agent
 * leaves nothing in a shared directory by default.
 *
 * <p>An existing report is replaced or removed only when it is a regular file owned by the JVM's
 * user (the owner is compared on file systems with a {@code posix} view): in a directory other
 * users can write without the sticky bit, the agent never deletes or overwrites their files.
 *
 * <p>Unlike the library, which writes a {@code 0} line for every loaded rule, the hits file lists
 * only ids that applied, and keeps them after the scenario ends; the status file's {@code
 * scenarios} count shows what was loaded. The status file has one name per directory, so JVMs
 * sharing a {@code reportsDir} overwrite each other's status, and containers that share it all run
 * as pid 1 and so share a hits file name: give each JVM its own {@code reportsDir}. Every text
 * value (scenario id, source, reason) is cut to its first 200 characters, as the library cuts rule
 * texts.
 */
final class ChaosReports implements AutoCloseable {

  static final String STATUS_FILE = ".chaos-jvm.status";
  static final String HITS_FILE_PREFIX = ".chaos-jvm.hits.";

  static final String REPORTS_ARG = "reports";
  static final String REPORTS_PROPERTY = "macstab.chaos.reports";
  static final String REPORTS_ENV = "MACSTAB_CHAOS_REPORTS";
  static final String REPORTS_DIR_ARG = "reportsDir";
  static final String REPORTS_DIR_PROPERTY = "macstab.chaos.reportsDir";
  static final String REPORTS_DIR_ENV = "MACSTAB_CHAOS_REPORTS_DIR";

  /** Applications written at once; later ones are batched. */
  private static final int IMMEDIATE_HITS = 64;

  private static final long BATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

  private static final AgentLog LOGGER = AgentLog.named(ChaosReports.class.getName());

  private static volatile @Nullable ChaosReports current;

  private final Path directory;
  private final long pid;
  private final ChaosRuntime runtime;
  private final AtomicBoolean hitsDirty = new AtomicBoolean();
  private final AtomicLong applications = new AtomicLong();

  /** Applications per scenario id since these reports started; never reset. */
  private final Map<String, LongAdder> hits = new ConcurrentHashMap<>();

  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread hitsWriter;
  private final Thread shutdownHook;
  private final com.macstab.chaos.jvm.api.ChaosEventListener hitCounter = this::countHit;

  /** Whether a failed write was logged; later failures stay quiet. */
  private final AtomicBoolean failureLogged = new AtomicBoolean();

  private ChaosReports(final Path directory, final ChaosRuntime runtime) {
    this.directory = directory;
    this.pid = ProcessHandle.current().pid();
    this.runtime = runtime;
    this.hitsWriter = new Thread(this::writeHitsWhileOpen, "macstab-chaos-hits-writer");
    this.hitsWriter.setDaemon(true);
    this.shutdownHook = new Thread(this::writeFinalHits, "macstab-chaos-hits-final");
  }

  /**
   * Creates and starts the reports when they are switched on ({@code reports=on}).
   *
   * @param settings the agent's settings
   * @param runtime the runtime whose scenarios are reported
   * @return the reports, or empty when not switched on
   * @throws IllegalStateException if the JVM is shutting down; nothing is started then
   */
  static Optional<ChaosReports> createIfEnabled(
      final AgentSettings settings, final ChaosRuntime runtime) {
    final String enabled = settings.valueOrEmpty(REPORTS_ARG, REPORTS_PROPERTY, REPORTS_ENV);
    if (!AgentArgs.parseBoolean(REPORTS_ARG, enabled, false)) {
      return Optional.empty();
    }
    final String dir =
        settings.valueOrEmpty(REPORTS_DIR_ARG, REPORTS_DIR_PROPERTY, REPORTS_DIR_ENV);
    final ChaosReports reports =
        new ChaosReports(
            Path.of(dir.isEmpty() ? System.getProperty("java.io.tmpdir") : dir), runtime);
    reports.start();
    current = reports;
    return Optional.of(reports);
  }

  /**
   * Returns the reports of the installed agent.
   *
   * @return the reports, or empty when switched off or not installed
   */
  static Optional<ChaosReports> current() {
    return Optional.ofNullable(current);
  }

  /**
   * Registers the shutdown hook first: it is the step that fails while the JVM shuts down ({@link
   * IllegalStateException}), and then no listener and no writer thread may be left behind.
   */
  private void start() {
    BootstrapDispatcher.beginInternalCall();
    try {
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      runtime.addLifecycleListener(hitCounter);
      hitsWriter.start();
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  private void countHit(final ChaosEvent event) {
    if (event.type() == ChaosEvent.Type.APPLIED) {
      hits.computeIfAbsent(event.scenarioId(), id -> new LongAdder()).increment();
      hitsDirty.set(true);
      if (applications.incrementAndGet() <= IMMEDIATE_HITS) {
        LockSupport.unpark(hitsWriter);
      }
    }
  }

  /**
   * Reports a loaded plan.
   *
   * @param scenarios the number of scenarios in the plan
   * @param source where the plan came from
   */
  void statusOk(final int scenarios, final String source) {
    writeAtomically(
        STATUS_FILE,
        "state=ok\nscenarios=" + scenarios + "\nsource=" + oneLine(source) + "\npid=" + pid + "\n");
  }

  /**
   * Reports a plan that could not be loaded or activated.
   *
   * @param source where the plan came from
   * @param line 1-based line of the failure; {@code 0} without a position
   * @param column 1-based column of the failure; {@code 0} without a position
   * @param reason what is wrong; {@code null} when the failure carried no message
   */
  void statusError(
      final String source, final int line, final int column, final @Nullable String reason) {
    writeAtomically(
        STATUS_FILE,
        "state=error\nscenarios=0\nline="
            + line
            + "\ncolumn="
            + column
            + "\nreason="
            + oneLine(reason)
            + "\nsource="
            + oneLine(source)
            + "\npid="
            + pid
            + "\n");
  }

  /**
   * What a failure reports as its reason: its message, else its class's simple name, never {@code
   * "null"}.
   *
   * @param failure the failure
   * @return the reason
   */
  static String reasonOf(final Throwable failure) {
    final String message = failure.getMessage();
    return message != null ? message : failure.getClass().getSimpleName();
  }

  /**
   * Writes the hits file a last time, if anything was applied: a JVM in which no scenario ever
   * applied leaves no hits file behind.
   */
  private void writeFinalHits() {
    if (applications.get() > 0) {
      writeHits();
    }
  }

  /** Removes the status file: the configured plan file no longer exists. */
  void removeStatus() {
    removeStatus(
        directory.getFileSystem().supportedFileAttributeViews(),
        ChaosReports::ownerName,
        System.getProperty("user.name", ""));
  }

  /**
   * Test seam of {@link #removeStatus()}: the file system's attribute views, the owner lookup and
   * the JVM's user name are passed in, so a test can model a file another user owns.
   *
   * @param supportedViews the attribute views of the report directory's file system
   * @param ownerOf the owner name of a path, not following a link; throws {@link
   *     UncheckedIOException} or {@link UnsupportedOperationException} where it is unavailable
   * @param jvmUser the JVM's user name ({@code user.name})
   */
  void removeStatus(
      final Set<String> supportedViews,
      final Function<Path, String> ownerOf,
      final String jvmUser) {
    BootstrapDispatcher.beginInternalCall();
    try {
      final Path status = directory.resolve(STATUS_FILE);
      if (isOurs(status, supportedViews, ownerOf, jvmUser)) {
        Files.deleteIfExists(status);
      }
    } catch (final IOException ignored) {
      // a stale report is better than a failing poller
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  /**
   * Whether an existing report may be replaced or removed: it is a regular file and, on a file
   * system with a {@code posix} view, owned by the JVM's user. In a directory other users can write
   * without the sticky bit, a rename or delete would otherwise destroy another user's file. A
   * missing file is ours to create. The owner is not compared where it or the user name is unknown
   * (a container running an unnamed uid), as for the plan file. A refused file is logged at FINE
   * and left as it is.
   */
  private static boolean isOurs(
      final Path report,
      final Set<String> supportedViews,
      final Function<Path, String> ownerOf,
      final String jvmUser) {
    if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) {
      return true;
    }
    if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
      LOGGER.fine(() -> "[chaos-agent] report left alone, not a regular file: " + report);
      return false;
    }
    if (!supportedViews.contains("posix") || jvmUser.isBlank() || jvmUser.equals("?")) {
      return true;
    }
    final String owner;
    try {
      owner = ownerOf.apply(report);
    } catch (final UncheckedIOException | UnsupportedOperationException unavailable) {
      return true;
    }
    if (!owner.equals(jvmUser)) {
      LOGGER.fine(
          () ->
              "[chaos-agent] report left alone, owned by "
                  + owner
                  + ", not by the JVM's user "
                  + jvmUser
                  + ": "
                  + report);
      return false;
    }
    return true;
  }

  private static String ownerName(final Path path) {
    try {
      return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
    } catch (final IOException unavailable) {
      throw new UncheckedIOException(unavailable);
    }
  }

  /**
   * Writes the hits file now. Rendering and writing are one step: the counts only grow, so the
   * writer that writes last also rendered last and leaves the largest counts.
   */
  synchronized void writeHits() {
    final StringBuilder lines = new StringBuilder();
    for (final Map.Entry<String, LongAdder> hit : new TreeMap<>(hits).entrySet()) {
      lines.append(hit.getValue().sum()).append('\t').append(oneLine(hit.getKey())).append('\n');
    }
    writeAtomically(HITS_FILE_PREFIX + pid, lines.toString());
  }

  private void writeHitsWhileOpen() {
    BootstrapDispatcher.beginInternalCall();
    try {
      while (!closed.get()) {
        LockSupport.parkNanos(this, BATCH_NANOS);
        if (hitsDirty.getAndSet(false)) {
          writeHits();
        }
      }
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  private void writeAtomically(final String name, final String content) {
    writeAtomically(
        name,
        content,
        directory.getFileSystem().supportedFileAttributeViews(),
        ChaosReports::ownerName,
        System.getProperty("user.name", ""));
  }

  /**
   * Test seam of {@link #writeAtomically(String, String)}, with the parameters of {@link
   * #removeStatus(Set, Function, String)}.
   */
  void writeAtomically(
      final String name,
      final String content,
      final Set<String> supportedViews,
      final Function<Path, String> ownerOf,
      final String jvmUser) {
    BootstrapDispatcher.beginInternalCall();
    Path staging = null;
    try {
      Files.createDirectories(directory);
      if (!isOurs(directory.resolve(name), supportedViews, ownerOf, jvmUser)) {
        return;
      }
      staging =
          directory.resolve(
              ".chaos-staging-"
                  + pid
                  + "-"
                  + Long.toHexString(ThreadLocalRandom.current().nextLong())
                  + "-"
                  + name);
      // CREATE_NEW: never write through a file or link someone else placed in a shared directory.
      Files.writeString(
          staging,
          content,
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE);
      Files.move(
          staging,
          directory.resolve(name),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (final IOException | RuntimeException failed) {
      // A report that cannot be written leaves the previous one in place; chaos must not fail
      // because a report directory is unwritable. The first failure says why.
      if (failureLogged.compareAndSet(false, true)) {
        LOGGER.log(
            Level.WARNING, failed, () -> "[chaos-agent] cannot write reports in " + directory);
      }
      if (staging != null) {
        try {
          Files.deleteIfExists(staging);
        } catch (final IOException ignored) {
          // nothing more to do
        }
      }
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  private static String oneLine(final @Nullable String value) {
    if (value == null) {
      return "";
    }
    final String flat = value.replaceAll("[\\p{Cntrl}]", " ");
    return flat.length() > 200 ? flat.substring(0, 200) : flat;
  }

  /**
   * Stops the writer thread and waits for it, then writes the hits file a last time: once this
   * returns, no write of these reports is still running or still to come.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    runtime.removeLifecycleListener(hitCounter);
    LockSupport.unpark(hitsWriter);
    try {
      hitsWriter.join();
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    writeFinalHits();
    if (current == this) {
      current = null;
    }
    BootstrapDispatcher.beginInternalCall();
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (final IllegalStateException shuttingDown) {
      // the hook is running or has run
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }
}

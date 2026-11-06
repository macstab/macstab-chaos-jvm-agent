package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
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
 *   <li><b>Hits file</b> {@code <dir>/.chaos-jvm.hits.<pid>}, one line per scenario: {@code
 *       <applied count>\t<scenario id>}. Written after each of the first 64 applications, then at
 *       most every 100 ms while applications happen, and at exit; not at all in a JVM where no
 *       scenario ever applied.
 * </ul>
 *
 * <p>Both are written to a staging file and renamed over the report, so a reader never sees a
 * partial file. The writes run with the dispatch depth raised: a file I/O scenario never applies to
 * the agent's own reports. {@code <dir>} is {@code java.io.tmpdir} ({@code /tmp} on Linux, like the
 * library) unless {@code reportsDir} is set. Both files are off unless {@code reports=on}: an agent
 * leaves nothing in a shared directory by default.
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

  private static volatile @Nullable ChaosReports current;

  private final Path directory;
  private final long pid;
  private final ChaosRuntime runtime;
  private final AtomicBoolean hitsDirty = new AtomicBoolean();
  private final AtomicLong applications = new AtomicLong();
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
   * @param agentArgs the raw agent arguments
   * @param environment the process environment
   * @param runtime the runtime whose scenarios are reported
   * @return the reports, or empty when not switched on
   */
  static Optional<ChaosReports> createIfEnabled(
      final @Nullable String agentArgs,
      final Map<String, String> environment,
      final ChaosRuntime runtime) {
    final String enabled =
        AgentSettings.valueOrEmpty(
            agentArgs, environment, REPORTS_ARG, REPORTS_PROPERTY, REPORTS_ENV);
    if (!"on".equalsIgnoreCase(enabled) && !"true".equalsIgnoreCase(enabled)) {
      return Optional.empty();
    }
    final String dir =
        AgentSettings.valueOrEmpty(
            agentArgs, environment, REPORTS_DIR_ARG, REPORTS_DIR_PROPERTY, REPORTS_DIR_ENV);
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

  private void start() {
    runtime.addEventListener(hitCounter);
    BootstrapDispatcher.beginInternalCall();
    try {
      hitsWriter.start();
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  private void countHit(final ChaosEvent event) {
    if (event.type() == ChaosEvent.Type.APPLIED) {
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
    BootstrapDispatcher.beginInternalCall();
    try {
      Files.deleteIfExists(directory.resolve(STATUS_FILE));
    } catch (final IOException ignored) {
      // a stale report is better than a failing poller
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  /** Writes the hits file now. */
  void writeHits() {
    final StringBuilder lines = new StringBuilder();
    for (final ChaosDiagnostics.ScenarioReport report :
        runtime.diagnostics().snapshot().scenarios()) {
      lines.append(report.appliedCount()).append('\t').append(oneLine(report.id())).append('\n');
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
    BootstrapDispatcher.beginInternalCall();
    Path staging = null;
    try {
      Files.createDirectories(directory);
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
        java.util.logging.Logger.getLogger(ChaosReports.class.getName())
            .log(
                java.util.logging.Level.WARNING,
                failed,
                () -> "chaos-agent: cannot write reports in " + directory);
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

  /** Writes the hits file a last time and stops the writer thread. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    runtime.removeEventListener(hitCounter);
    LockSupport.unpark(hitsWriter);
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

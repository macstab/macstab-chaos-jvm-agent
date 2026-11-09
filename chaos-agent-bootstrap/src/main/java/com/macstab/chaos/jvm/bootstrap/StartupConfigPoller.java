package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.core.DefaultChaosActivationHandle;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.startup.AgentSettings;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import com.macstab.chaos.jvm.startup.LogText;
import com.macstab.chaos.jvm.startup.StartupConfigLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Polls a chaos configuration file at a fixed interval and applies incremental diffs to the {@link
 * ChaosRuntime} scenario registry.
 *
 * <p>Only scenarios sourced from the watched config file are managed by this poller. Scenarios
 * activated programmatically via {@code ChaosRuntime.activate()} are invisible to it and are never
 * stopped or replaced by a reload.
 *
 * <p>The diff algorithm compares by scenario identity and full structural equality:
 *
 * <ul>
 *   <li>Scenarios present in the new config but not in current state → activated
 *   <li>Scenarios in current state but absent from the new config → stopped
 *   <li>Scenarios with the same ID but different content → stopped, then re-activated
 *   <li>Scenarios identical in both (same ID, same content) → kept running, untouched
 * </ul>
 *
 * <p>Enable by setting {@code configWatchInterval=<ms>} in the agent args, the system property
 * {@code macstab.chaos.configWatchInterval} or the environment variable {@code
 * MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL} (alias {@code MACSTAB_CHAOS_WATCH_INTERVAL}). A value of
 * {@code 0} or absent disables watching (read-once behaviour is preserved).
 *
 * <p>Example:
 *
 * <pre>{@code
 * java -javaagent:chaos-agent.jar=configFile=/etc/chaos/plan.json;configWatchInterval=500 -jar app.jar
 * }</pre>
 *
 * or via environment:
 *
 * <pre>{@code
 * MACSTAB_CHAOS_CONFIG_FILE=/etc/chaos/plan.json
 * MACSTAB_CHAOS_WATCH_INTERVAL=500
 * }</pre>
 */
public final class StartupConfigPoller implements AutoCloseable {

  /** Environment variable that sets the poll interval in milliseconds. */
  public static final String ENV_CONFIG_WATCH_INTERVAL = "MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL";

  /** Alias of {@link #ENV_CONFIG_WATCH_INTERVAL}, read after it. */
  public static final String ENV_WATCH_INTERVAL = "MACSTAB_CHAOS_WATCH_INTERVAL";

  /** System property that sets the poll interval in milliseconds, read after the agent arg. */
  public static final String PROPERTY_WATCH_INTERVAL = "macstab.chaos.configWatchInterval";

  /** Agent arg key that sets the poll interval in milliseconds, overrides the env var. */
  public static final String ARG_WATCH_INTERVAL = "configWatchInterval";

  private final Path configPath;
  private final ChaosRuntime runtime;
  private final long intervalMs;
  private final ScheduledExecutorService scheduler;

  /** Runs after a read and before its re-stat; a no-op outside tests (U-72 c1). */
  private final Runnable afterRead;

  // Shared with close(); guarded by synchronized(this).
  private final Map<String, ChaosScenario> activeScenarios = new HashMap<>();
  private final Map<String, ChaosActivationHandle> activeHandles = new HashMap<>();

  /** The scenario ids the current plan's observability settings apply to. */
  private Set<String> observedIds = Set.of();

  /** The current plan's observability settings; {@code null} before the first plan. */
  private com.macstab.chaos.jvm.api.ChaosPlan.@Nullable Observability observed;

  /** A read whose file was modified this recently may still be in the middle of a write. */
  private static final long PROVISIONAL_MILLIS = 20L;

  /**
   * The provisional window of a whole-second modification time: a file system that stores whole
   * seconds gives an edit in the same second the same stamp.
   */
  private static final long WHOLE_SECOND_PROVISIONAL_MILLIS = 1_000L;

  // Confined to the poll thread: written at start before the schedule begins (which
  // happens-before every tick), then only by pollOnce, whose runs never overlap.
  /** The stamp of the applied contents; while it is unchanged the file is not read. */
  private StartupConfigLoader.@Nullable PlanFileStamp lastStamp;

  /** The stamp of contents that can never apply; while it is unchanged the file is not read. */
  private StartupConfigLoader.@Nullable PlanFileStamp failedStamp;

  private byte @Nullable [] lastDigest;
  private byte @Nullable [] failedDigest;

  /**
   * Until a read begins at or after this time (epoch millis), the file is read on every tick even
   * if its stamp did not change.
   */
  private long provisionalUntil;

  /**
   * When the last read of the file began (epoch millis); it settles the stamp once past the window.
   */
  private long lastReadStartedAt = Long.MIN_VALUE;

  /** Whether a missing file has been reported since it disappeared. */
  private boolean missingReported;

  /** The previous tick found the file missing; a second one in a row stops its scenarios. */
  private boolean missingOnce;

  /** Last reload failure that was logged; identical consecutive failures are logged once. */
  private @Nullable String lastLoggedFailure;

  // Set by close() before the scheduler stops, read by applyDiff under the monitor: a poll that
  // outlives the shutdown (blocked in an uninterruptible read) then activates nothing, so no
  // scenario stays registered without an owner.
  private volatile boolean closed = false;

  /** Runs before each read of the plan file; a test seam (U-72 c1), a no-op otherwise. */
  private volatile Runnable readHook = () -> {};

  private StartupConfigPoller(
      final Path configPath, final ChaosRuntime runtime, final long intervalMs) {
    this(configPath, runtime, intervalMs, () -> {});
  }

  /**
   * Creates a poller that runs {@code afterRead} between reading the file and re-checking its
   * attributes. Test seam (U-72 c1): a test changes the file there, which a real writer does only
   * by chance.
   */
  StartupConfigPoller(
      final Path configPath,
      final ChaosRuntime runtime,
      final long intervalMs,
      final Runnable afterRead) {
    this.configPath = configPath;
    this.runtime = runtime;
    this.intervalMs = intervalMs;
    this.afterRead = afterRead;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              final Thread thread = new Thread(() -> internally(runnable), "chaos-config-poller");
              thread.setDaemon(true);
              return thread;
            });
  }

  /**
   * Creates a poller if watch mode is configured, otherwise returns empty.
   *
   * <p>Agent arg {@code configWatchInterval} takes precedence over the system property {@code
   * macstab.chaos.configWatchInterval}, then the environment variable {@code
   * MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL}, then its alias {@code MACSTAB_CHAOS_WATCH_INTERVAL}. A
   * value of {@code 0} or absent disables watching; a value that is not a number disables it with
   * one line on stderr.
   *
   * @param settings the agent's settings
   * @param configPath the resolved config file path to watch
   * @param runtime the live runtime to apply diffs against
   * @return an unstarted poller, or empty if watching is not configured
   */
  public static Optional<StartupConfigPoller> createIfEnabled(
      final AgentSettings settings, final Path configPath, final ChaosRuntime runtime) {
    final long intervalMs = resolveInterval(settings);
    if (intervalMs <= 0) {
      return Optional.empty();
    }
    return Optional.of(new StartupConfigPoller(configPath, runtime, intervalMs));
  }

  /**
   * Applies the initial plan and starts polling, with the file read it came from as the baseline: a
   * change after that read is applied on the next tick.
   *
   * @param initialPlan the plan from the first file read
   * @param baseline that read; {@code null} to let the first tick reconcile with the file
   */
  public void startWithInitialPlan(
      final com.macstab.chaos.jvm.api.ChaosPlan initialPlan,
      final StartupConfigLoader.@Nullable PlanFile baseline) {
    if (baseline != null) {
      lastStamp = stampOf(baseline);
      lastDigest = digest(baseline.json());
      provisionalUntil = provisionalUntil(baseline.lastModified());
      lastReadStartedAt = baseline.readStartedAt();
    }
    applyDiff(initialPlan.scenarios(), initialPlan.observability());
    // Reported before the first tick can run, so a tick's own report is never overwritten.
    ChaosReports.current()
        .ifPresent(
            reports -> reports.statusOk(initialPlan.scenarios().size(), "file:" + configPath));
    // The future is not needed: pollOnce() catches every Throwable, so the schedule never ends
    // with a failure that the future would report; close() stops the scheduler. A fixed delay:
    // ticks delayed by a slow read are not run back to back to catch up.
    // The scheduler is the agent's own (L3): the schedule call and the start of its thread run with
    // the dispatch depth raised, and so does every tick, so neither a SCHEDULE_SUBMIT or
    // SCHEDULE_TICK scenario nor a THREAD_START reject reaches the poller.
    internally(
        () -> {
          @SuppressWarnings("FutureReturnValueIgnored")
          final Object unused =
              scheduler.scheduleWithFixedDelay(
                  this::pollOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        });
  }

  /**
   * Stops the polling scheduler and fully unregisters all scenarios currently managed by this
   * poller.
   *
   * <p>Programmatically activated scenarios are not affected. Calls {@code destroy()} rather than
   * {@code stop()} because a stopped-but-registered scenario still occupies a slot in the registry
   * — across many reload cycles or test runs those entries accumulate without bound.
   */
  @Override
  public void close() {
    // Set the closed flag BEFORE shutdownNow() so an in-flight pollOnce that reaches applyDiff
    // after shutdownNow returns but before we enter the synchronized block below sees closed
    // and bails out — without this, a poll blocked in Files.newInputStream (uninterruptible)
    // would unblock, parse, and then re-activate every scenario it parsed into the soon-to-be-
    // cleared maps of this already-closed poller, leaving zombies registered in the runtime
    // with no owning handle.
    closed = true;
    BootstrapDispatcher.beginInternalCall();
    try {
      scheduler.shutdownNow();
      // Best-effort: give the in-flight poll a short window to complete before we tear the maps
      // down. If it completes, the closed check inside applyDiff short-circuits it. If it's
      // still blocked in Files.newInputStream after the timeout we accept the window and rely on
      // the closed flag alone — the poll thread is a daemon so it will not block JVM exit.
      scheduler.awaitTermination(500, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException ignored) {
      Thread.currentThread().interrupt();
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
    synchronized (this) {
      activeHandles.values().forEach(StartupConfigPoller::destroyHandle);
      activeHandles.clear();
      activeScenarios.clear();
    }
  }

  private static void destroyHandle(final @Nullable ChaosActivationHandle handle) {
    if (handle == null) {
      return;
    }
    if (handle instanceof DefaultChaosActivationHandle defaultHandle) {
      defaultHandle.destroy();
    } else {
      handle.stop();
    }
  }

  // ── internals ─────────────────────────────────────────────────────────────

  void pollOnce() {
    try {
      // The stamp first: a file whose stamp did not change is not read, so a file the JVM may no
      // longer read (chmod changes the change time only) and a large file cost one stat per tick.
      final Optional<StartupConfigLoader.PlanFileStamp> stamp =
          internally(() -> StartupConfigLoader.statPlanFile(configPath.toString()));
      if (stamp.isPresent()) {
        missingOnce = false;
        // Settled only by a read that began after the window: an edit within the window that keeps
        // the stamp (a same-size edit in the same whole second) is then seen by the next read.
        if (lastReadStartedAt >= provisionalUntil) {
          if (stamp.get().equals(lastStamp)) {
            recovered();
            return;
          }
          if (stamp.get().equals(failedStamp)) {
            return;
          }
        }
      }
      // Also empty when the file went away between the stat and the read.
      final Optional<StartupConfigLoader.PlanFile> file =
          stamp.isEmpty() ? Optional.empty() : internally(this::readCurrentFile);
      if (file.isEmpty()) {
        handleMissingFile();
        return;
      }
      reloadIfChanged(file.get());
    } catch (final Throwable throwable) {
      // Catch Throwable, not Exception: ScheduledExecutorService.scheduleWithFixedDelay silently
      // suppresses all future executions if the task throws anything, including Errors. Log here
      // and keep the scheduler alive, also after an Error (a StackOverflowError, an
      // OutOfMemoryError): the next tick retries, while a rethrow would end the watching for good.
      // Re-interrupt on InterruptedException. A failed read or parse keeps the current plan. A
      // failure that recurs on every tick is logged and reported once.
      if (throwable instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      if (logReloadFailure(ChaosReports.reasonOf(throwable))) {
        reportReloadFailure(throwable);
      }
    }
  }

  private Optional<StartupConfigLoader.PlanFile> readCurrentFile() {
    readHook.run();
    return StartupConfigLoader.readPlanFile(configPath.toString());
  }

  /** File I/O of the poller's own, which scenarios on the application's file I/O do not see. */
  private static <T> T internally(final InternalIo<T> io) throws IOException {
    BootstrapDispatcher.beginInternalCall();
    try {
      return io.run();
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  /** Work of the poller's own (its thread's body, its schedule), which no scenario sees. */
  private static void internally(final Runnable work) {
    BootstrapDispatcher.beginInternalCall();
    try {
      work.run();
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  /** A file operation of the poller's own. */
  @FunctionalInterface
  private interface InternalIo<T> {
    T run() throws IOException;
  }

  /**
   * Test seam (U-72 c1): runs {@code hook} on the poll thread before each read of the plan file, so
   * a test can fail a tick the way a broken file system or the JVM would.
   *
   * @param hook what to run before each read
   */
  void onRead(final Runnable hook) {
    readHook = java.util.Objects.requireNonNull(hook, "hook");
  }

  /**
   * Reloads when the contents changed. A new stamp with the same contents (a touch, a ConfigMap
   * re-sync) only updates the stamp. Contents that already failed to parse are not parsed again
   * until they change. A file read within {@value #PROVISIONAL_MILLIS} ms of its modification (one
   * second for a whole-second modification time) may be mid-write, and an edit within that window
   * may keep the stamp, so the file is read again on every tick until a read that began after the
   * window; the window counts from when the stamp was first seen at the latest, so a modification
   * time ahead of the clock does not keep it open.
   */
  private void reloadIfChanged(final StartupConfigLoader.PlanFile file) throws IOException {
    final StartupConfigLoader.PlanFileStamp stamp = stampOf(file);
    lastReadStartedAt = file.readStartedAt();
    final byte[] digest = digest(file.json());
    if (java.util.Arrays.equals(digest, lastDigest)) {
      settle(stamp);
      recovered();
      return;
    }
    if (java.util.Arrays.equals(digest, failedDigest)) {
      return;
    }
    // Re-stat after the read: if the file changed while it was being read, the bytes may be
    // torn. Skip and retry on the next tick, which sees the settled file.
    afterRead.run();
    final java.nio.file.attribute.BasicFileAttributes after;
    try {
      after =
          internally(
              () ->
                  Files.readAttributes(
                      file.target(),
                      java.nio.file.attribute.BasicFileAttributes.class,
                      LinkOption.NOFOLLOW_LINKS));
    } catch (final java.nio.file.NoSuchFileException swappedAway) {
      // A ConfigMap swap removed the directory just read: the next tick reads the new one.
      return;
    }
    if (!after.lastModifiedTime().equals(file.lastModified()) || after.size() != file.size()) {
      return;
    }
    final com.macstab.chaos.jvm.api.ChaosPlan newPlan;
    try {
      newPlan = ChaosPlanMapper.read(file.json());
      applyDiff(newPlan.scenarios(), newPlan.observability());
      // A close that overtook this reload made applyDiff a no-op: nothing was applied, so
      // nothing is recorded or reported.
      if (closed) {
        return;
      }
    } catch (final RuntimeException | Error failure) {
      // Remember content that can never apply (malformed, invalid, hooks that cannot be woven, an
      // Error while applying it) so it is not tried again; a conflict with a scenario activated
      // elsewhere may clear and is retried.
      if (failure instanceof Error || isDeterministic(failure)) {
        failedDigest = digest;
        failedStamp = stamp;
        provisionalUntil = provisionalUntil(stamp.lastModified());
      }
      throw failure;
    }
    settle(stamp);
    lastDigest = digest;
    failedDigest = null;
    failedStamp = null;
    lastLoggedFailure = null;
    missingReported = false;
    ChaosReports.current()
        .ifPresent(reports -> reports.statusOk(newPlan.scenarios().size(), "file:" + configPath));
    System.err.println(
        "[chaos-agent] config reloaded: "
            + newPlan.scenarios().size()
            + " scenario(s) from "
            + LogText.sanitize(configPath.toString()));
  }

  /**
   * The applied plan reads cleanly again after a failure (a broken edit reverted, a read that
   * failed during a ConfigMap swap): report it as loaded, and forget the failed content so that it
   * is reported again if it comes back.
   */
  private void recovered() {
    if (lastLoggedFailure == null) {
      return;
    }
    lastLoggedFailure = null;
    failedDigest = null;
    failedStamp = null;
    final int scenarios;
    synchronized (this) {
      scenarios = activeScenarios.size();
    }
    ChaosReports.current().ifPresent(reports -> reports.statusOk(scenarios, "file:" + configPath));
  }

  /**
   * Whether a reload failure would recur for the same file content: then it is not retried (and so
   * recorded once) until the file changes. A scenario the runtime cannot run (a JVM flag, a limit)
   * is as final as an invalid one; a conflict with a scenario activated elsewhere may clear.
   */
  private static boolean isDeterministic(final Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof com.macstab.chaos.jvm.startup.ConfigLoadException
          || cause instanceof com.macstab.chaos.jvm.api.ChaosValidationException
          || cause instanceof com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException
          || cause instanceof IllegalArgumentException) {
        return true;
      }
      if (cause instanceof com.macstab.chaos.jvm.api.ChaosActivationException) {
        // A conflict with a scenario activated elsewhere may clear; any other failure is final.
        return !(cause instanceof com.macstab.chaos.jvm.api.ChaosActivationConflictException);
      }
      if (cause.getCause() == null || cause.getCause().equals(cause)) {
        break;
      }
    }
    return false;
  }

  /**
   * The configured file is gone: every scenario it activated is stopped, and the status file is
   * removed, like the native library does without a rule file. When the file comes back it is
   * loaded as a change.
   */
  private void handleMissingFile() {
    if (missingReported) {
      return;
    }
    // An editor saving by rename-then-write, or rm followed by cp, leaves the path missing for a
    // moment; stopping every scenario then (and re-activating them) would reset their counters,
    // budgets and windows. Act only when the file is still missing on the next tick.
    if (!missingOnce) {
      missingOnce = true;
      return;
    }
    missingReported = true;
    final int stopped;
    synchronized (this) {
      stopped = activeScenarios.size();
      applyDiff(List.of());
      observe(Set.of(), null);
    }
    lastStamp = null;
    failedStamp = null;
    lastDigest = null;
    failedDigest = null;
    // The status file is gone: a failure after the file comes back is reported again.
    lastLoggedFailure = null;
    ChaosReports.current().ifPresent(ChaosReports::removeStatus);
    System.err.println(
        "[chaos-agent] config file removed: "
            + stopped
            + " scenario(s) stopped ("
            + LogText.sanitize(configPath.toString())
            + ")");
  }

  private static StartupConfigLoader.PlanFileStamp stampOf(
      final StartupConfigLoader.PlanFile file) {
    return new StartupConfigLoader.PlanFileStamp(
        file.target(), file.lastModified(), file.size(), file.fileKey());
  }

  /** Records the stamp of the applied contents; a new stamp opens its provisional window. */
  private void settle(final StartupConfigLoader.PlanFileStamp stamp) {
    if (!stamp.equals(lastStamp)) {
      lastStamp = stamp;
      provisionalUntil = provisionalUntil(stamp.lastModified());
    }
  }

  /**
   * The end of the window in which a file first seen now may still change under the same stamp:
   * {@value #PROVISIONAL_MILLIS} ms after its modification ({@value
   * #WHOLE_SECOND_PROVISIONAL_MILLIS} ms for a whole-second modification time), counted from now at
   * the latest.
   */
  private static long provisionalUntil(final FileTime lastModified) {
    final long modified = lastModified.toMillis();
    final long window =
        Math.floorMod(modified, 1_000L) == 0L
            ? WHOLE_SECOND_PROVISIONAL_MILLIS
            : PROVISIONAL_MILLIS;
    return Math.min(modified, System.currentTimeMillis()) + window;
  }

  private static byte[] digest(final String json) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256")
          .digest(json.getBytes(StandardCharsets.UTF_8));
    } catch (final java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is required of every JDK", unavailable);
    }
  }

  /**
   * Applies a plan's observability settings to its scenarios and restores the defaults of scenarios
   * that left the plan; {@code null} settings restore the defaults of all of them.
   */
  private synchronized void observe(
      final Set<String> ids,
      final com.macstab.chaos.jvm.api.ChaosPlan.@Nullable Observability observability) {
    final Set<String> gone = new java.util.HashSet<>(observedIds);
    if (observability != null) {
      gone.removeAll(ids);
    }
    runtime.clearPlanObservability(gone, this);
    if (observability != null) {
      runtime.applyPlanObservability(ids, observability, this);
    }
    observedIds = observability != null ? Set.copyOf(ids) : Set.of();
    observed = observability;
  }

  /**
   * Applies a reloaded plan's scenarios without changing the observability settings (see {@link
   * #applyDiff(List, com.macstab.chaos.jvm.api.ChaosPlan.Observability)}).
   *
   * @param newScenarios the scenarios of the reloaded plan
   */
  void applyDiff(final List<ChaosScenario> newScenarios) {
    applyDiff(newScenarios, null);
  }

  /**
   * Applies a reloaded plan atomically: either every new and changed scenario is active afterwards,
   * or the previously active set is restored.
   *
   * <p>Every new or changed scenario is validated first, and its id checked against scenarios
   * activated elsewhere; an invalid one, or one whose id is taken, rejects the plan before anything
   * changes. Then the plan's observability settings are applied, so its scenarios start with them,
   * and scenarios that were removed or changed are stopped (their ids must be free for the new
   * versions), and every new or changed scenario is activated. If an activation still fails (a
   * conflict with a scenario activated elsewhere, a weaving failure), the scenarios activated by
   * this call are closed, the previous settings restored and the previous versions re-activated;
   * they passed validation when they were first activated. A reloaded plan is therefore never
   * partially applied, and a changed scenario that fails never stays stopped.
   *
   * @param newScenarios the scenarios of the reloaded plan, with distinct ids (a {@link
   *     com.macstab.chaos.jvm.api.ChaosPlan} refuses duplicates)
   * @param observability the plan's observability settings; {@code null} leaves them unchanged
   * @throws IllegalStateException when a scenario fails to activate; the previous set is active
   *     again when this is thrown. An {@link Error} of an activation is rethrown as it is, after
   *     the same rollback
   */
  synchronized void applyDiff(
      final List<ChaosScenario> newScenarios,
      final com.macstab.chaos.jvm.api.ChaosPlan.@Nullable Observability observability) {
    // Race guard: close() sets `closed=true` before entering this monitor. An in-flight pollOnce
    // that was blocked on file I/O may reach applyDiff after close() returned; activating into a
    // torn-down poller would orphan the handles. Bail out instead.
    if (closed) {
      return;
    }
    final Map<String, ChaosScenario> newById = new java.util.LinkedHashMap<>(newScenarios.size());
    for (final ChaosScenario scenario : newScenarios) {
      newById.put(scenario.id(), scenario);
    }

    // Validate every new or changed scenario, and weave its hooks (method selectors included),
    // before touching the active set: an invalid plan, or one whose hooks cannot be woven, then
    // leaves the running scenarios running, instead of stopping the changed ones, activating part
    // of the new plan and rolling back (which restarts them, resetting their counters, budgets and
    // windows). The runtime records each refusal (FAILED and the diagnostics); a refusal that
    // recurs for the same content is not retried, so it is recorded once.
    for (final ChaosScenario scenario : newById.values()) {
      if (scenario.equals(activeScenarios.get(scenario.id()))) {
        continue;
      }
      try {
        runtime.validate(scenario);
        runtime.requireHooks(scenario);
      } catch (final RuntimeException invalid) {
        throw new IllegalStateException(
            "plan not applied, previous scenarios kept: " + invalid.getMessage(), invalid);
      }
      // An id taken by a scenario activated elsewhere would fail the activation below after the
      // changed scenarios were stopped, and every retry would stop and restart them again
      // (resetting their counters, budgets and windows). Refuse before anything changes; the
      // conflict stays retryable, so it is not recorded (stderr and the status file report it
      // once). A changed id is taken when the live scenario of that id is not this poller's own:
      // its own ended (INACTIVE) or was replaced by another activation (STOPPED).
      final ChaosActivationHandle own = activeHandles.get(scenario.id());
      if (runtime.isActive(scenario.id())
          && (own == null
              || own.state() == com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioState.INACTIVE
              || own.state() == com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioState.STOPPED)) {
        final String conflict = "scenario key already active: jvm::" + scenario.id();
        throw new IllegalStateException(
            "plan not applied, previous scenarios kept: " + conflict,
            new com.macstab.chaos.jvm.api.ChaosActivationConflictException(conflict));
      }
    }

    // Applied after the checks, so a refused plan never takes over the settings of the running
    // one, and before the activations, so the new scenarios start with them.
    final Set<String> previousIds = observedIds;
    final com.macstab.chaos.jvm.api.ChaosPlan.@Nullable Observability previousObservability =
        observed;
    if (observability != null) {
      observe(newById.keySet(), observability);
    }

    final Map<String, ChaosScenario> replaced = new java.util.LinkedHashMap<>();
    for (final String id : new java.util.ArrayList<>(activeScenarios.keySet())) {
      final ChaosScenario existing = activeScenarios.get(id);
      final ChaosScenario incoming = newById.get(id);
      if (incoming == null || !incoming.equals(existing)) {
        destroyHandle(activeHandles.remove(id));
        activeScenarios.remove(id);
        replaced.put(id, existing);
      }
    }

    final Map<String, ChaosActivationHandle> activated = new java.util.LinkedHashMap<>();
    try {
      for (final ChaosScenario scenario : newById.values()) {
        if (!activeScenarios.containsKey(scenario.id())) {
          activated.put(scenario.id(), runtime.activate(scenario));
        }
      }
    } catch (final RuntimeException | Error activationFailure) {
      // An Error too (core rethrows one after its own cleanup): the replaced scenarios are already
      // stopped, so without the rollback they would be gone and the new ones owned by nobody.
      activated.values().forEach(StartupConfigPoller::destroyHandle);
      if (observability != null) {
        observe(previousIds, previousObservability);
      }
      for (final ChaosScenario previous : replaced.values()) {
        try {
          activeHandles.put(previous.id(), runtime.activate(previous));
          activeScenarios.put(previous.id(), previous);
        } catch (final RuntimeException | Error restoreFailure) {
          activationFailure.addSuppressed(restoreFailure);
        }
      }
      if (activationFailure instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(
          "plan not applied, previous scenarios kept: " + activationFailure.getMessage(),
          activationFailure);
    }
    for (final Map.Entry<String, ChaosActivationHandle> entry : activated.entrySet()) {
      activeHandles.put(entry.getKey(), entry.getValue());
      activeScenarios.put(entry.getKey(), newById.get(entry.getKey()));
    }
  }

  private void reportReloadFailure(final Throwable failure) {
    ChaosReports.current()
        .ifPresent(
            reports -> {
              if (failure instanceof ConfigLoadException loadFailure) {
                reports.statusError(
                    "file:" + configPath,
                    loadFailure.line(),
                    loadFailure.column(),
                    loadFailure.reason());
              } else {
                reports.statusError("file:" + configPath, 0, 0, ChaosReports.reasonOf(failure));
              }
            });
  }

  /**
   * Logs a reload failure unless it is the one logged last.
   *
   * @return whether it was new (and logged): only then is it reported in the status file too
   */
  private boolean logReloadFailure(final String message) {
    final String sanitised = LogText.sanitize(message);
    if (sanitised.equals(lastLoggedFailure)) {
      return false;
    }
    lastLoggedFailure = sanitised;
    System.err.println("[chaos-agent] config reload failed: " + sanitised);
    return true;
  }

  /**
   * Lower bound on the poll interval. Values below this are clamped up to protect against a
   * stat-storm against network filesystems (ConfigMap mounts, NFS). A misconfigured {@code
   * configWatchInterval=1} would otherwise schedule one {@code getLastModifiedTime} per millisecond
   * — enough to saturate a kubelet's ConfigMap volume plugin on a single process. 50 ms is
   * aggressive for test iteration but well below any operational-impact threshold.
   */
  static final long MIN_WATCH_INTERVAL_MS = 50L;

  static long resolveInterval(final AgentSettings settings) {
    final long rawIntervalMs = resolveRawInterval(settings);
    if (rawIntervalMs <= 0) {
      return 0L;
    }
    return Math.max(rawIntervalMs, MIN_WATCH_INTERVAL_MS);
  }

  private static long resolveRawInterval(final AgentSettings settings) {
    // A present argument decides, also configWatchInterval=0 (off) over the environment.
    final String value =
        settings.valueOrEmpty(
            ARG_WATCH_INTERVAL,
            PROPERTY_WATCH_INTERVAL,
            ENV_CONFIG_WATCH_INTERVAL,
            ENV_WATCH_INTERVAL);
    if (value.isEmpty()) {
      return 0L;
    }
    try {
      final long interval = Long.parseLong(value);
      if (interval < 0) {
        System.err.println(
            "[chaos-agent] the watch interval '"
                + interval
                + "' is negative; the plan file is not watched (0 turns watching off)");
        return 0L;
      }
      return interval;
    } catch (final NumberFormatException notANumber) {
      System.err.println(
          "[chaos-agent] the watch interval ("
              + ARG_WATCH_INTERVAL
              + ", -D"
              + PROPERTY_WATCH_INTERVAL
              + ", "
              + ENV_CONFIG_WATCH_INTERVAL
              + " or "
              + ENV_WATCH_INTERVAL
              + ") '"
              + LogText.sanitize(value)
              + "' is not a number of milliseconds; the plan file is not watched");
      return 0L;
    }
  }
}

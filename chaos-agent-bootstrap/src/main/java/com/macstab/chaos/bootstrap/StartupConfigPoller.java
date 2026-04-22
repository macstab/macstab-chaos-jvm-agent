package com.macstab.chaos.bootstrap;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.core.ChaosRuntime;
import com.macstab.chaos.core.DefaultChaosActivationHandle;
import com.macstab.chaos.startup.AgentArgsParser;
import com.macstab.chaos.startup.ChaosPlanMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 * <p>Enable by setting {@code configWatchInterval=<ms>} in the agent args or {@code
 * MACSTAB_CHAOS_WATCH_INTERVAL=<ms>} as an environment variable. A value of {@code 0} or absent
 * disables watching (read-once behaviour is preserved).
 *
 * <p>Example:
 *
 * <pre>{@code
 * java -javaagent:chaos-agent.jar=configFile=/etc/chaos/plan.json,configWatchInterval=500 -jar app.jar
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
  public static final String ENV_WATCH_INTERVAL = "MACSTAB_CHAOS_WATCH_INTERVAL";

  /** Agent arg key that sets the poll interval in milliseconds, overrides the env var. */
  public static final String ARG_WATCH_INTERVAL = "configWatchInterval";

  private final Path configPath;
  private final ChaosRuntime runtime;
  private final long intervalMs;
  private final ScheduledExecutorService scheduler;

  // Guarded by synchronized(this)
  private final Map<String, ChaosScenario> activeScenarios = new HashMap<>();
  private final Map<String, ChaosActivationHandle> activeHandles = new HashMap<>();

  private volatile FileTime lastModified = FileTime.fromMillis(0);

  // Volatile close flag observed by applyDiff so a pollOnce that wins the race against
  // close() (e.g. blocked in Files.newInputStream and not interruptible) cannot silently
  // re-activate scenarios into a torn-down poller, leaving zombies registered in the runtime
  // that no one owns. Writes are under synchronized(this); reads in applyDiff are inside the
  // same monitor so plain read is sufficient, but marking volatile keeps close() lock-free.
  private volatile boolean closed = false;

  private StartupConfigPoller(
      final Path configPath, final ChaosRuntime runtime, final long intervalMs) {
    this.configPath = configPath;
    this.runtime = runtime;
    this.intervalMs = intervalMs;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              final Thread thread = new Thread(runnable, "chaos-config-poller");
              thread.setDaemon(true);
              return thread;
            });
  }

  /**
   * Creates a poller if watch mode is configured, otherwise returns empty.
   *
   * <p>Agent arg {@code configWatchInterval} takes precedence over env var {@code
   * MACSTAB_CHAOS_WATCH_INTERVAL}. A value of {@code 0} or absent disables watching.
   *
   * @param rawAgentArgs raw argument string from {@code -javaagent:}, may be null
   * @param environment environment variable map (typically {@link System#getenv()})
   * @param configPath the resolved config file path to watch
   * @param runtime the live runtime to apply diffs against
   * @return an unstarted poller, or empty if watching is not configured
   */
  public static Optional<StartupConfigPoller> createIfEnabled(
      final String rawAgentArgs,
      final Map<String, String> environment,
      final Path configPath,
      final ChaosRuntime runtime) {
    final long intervalMs = resolveInterval(rawAgentArgs, environment);
    if (intervalMs <= 0) {
      return Optional.empty();
    }
    return Optional.of(new StartupConfigPoller(configPath, runtime, intervalMs));
  }

  /**
   * Activates the initial set of scenarios and starts the polling loop.
   *
   * <p>The initial scenarios are applied as the first diff (from empty state), so they are tracked
   * by this poller and managed by all subsequent reloads. The polling scheduler begins ticking
   * after this call returns.
   *
   * @param initialScenarios scenarios from the first file read, already parsed by the caller
   */
  public void startWithInitialPlan(final List<ChaosScenario> initialScenarios) {
    // Capture the file's mtime BEFORE applying the initial plan. If the file is atomically
    // renamed between applyDiff and the stat, lastModified reflects the new file's mtime and
    // the first pollOnce would silently skip the new content. Capturing mtime first ensures the
    // poller's baseline is anchored to the file the caller already read and passed as
    // initialScenarios; a subsequent file change will then produce a visible mtime difference.
    final FileTime initialMtime = readLastModifiedTimeOrEpoch(configPath);
    applyDiff(initialScenarios);
    lastModified = initialMtime;
    scheduler.scheduleAtFixedRate(this::pollOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  private static FileTime readLastModifiedTimeOrEpoch(final Path path) {
    try {
      // NOFOLLOW_LINKS: a symlink swap between startup validation and this stat should fail
      // rather than silently bind the poller to the target of the symlink. Matches the read
      // path in pollOnce and in StartupConfigLoader.
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
    } catch (final IOException ignored) {
      return FileTime.fromMillis(0); // best-effort; if the stat fails the next poll will re-read
    }
  }

  /**
   * Returns the configured poll interval in milliseconds.
   *
   * @return poll interval in milliseconds; always {@code >= MIN_WATCH_INTERVAL_MS} when watching is
   *     enabled
   */
  public long intervalMs() {
    return intervalMs;
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
    scheduler.shutdownNow();
    // Best-effort: give the in-flight poll a short window to complete before we tear the maps
    // down. If it completes, the closed check inside applyDiff short-circuits it. If it's
    // still blocked in Files.newInputStream after the timeout we accept the window and rely on
    // the closed flag alone — the poll thread is a daemon so it will not block JVM exit.
    try {
      scheduler.awaitTermination(500, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    synchronized (this) {
      activeHandles.values().forEach(StartupConfigPoller::destroyHandle);
      activeHandles.clear();
      activeScenarios.clear();
    }
  }

  private static void destroyHandle(final ChaosActivationHandle handle) {
    if (handle instanceof DefaultChaosActivationHandle defaultHandle) {
      defaultHandle.destroy();
    } else {
      handle.stop();
    }
  }

  // ── internals ─────────────────────────────────────────────────────────────

  void pollOnce() {
    final FileTime mtimeBeforeRead;
    try {
      // NOFOLLOW_LINKS on getLastModifiedTime for the same reason as on the read: detect a
      // symlink swap at the config path instead of silently watching the target.
      mtimeBeforeRead = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS);
    } catch (final IOException exception) {
      System.err.println(
          "[chaos-agent] config poll failed (stat): " + sanitiseForLog(exception.getMessage()));
      return;
    }
    if (mtimeBeforeRead.equals(lastModified)) {
      return;
    }
    try {
      reloadIfFileSizeAcceptable(mtimeBeforeRead);
    } catch (final Throwable throwable) {
      // Catch Throwable, not Exception: ScheduledExecutorService.scheduleAtFixedRate silently
      // suppresses all future executions if the task throws anything, including Errors. A
      // transient NoClassDefFoundError from the JSON mapper's classloader, an OOM from a
      // huge plan, a StackOverflowError from a pathological config — any of those would
      // permanently kill config reloading with no log and no way to notice. Log here and keep
      // the scheduler alive; the next tick retries. Re-interrupt on InterruptedException to
      // preserve the flag, and re-throw VirtualMachineError (OOM etc.) since continuing past
      // those is not meaningful.
      if (throwable instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.err.println(
          "[chaos-agent] config reload failed (parse): " + sanitiseForLog(throwable.getMessage()));
      if (throwable instanceof VirtualMachineError virtualMachineError) {
        throw virtualMachineError;
      }
    }
  }

  private void reloadIfFileSizeAcceptable(final FileTime mtimeBeforeRead) throws IOException {
    final long fileSize = readFileSize();
    if (fileSize < 0) {
      return; // size-check failed, error already logged
    }
    if (fileSize > MAX_FILE_SIZE_BYTES) {
      System.err.println(
          "[chaos-agent] config reload skipped: file size "
              + fileSize
              + " exceeds limit "
              + MAX_FILE_SIZE_BYTES);
      return;
    }
    // NOFOLLOW_LINKS: if the config path was swapped for a symlink between stat and read
    // (e.g. by an attacker with write access to the config directory), the open fails
    // rather than silently following the link. Mirrors the check in StartupConfigLoader.
    final String json;
    try (final java.io.InputStream inputStream =
        Files.newInputStream(configPath, LinkOption.NOFOLLOW_LINKS)) {
      json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
    reloadIfMtimeSettled(json, mtimeBeforeRead);
  }

  private long readFileSize() {
    try {
      // Guard against OOM from arbitrarily large files replacing the config between the
      // mtime stat and the read. Mirrors the 1 MiB size cap in StartupConfigLoader.
      return Files.readAttributes(configPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
          .size();
    } catch (final IOException exception) {
      System.err.println(
          "[chaos-agent] config poll failed (size-check): "
              + sanitiseForLog(exception.getMessage()));
      return -1L;
    }
  }

  private void reloadIfMtimeSettled(final String json, final FileTime mtimeBeforeRead)
      throws IOException {
    // Re-stat after the read: if the mtime moved between the initial stat and end-of-read,
    // the writer was still producing the file while we were consuming it, so the bytes we
    // have are potentially truncated or mid-rewrite. Advancing lastModified with the stale
    // stat would permanently mask the final (complete) mtime once the writer finishes,
    // leaving the poller stuck on the torn read forever. Skip the parse and wait for the
    // next tick, which will see the settled mtime and a clean read. Editors that perform
    // atomic rename-into-place produce exactly one mtime change and are unaffected; editors
    // that truncate-and-write (vim default, `echo > file`) produce two — only the second
    // will parse.
    final FileTime mtimeAfterRead;
    try {
      mtimeAfterRead = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS);
    } catch (final IOException exception) {
      System.err.println(
          "[chaos-agent] config poll failed (post-read stat): "
              + sanitiseForLog(exception.getMessage()));
      return;
    }
    if (!mtimeAfterRead.equals(mtimeBeforeRead)) {
      // Don't advance lastModified: the next tick should see mtimeAfterRead (or a later value)
      // as a fresh change and retry. Logging at FINE would flood for active writers; stay silent.
      return;
    }
    final com.macstab.chaos.api.ChaosPlan newPlan = ChaosPlanMapper.read(json);
    applyDiff(newPlan.scenarios());
    // Only advance lastModified AFTER a successful parse+apply. Advancing it before the
    // parse would permanently mask the same-mtime case: a parse failure followed by the
    // operator correcting the file in place (without touching mtime) would never reload.
    lastModified = mtimeBeforeRead;
    System.err.println(
        "[chaos-agent] config reloaded: "
            + newPlan.scenarios().size()
            + " scenario(s) from "
            + sanitiseForLog(configPath.toString()));
  }

  synchronized void applyDiff(final List<ChaosScenario> newScenarios) {
    // Race guard: close() sets `closed=true` before entering this monitor. An in-flight pollOnce
    // that was blocked on file I/O may reach applyDiff after close() returned, holding a freshly
    // parsed plan. If we let it through, each "missing from activeScenarios" branch would call
    // runtime.activate(...) into a torn-down poller whose maps are then cleared — leaving the
    // activated handles orphaned in the runtime registry with no owner. Bail out instead.
    if (closed) {
      return;
    }
    // Detect duplicate IDs up front rather than letting Collectors.toMap throw
    // IllegalStateException mid-diff (which would leave the runtime in a partially-torn-down
    // state: scenarios whose content changed would already be stopped, but new/updated ones
    // would never be re-activated). A duplicate ID means the reloaded plan is malformed, so
    // skip the entire diff; the next successful reload will converge.
    final Map<String, ChaosScenario> newById = new java.util.HashMap<>(newScenarios.size());
    for (final ChaosScenario scenario : newScenarios) {
      final ChaosScenario previous = newById.putIfAbsent(scenario.id(), scenario);
      if (previous != null) {
        System.err.println(
            "[chaos-agent] config reload skipped: duplicate scenario id '"
                + sanitiseForLog(scenario.id())
                + "' in reloaded plan; no scenarios were changed");
        return;
      }
    }

    // Stop scenarios that were removed or whose content changed. Use destroy() rather than
    // stop() so the registry slot is freed; otherwise every reload leaks the old scenario
    // entry into diagnostics and the registry grows without bound.
    for (final String id : new HashSet<>(activeScenarios.keySet())) {
      final ChaosScenario existing = activeScenarios.get(id);
      final ChaosScenario incoming = newById.get(id);
      if (incoming == null || !incoming.equals(existing)) {
        destroyHandle(activeHandles.remove(id));
        activeScenarios.remove(id);
      }
    }

    // Activate scenarios that are new or were just stopped due to content change.
    // Each activation is attempted independently: a validation failure on one scenario must not
    // leave all subsequent scenarios unactivated, which would make the runtime inconsistent with
    // every reload after the first partial failure. Log and continue so the successfully
    // activatable subset is always applied.
    for (final ChaosScenario scenario : newScenarios) {
      if (!activeScenarios.containsKey(scenario.id())) {
        try {
          final ChaosActivationHandle handle = runtime.activate(scenario);
          activeHandles.put(scenario.id(), handle);
          activeScenarios.put(scenario.id(), scenario);
        } catch (final RuntimeException activationFailure) {
          System.err.println(
              "[chaos-agent] config reload: failed to activate scenario '"
                  + sanitiseForLog(scenario.id())
                  + "': "
                  + sanitiseForLog(activationFailure.getMessage()));
        }
      }
    }
  }

  /** Maximum number of characters rendered from a single log message before truncation. */
  static final int MAX_LOG_MESSAGE_LENGTH = 512;

  /**
   * Strips control characters (including CR/LF) from strings before they hit {@code System.err}.
   * Config file paths and parse-error messages can contain attacker-controlled bytes; without
   * sanitisation a chaos-agent log line could be forged by embedding {@code \n[chaos-agent] ...}
   * into a filename or JSON payload. Replaces any ASCII control character with U+FFFD and caps the
   * rendered length so that a huge error message cannot flood the log.
   */
  static String sanitiseForLog(final String raw) {
    if (raw == null) {
      return "null";
    }
    final int end = Math.min(raw.length(), MAX_LOG_MESSAGE_LENGTH);
    final StringBuilder sanitised = new StringBuilder(end);
    for (int i = 0; i < end; i++) {
      final char character = raw.charAt(i);
      if (character < 0x20 || character == 0x7F) {
        sanitised.append('\uFFFD');
      } else {
        sanitised.append(character);
      }
    }
    if (raw.length() > MAX_LOG_MESSAGE_LENGTH) {
      sanitised.append("...[truncated]");
    }
    return sanitised.toString();
  }

  /** Maximum config file size on the hot-reload path: 1 MiB. Matches StartupConfigLoader. */
  static final long MAX_FILE_SIZE_BYTES = 1_048_576L;

  /**
   * Lower bound on the poll interval. Values below this are clamped up to protect against a
   * stat-storm against network filesystems (ConfigMap mounts, NFS). A misconfigured {@code
   * configWatchInterval=1} would otherwise schedule one {@code getLastModifiedTime} per millisecond
   * — enough to saturate a kubelet's ConfigMap volume plugin on a single process. 50 ms is
   * aggressive for test iteration but well below any operational-impact threshold.
   */
  static final long MIN_WATCH_INTERVAL_MS = 50L;

  static long resolveInterval(final String rawAgentArgs, final Map<String, String> environment) {
    final long rawIntervalMs = resolveRawInterval(rawAgentArgs, environment);
    if (rawIntervalMs <= 0) {
      return 0L;
    }
    return Math.max(rawIntervalMs, MIN_WATCH_INTERVAL_MS);
  }

  private static long resolveRawInterval(
      final String rawAgentArgs, final Map<String, String> environment) {
    final long fromArg = AgentArgsParser.parse(rawAgentArgs).getLong(ARG_WATCH_INTERVAL, 0L);
    if (fromArg > 0) {
      return fromArg;
    }
    final String envValue = environment.get(ENV_WATCH_INTERVAL);
    if (envValue != null && !envValue.isBlank()) {
      try {
        return Long.parseLong(envValue.strip());
      } catch (NumberFormatException ignored) {
        return 0L;
      }
    }
    return 0L;
  }
}

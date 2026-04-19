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
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

  private StartupConfigPoller(
      final Path configPath, final ChaosRuntime runtime, final long intervalMs) {
    this.configPath = configPath;
    this.runtime = runtime;
    this.intervalMs = intervalMs;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              final Thread t = new Thread(r, "chaos-config-poller");
              t.setDaemon(true);
              return t;
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
    applyDiff(initialScenarios);
    try {
      // NOFOLLOW_LINKS: a symlink swap between startup validation and this stat should fail
      // rather than silently bind the poller to the target of the symlink. Matches the read
      // path in pollOnce and in StartupConfigLoader.
      lastModified = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException ignored) {
      // best-effort; if the stat fails the next poll will re-read the file
    }
    scheduler.scheduleAtFixedRate(this::pollOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  /** Returns the configured poll interval in milliseconds. */
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
    scheduler.shutdownNow();
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
    final FileTime current;
    try {
      // NOFOLLOW_LINKS on getLastModifiedTime for the same reason as on the read: detect a
      // symlink swap at the config path instead of silently watching the target.
      current = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      System.err.println(
          "[chaos-agent] config poll failed (stat): " + sanitiseForLog(e.getMessage()));
      return;
    }
    if (current.equals(lastModified)) {
      return;
    }
    try {
      // NOFOLLOW_LINKS: if the config path was swapped for a symlink between stat and read
      // (e.g. by an attacker with write access to the config directory), the open fails
      // rather than silently following the link. Mirrors the check in StartupConfigLoader.
      final String json;
      try (final java.io.InputStream in =
          Files.newInputStream(configPath, LinkOption.NOFOLLOW_LINKS)) {
        json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      final com.macstab.chaos.api.ChaosPlan newPlan = ChaosPlanMapper.read(json);
      applyDiff(newPlan.scenarios());
      // Only advance lastModified AFTER a successful parse+apply. Advancing it before the
      // parse would permanently mask the same-mtime case: a parse failure followed by the
      // operator correcting the file in place (without touching mtime) would never reload.
      lastModified = current;
      System.err.println(
          "[chaos-agent] config reloaded: "
              + newPlan.scenarios().size()
              + " scenario(s) from "
              + sanitiseForLog(configPath.toString()));
    } catch (Exception e) {
      System.err.println(
          "[chaos-agent] config reload failed (parse): " + sanitiseForLog(e.getMessage()));
    }
  }

  synchronized void applyDiff(final List<ChaosScenario> newScenarios) {
    final Map<String, ChaosScenario> newById =
        newScenarios.stream().collect(Collectors.toMap(ChaosScenario::id, s -> s));

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

    // Activate scenarios that are new or were just stopped due to content change
    for (final ChaosScenario scenario : newScenarios) {
      if (!activeScenarios.containsKey(scenario.id())) {
        final ChaosActivationHandle handle = runtime.activate(scenario);
        activeHandles.put(scenario.id(), handle);
        activeScenarios.put(scenario.id(), scenario);
      }
    }
  }

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
    final int max = 512;
    final int end = Math.min(raw.length(), max);
    final StringBuilder sb = new StringBuilder(end);
    for (int i = 0; i < end; i++) {
      final char ch = raw.charAt(i);
      if (ch < 0x20 || ch == 0x7F) {
        sb.append('\uFFFD');
      } else {
        sb.append(ch);
      }
    }
    if (raw.length() > max) {
      sb.append("...[truncated]");
    }
    return sb.toString();
  }

  static long resolveInterval(final String rawAgentArgs, final Map<String, String> environment) {
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

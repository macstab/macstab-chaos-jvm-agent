package com.macstab.chaos.jvm.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/**
 * Records which matches of each scenario took effect, so a run can be replayed exactly with {@link
 * ActivationPolicy.Builder#replayMatches(java.util.Collection)}, even when it was driven by an
 * unseeded probability.
 *
 * <pre>{@code
 * ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();
 * chaos.addEventListener(recorder);
 * runTheFlakyTest();
 * List<Long> fired = recorder.matches("flaky-db");      // e.g. [3, 17, 18]
 *
 * // later, the same decisions again:
 * ActivationPolicy.builder().replayMatches(fired).build();
 * }</pre>
 *
 * <p>A match number counts the scenario's selector matches since it started, from 1, including
 * matches skipped by the policy. Replay reproduces the decisions when the calls arrive in the same
 * order; concurrent callers can interleave differently between runs.
 *
 * <p>The log is kept per scenario and scope: the JVM ({@code "jvm"}) and each session ({@code
 * "session:<id>"}) have their own, as the event's {@code scope} attribute names it. A scenario that
 * starts again (a {@code STARTED} event) starts a new log for its scope, because its match numbers
 * start again from 1. At most {@value #MAX_DECISIONS} numbers are kept per log; beyond that only a
 * count of the dropped ones grows, and {@link #replay} refuses the truncated log.
 */
public final class ChaosDecisionRecorder implements ChaosEventListener {

  /** The most match numbers kept per scenario and scope. */
  public static final int MAX_DECISIONS = 1_000_000;

  private static final String JVM_SCOPE = "jvm";
  private static final String SESSION_SCOPE_PREFIX = "session:";

  /** Logs by scenario id, then by scope key ({@code "jvm"} or {@code "session:<id>"}). */
  private final Map<String, Map<String, Log>> fired = new ConcurrentHashMap<>();

  /** Creates an empty recorder. */
  public ChaosDecisionRecorder() {}

  @Override
  public void onEvent(final ChaosEvent event) {
    if (event.type() == ChaosEvent.Type.STARTED) {
      final Map<String, Log> byScope = fired.get(event.scenarioId());
      if (byScope != null) {
        byScope.remove(scopeOf(event));
      }
      return;
    }
    if (event.type() != ChaosEvent.Type.APPLIED) {
      return;
    }
    final String match = event.attributes().get("match");
    if (match != null) {
      fired
          .computeIfAbsent(event.scenarioId(), id -> new ConcurrentHashMap<>())
          .computeIfAbsent(scopeOf(event), scope -> new Log())
          .add(Long.parseLong(match));
    }
  }

  /** The event's scope key; for an event without one, from its session id, else the JVM. */
  private static String scopeOf(final ChaosEvent event) {
    final String scope = event.attributes().get("scope");
    if (scope != null) {
      return scope;
    }
    final String sessionId = event.attributes().get("sessionId");
    return sessionId == null ? JVM_SCOPE : SESSION_SCOPE_PREFIX + sessionId;
  }

  private static String scopeKey(final @Nullable String sessionId) {
    return sessionId == null ? JVM_SCOPE : SESSION_SCOPE_PREFIX + sessionId;
  }

  /**
   * Returns the match numbers on which the scenario took effect in any scope, ascending.
   *
   * @param scenarioId the scenario id
   * @return the match numbers; empty when it never took effect
   */
  public List<Long> matches(final String scenarioId) {
    final Map<String, Log> byScope = fired.get(scenarioId);
    if (byScope == null) {
      return List.of();
    }
    final java.util.TreeSet<Long> all = new java.util.TreeSet<>();
    byScope.values().forEach(log -> all.addAll(log.matches));
    return List.copyOf(all);
  }

  /**
   * Returns the match numbers on which the scenario took effect in one session, ascending: the log
   * to replay when several sessions ran a scenario with the same id.
   *
   * @param scenarioId the scenario id
   * @param sessionId the session id; {@code null} for a JVM-scoped scenario
   * @return the match numbers; empty when it never took effect there
   */
  public List<Long> matches(final String scenarioId, final @Nullable String sessionId) {
    final Log log = log(scenarioId, scopeKey(sessionId));
    return log == null ? List.of() : List.copyOf(log.matches);
  }

  private @Nullable Log log(final String scenarioId, final String scope) {
    final Map<String, Log> byScope = fired.get(scenarioId);
    return byScope == null ? null : byScope.get(scope);
  }

  /**
   * Returns every recorded scenario's match numbers, by scenario id.
   *
   * @return the decision log
   */
  public Map<String, List<Long>> decisions() {
    final Map<String, List<Long>> decisions = new TreeMap<>();
    fired.keySet().forEach(id -> decisions.put(id, matches(id)));
    return decisions;
  }

  /**
   * Returns the JVM-scoped {@code scenario} with its policy replaying the decisions recorded for it
   * in the JVM scope: probability 1.0, no rate limit, {@code replayMatches} set; every other policy
   * field is kept. The decisions of sessions are not included; see {@link #replay(ChaosScenario,
   * String)}. A stressor ({@link ChaosSelector.StressSelector}) or a clock skew ({@link
   * ChaosEffect.ClockSkewEffect}) decides nothing per call, so it is returned unchanged.
   *
   * @param scenario the scenario as it was recorded
   * @return the replaying scenario
   * @throws IllegalStateException if the log was truncated at {@value #MAX_DECISIONS} numbers
   */
  public ChaosScenario replay(final ChaosScenario scenario) {
    return replayFrom(scenario, JVM_SCOPE);
  }

  /**
   * Returns {@code scenario} replaying the decisions recorded for it in one session; a stressor or
   * a clock skew unchanged, as {@link #replay(ChaosScenario)}.
   *
   * @param scenario the scenario as it was recorded
   * @param sessionId the session it ran in
   * @return the replaying scenario
   * @throws IllegalStateException if the log was truncated at {@value #MAX_DECISIONS} numbers
   */
  public ChaosScenario replay(final ChaosScenario scenario, final String sessionId) {
    return replayFrom(scenario, scopeKey(Objects.requireNonNull(sessionId, "sessionId")));
  }

  private ChaosScenario replayFrom(final ChaosScenario scenario, final String scope) {
    if (scenario.selector() instanceof ChaosSelector.StressSelector
        || scenario.effect() instanceof ChaosEffect.ClockSkewEffect) {
      // No APPLIED events, nothing to replay; replayMatches would be refused on them.
      return scenario;
    }
    final Log log = log(scenario.id(), scope);
    final long dropped = log == null ? 0L : log.dropped.sum();
    if (dropped > 0L) {
      throw new IllegalStateException(
          "decision log truncated: scenario "
              + scenario.id()
              + " in "
              + scope
              + " took effect more than "
              + MAX_DECISIONS
              + " times ("
              + dropped
              + " not recorded); a replay would not reproduce the run");
    }
    final List<Long> matches = log == null ? List.of() : List.copyOf(log.matches);
    final ActivationPolicy recorded = scenario.activationPolicy();
    final ActivationPolicy replaying =
        new ActivationPolicy(
            recorded.startMode(),
            1.0d,
            recorded.activateAfterMatches(),
            recorded.maxApplications(),
            recorded.activeFor(),
            null,
            recorded.randomSeed(),
            recorded.allowDestructiveEffects(),
            recorded.activeFrom(),
            recorded.activeUntil(),
            matches);
    return new ChaosScenario(
        scenario.id(),
        scenario.description(),
        scenario.scope(),
        scenario.selector(),
        scenario.effect(),
        replaying,
        scenario.precedence(),
        scenario.tags());
  }

  /** Forgets every recorded decision. */
  public void clear() {
    fired.clear();
  }

  /** One scenario's decisions in one scope, bounded by {@link #MAX_DECISIONS}. */
  private static final class Log {
    private final ConcurrentSkipListSet<Long> matches = new ConcurrentSkipListSet<>();
    private final AtomicInteger stored = new AtomicInteger();
    private final LongAdder dropped = new LongAdder();

    void add(final long match) {
      // Reserve a slot first, so concurrent adds never store more than the cap.
      if (stored.incrementAndGet() > MAX_DECISIONS) {
        stored.decrementAndGet();
        dropped.increment();
      } else if (!matches.add(match)) {
        stored.decrementAndGet();
      }
    }
  }
}

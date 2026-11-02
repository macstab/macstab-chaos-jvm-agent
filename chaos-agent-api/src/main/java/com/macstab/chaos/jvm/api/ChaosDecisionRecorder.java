package com.macstab.chaos.jvm.api;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

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
 */
public final class ChaosDecisionRecorder implements ChaosEventListener {

  private final Map<String, ConcurrentSkipListSet<Long>> fired = new ConcurrentHashMap<>();

  /** Creates an empty recorder. */
  public ChaosDecisionRecorder() {}

  @Override
  public void onEvent(final ChaosEvent event) {
    if (event.type() != ChaosEvent.Type.APPLIED) {
      return;
    }
    final String match = event.attributes().get("match");
    if (match != null) {
      fired
          .computeIfAbsent(event.scenarioId(), id -> new ConcurrentSkipListSet<>())
          .add(Long.parseLong(match));
    }
  }

  /**
   * Returns the match numbers on which the scenario took effect, ascending.
   *
   * @param scenarioId the scenario id
   * @return the match numbers; empty when it never took effect
   */
  public List<Long> matches(final String scenarioId) {
    final ConcurrentSkipListSet<Long> matches = fired.get(scenarioId);
    return matches == null ? List.of() : List.copyOf(matches);
  }

  /**
   * Returns every recorded scenario's match numbers, by scenario id.
   *
   * @return the decision log
   */
  public Map<String, List<Long>> decisions() {
    final Map<String, List<Long>> decisions = new TreeMap<>();
    fired.forEach((id, matches) -> decisions.put(id, List.copyOf(matches)));
    return decisions;
  }

  /**
   * Returns {@code scenario} with its policy replaying the recorded decisions: probability 1.0, no
   * rate limit, {@code replayMatches} set; every other policy field is kept.
   *
   * @param scenario the scenario as it was recorded
   * @return the replaying scenario
   */
  public ChaosScenario replay(final ChaosScenario scenario) {
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
            matches(scenario.id()));
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
}

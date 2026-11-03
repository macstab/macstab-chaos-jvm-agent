package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDecisionRecorder;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Recording the decisions of a run and replaying them with {@code replayMatches}. */
@DisplayName("Decision replay")
class DecisionReplayTest {

  private static final int CALLS = 200;

  @Test
  @Tag("law-L9a")
  @DisplayName("replays an unseeded probabilistic run decision by decision")
  void replaysUnseededRun() {
    final ChaosScenario recorded =
        ChaosScenario.builder("flaky")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.reject("flaky executor"))
            .activationPolicy(ActivationPolicy.builder().probability(0.3).randomSeed(null).build())
            .build();

    final ChaosDecisionRecorder first = new ChaosDecisionRecorder();
    final boolean[] firstOutcome = run(recorded, first);
    final List<Long> decisions = first.matches("flaky");
    assertThat(decisions).isNotEmpty().hasSizeLessThan(CALLS);

    final ChaosDecisionRecorder second = new ChaosDecisionRecorder();
    final boolean[] replayOutcome = run(first.replay(recorded), second);

    assertThat(second.matches("flaky")).isEqualTo(decisions);
    assertThat(replayOutcome).isEqualTo(firstOutcome);
  }

  @Test
  @Tag("law-L9a")
  @DisplayName("replay counts warm-up matches, as recording did")
  void replayWithWarmUp() {
    final ChaosScenario scenario =
        ChaosScenario.builder("warm")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.reject("warm"))
            .activationPolicy(
                ActivationPolicy.builder()
                    .activateAfterMatches(5)
                    .replayMatches(List.of(6L, 9L))
                    .build())
            .build();
    final ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();

    run(scenario, recorder);

    assertThat(recorder.matches("warm")).as("matches 1-5 are the warm-up").containsExactly(6L, 9L);
  }

  @Test
  @DisplayName("rejects replay logs that would not replay: zero, probability, rate limit")
  void rejectsInvalidLogs() {
    assertThatThrownBy(() -> ActivationPolicy.builder().replayMatches(List.of(0L)).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ActivationPolicy.builder().probability(0.5).replayMatches(List.of(1L)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("probability");
    assertThatThrownBy(
            () ->
                ActivationPolicy.builder()
                    .rateLimit(1, Duration.ofSeconds(1))
                    .replayMatches(List.of(1L))
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            ActivationPolicy.builder().replayMatches(List.of(9L, 2L, 9L)).build().replayMatches())
        .containsExactly(2L, 9L);
  }

  private static boolean[] run(final ChaosScenario scenario, final ChaosDecisionRecorder recorder) {
    final boolean[] rejected = new boolean[CALLS];
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      runtime.addEventListener(recorder);
      runtime.activate(scenario);
      for (int i = 0; i < CALLS; i++) {
        try {
          runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", runtime, () -> {});
        } catch (final RuntimeException failed) {
          rejected[i] = true;
        }
      }
    }
    return rejected;
  }
}

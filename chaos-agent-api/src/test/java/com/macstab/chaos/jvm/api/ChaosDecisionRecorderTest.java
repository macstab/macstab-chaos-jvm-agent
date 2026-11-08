package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosDecisionRecorder and replayMatches")
class ChaosDecisionRecorderTest {

  private static ChaosEvent applied(final String scenario, final String match) {
    return new ChaosEvent(
        Instant.now(),
        ChaosEvent.Type.APPLIED,
        scenario,
        "applied",
        match == null ? Map.of() : Map.of("match", match));
  }

  @Test
  @DisplayName("records applied match numbers per scenario, sorted, ignoring other events")
  void records() {
    final ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();
    recorder.onEvent(applied("a", "7"));
    recorder.onEvent(applied("a", "2"));
    recorder.onEvent(applied("a", "7"));
    recorder.onEvent(applied("b", "1"));
    recorder.onEvent(applied("b", null));
    recorder.onEvent(
        new ChaosEvent(Instant.now(), ChaosEvent.Type.SKIPPED, "a", "skip", Map.of("match", "3")));

    assertThat(recorder.matches("a")).containsExactly(2L, 7L);
    assertThat(recorder.matches("none")).isEmpty();
    assertThat(recorder.decisions()).containsOnlyKeys("a", "b");

    recorder.clear();
    assertThat(recorder.decisions()).isEmpty();
  }

  @Test
  @DisplayName("keeps one log per scope and starts it again when the scenario starts again")
  void logsPerScope() {
    final ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();
    recorder.onEvent(event(ChaosEvent.Type.STARTED, Map.of("scope", "jvm")));
    recorder.onEvent(event(ChaosEvent.Type.APPLIED, Map.of("match", "2", "scope", "jvm")));
    recorder.onEvent(event(ChaosEvent.Type.APPLIED, Map.of("match", "1", "scope", "session:s1")));
    recorder.onEvent(event(ChaosEvent.Type.APPLIED, Map.of("match", "5", "sessionId", "s2")));
    final ChaosScenario scenario =
        ChaosScenario.builder("a")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.reject("x"))
            .build();

    assertThat(recorder.replay(scenario).activationPolicy().replayMatches()).containsExactly(2L);
    assertThat(recorder.replay(scenario, "s1").activationPolicy().replayMatches())
        .containsExactly(1L);
    assertThat(recorder.matches("a", "s2")).as("scope taken from sessionId").containsExactly(5L);
    assertThat(recorder.matches("a")).containsExactly(1L, 2L, 5L);

    recorder.onEvent(event(ChaosEvent.Type.STARTED, Map.of("scope", "session:s1")));

    assertThat(recorder.matches("a", "s1")).isEmpty();
    assertThat(recorder.matches("a", null)).containsExactly(2L);
    assertThat(recorder.replay(scenario, "none").activationPolicy().replayMatches()).isEmpty();
  }

  private static ChaosEvent event(
      final ChaosEvent.Type type, final Map<String, String> attributes) {
    return new ChaosEvent(Instant.EPOCH, type, "a", type.name(), attributes);
  }

  @Test
  @DisplayName("beyond 1,000,000 decisions of one scenario the log is truncated and replay refuses")
  void capped() {
    // Heap: the stored 1,000,000 numbers take about 60-80 MB in the skip-list set; past the cap
    // nothing more is stored, only a counter grows, so the test's heap need stays at that level.
    final ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();
    for (long match = 1L; match <= 1_000_001L; match++) {
      recorder.onEvent(
          new ChaosEvent(
              Instant.EPOCH,
              ChaosEvent.Type.APPLIED,
              "busy",
              "applied",
              Map.of("match", Long.toString(match), "scope", "jvm")));
    }
    final ChaosScenario scenario =
        ChaosScenario.builder("busy")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.reject("x"))
            .build();

    assertThat(recorder.matches("busy")).hasSize(1_000_000);
    assertThatThrownBy(() -> recorder.replay(scenario))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("decision log truncated");
  }

  @Test
  @DisplayName("replay keeps the scenario and every policy field but the draw")
  void replay() {
    final ChaosScenario scenario =
        ChaosScenario.builder("a")
            .description("d")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.reject("x"))
            .activationPolicy(
                ActivationPolicy.builder()
                    .probability(0.2)
                    .maxApplications(5L)
                    .rateLimit(3, java.time.Duration.ofSeconds(1))
                    .build())
            .precedence(4)
            .tag("k", "v")
            .build();
    final ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();
    recorder.onEvent(applied("a", "4"));

    final ChaosScenario replaying = recorder.replay(scenario);

    assertThat(replaying.activationPolicy().replayMatches()).containsExactly(4L);
    assertThat(replaying.activationPolicy().probability()).isEqualTo(1.0);
    assertThat(replaying.activationPolicy().rateLimit()).isNull();
    assertThat(replaying.activationPolicy().maxApplications()).isEqualTo(5L);
    assertThat(replaying)
        .usingRecursiveComparison()
        .ignoringFields("activationPolicy")
        .isEqualTo(scenario);
  }

  @Test
  @DisplayName("replayMatches must be positive and needs probability 1 without a rate limit")
  void validation() {
    assertThatThrownBy(() -> ActivationPolicy.builder().replayMatches(List.of(-1L)).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ActivationPolicy.builder().replayMatches(null).build().replayMatches()).isNull();
  }

  @Test
  @DisplayName("process selectors and errno codes")
  void processAndErrno() {
    final ChaosSelector.ProcessSelector any =
        new ChaosSelector.ProcessSelector(Set.of(OperationType.PROCESS_START), null);
    assertThat(any.commandPattern()).isEqualTo(NamePattern.any());
    assertThat(ChaosSelector.process(NamePattern.exact("git")).operations())
        .containsExactly(OperationType.PROCESS_START);
    assertThatThrownBy(
            () -> new ChaosSelector.ProcessSelector(Set.of(OperationType.SOCKET_READ), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ChaosEffect.Errno.EAGAIN.linuxCode()).isEqualTo(11);
    assertThat(ChaosEffect.Errno.EAGAIN.family()).isEqualTo(ChaosEffect.Errno.Family.PROCESS);
    assertThat(ChaosEffect.Errno.ENOENT.linuxCode()).isEqualTo(2);
  }
}

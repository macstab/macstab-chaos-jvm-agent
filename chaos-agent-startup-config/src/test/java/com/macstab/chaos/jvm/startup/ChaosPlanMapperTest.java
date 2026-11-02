package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosPlanMapper")
class ChaosPlanMapperTest {

  @Nested
  @DisplayName("round-trip serialisation")
  class RoundTrip {

    @Test
    @DisplayName("write then read preserves plan identity")
    void writeReadRoundTrip() {
      ChaosPlan original = samplePlan("round-trip", 100);
      String json = ChaosPlanMapper.write(original);
      ChaosPlan restored = ChaosPlanMapper.read(json);

      assertThat(restored.metadata().name()).isEqualTo("round-trip");
      assertThat(restored.scenarios()).hasSize(1);
      assertThat(restored.scenarios().get(0).id()).isEqualTo("delay-scenario");
    }

    @Test
    @DisplayName("delay effect duration survives round-trip")
    void delayDurationPreserved() {
      ChaosPlan original = samplePlan("duration-check", 42);
      String json = ChaosPlanMapper.write(original);
      ChaosPlan restored = ChaosPlanMapper.read(json);

      ChaosEffect effect = restored.scenarios().get(0).effect();
      assertThat(effect).isInstanceOf(ChaosEffect.DelayEffect.class);
      assertThat(((ChaosEffect.DelayEffect) effect).minDelay()).isEqualTo(Duration.ofMillis(42));
    }

    @Test
    @DisplayName("network and file-I/O filters survive a round trip and parse from JSON")
    void ioFiltersPreserved() {
      final ChaosSelector network =
          new ChaosSelector.NetworkSelector(
              Set.of(OperationType.SOCKET_WRITE), null, "10.0.0.0/8", 5432, 1024L, 65536L);
      final ChaosSelector file =
          new ChaosSelector.FileIoSelector(
              Set.of(OperationType.FILE_IO_WRITE),
              com.macstab.chaos.jvm.api.PathPattern.under("/var/lib/app"),
              null,
              4096L);
      final ChaosPlan original =
          new ChaosPlan(
              new ChaosPlan.Metadata("io", ""),
              null,
              List.of(
                  ChaosScenario.builder("net")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(network)
                      .effect(ChaosEffect.delay(Duration.ofMillis(5)))
                      .build(),
                  ChaosScenario.builder("file")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(file)
                      .effect(ChaosEffect.delay(Duration.ofMillis(5)))
                      .build()));

      final ChaosPlan restored = ChaosPlanMapper.read(ChaosPlanMapper.write(original));

      assertThat(restored.scenarios().get(0).selector()).isEqualTo(network);
      assertThat(restored.scenarios().get(1).selector()).isEqualTo(file);

      final String json =
          """
          {"metadata":{"name":"p"},"scenarios":[{"id":"s","scope":"JVM",
           "selector":{"type":"fileIo","operations":["FILE_IO_WRITE"],
                       "pathPattern":{"prefix":"/data"},"maxBytes":10},
           "effect":{"type":"delay","minDelay":"PT0.005S","maxDelay":"PT0.005S"}}]}
          """;
      assertThat(ChaosPlanMapper.read(json).scenarios().get(0).selector())
          .isEqualTo(
              new ChaosSelector.FileIoSelector(
                  Set.of(OperationType.FILE_IO_WRITE),
                  com.macstab.chaos.jvm.api.PathPattern.under("/data"),
                  null,
                  10L));
    }

    @Test
    @DisplayName("activeFrom and activeUntil survive a round trip and parse from ISO-8601")
    void wallClockWindowPreserved() {
      final ActivationPolicy policy =
          ActivationPolicy.window(
              Instant.parse("2026-11-01T08:00:00Z"), Instant.parse("2026-11-01T09:30:00Z"));
      final ChaosPlan original =
          new ChaosPlan(
              new ChaosPlan.Metadata("window", ""),
              null,
              List.of(
                  ChaosScenario.builder("windowed")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                      .effect(ChaosEffect.delay(Duration.ofMillis(5)))
                      .activationPolicy(policy)
                      .build()));

      final ChaosPlan restored = ChaosPlanMapper.read(ChaosPlanMapper.write(original));

      assertThat(restored.scenarios().get(0).activationPolicy()).isEqualTo(policy);

      final String json =
          """
          {"metadata":{"name":"p"},"scenarios":[{"id":"s","scope":"JVM",
           "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
           "effect":{"type":"delay","minDelay":"PT0.005S","maxDelay":"PT0.005S"},
           "activationPolicy":{"activeFrom":"2026-11-01T08:00:00Z",
                               "activeUntil":"2026-11-01T09:30:00Z"}}]}
          """;
      final ActivationPolicy parsed =
          ChaosPlanMapper.read(json).scenarios().get(0).activationPolicy();
      assertThat(parsed.activeFrom()).isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
      assertThat(parsed.activeUntil()).isEqualTo(Instant.parse("2026-11-01T09:30:00Z"));
    }
  }

  @Nested
  @DisplayName("strict parsing")
  class StrictParsing {

    @Test
    @DisplayName("malformed JSON throws ConfigLoadException")
    void malformedJson() {
      assertThatThrownBy(() -> ChaosPlanMapper.read("{not valid json"))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("failed to parse");
    }

    @Test
    @DisplayName("trailing garbage is rejected")
    void trailingGarbage() {
      String validJson = ChaosPlanMapper.write(samplePlan("clean", 10));
      String poisoned = validJson + "GARBAGE";
      assertThatThrownBy(() -> ChaosPlanMapper.read(poisoned))
          .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("oversized JSON is rejected before parsing")
    void oversizedJson() {
      String huge =
          "{\"metadata\":{\"name\":\"" + "x".repeat(ChaosPlanMapper.MAX_JSON_LENGTH) + "\"}}";
      assertThatThrownBy(() -> ChaosPlanMapper.read(huge))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("exceeds maximum size");
    }

    @Test
    @DisplayName("empty scenarios list is rejected by plan contract")
    void emptyScenariosRejected() {
      String json =
          """
          {
            "metadata": { "name": "empty", "description": "" },
            "scenarios": []
          }
          """;
      assertThatThrownBy(() -> ChaosPlanMapper.read(json)).isInstanceOf(ConfigLoadException.class);
    }
  }

  @Nested
  @DisplayName("allowDestructiveEffects JSON deserialization")
  class AllowDestructiveEffectsDeserialization {

    @Test
    @DisplayName("allowDestructiveEffects=true in JSON sets flag on ActivationPolicy")
    void allowDestructiveEffectsTrueDeserializes() {
      String json =
          """
          {
            "metadata": { "name": "destructive-plan", "description": "" },
            "scenarios": [
              {
                "id": "deadlock-test",
                "scope": "JVM",
                "selector": { "type": "stress", "target": "DEADLOCK" },
                "effect": {
                  "type": "deadlock",
                  "participantCount": 2,
                  "acquisitionDelay": "PT0.1S"
                },
                "activationPolicy": {
                  "startMode": "AUTOMATIC",
                  "probability": 1.0,
                  "allowDestructiveEffects": true
                }
              }
            ]
          }
          """;
      ChaosPlan plan = ChaosPlanMapper.read(json);
      assertThat(plan.scenarios()).hasSize(1);
      assertThat(plan.scenarios().get(0).activationPolicy().allowDestructiveEffects()).isTrue();
    }

    @Test
    @DisplayName("allowDestructiveEffects absent in JSON defaults to false")
    void allowDestructiveEffectsAbsentDefaultsFalse() {
      String json =
          """
          {
            "metadata": { "name": "normal-plan", "description": "" },
            "scenarios": [
              {
                "id": "delay-test",
                "scope": "JVM",
                "selector": {
                  "type": "executor",
                  "operations": ["EXECUTOR_SUBMIT"]
                },
                "effect": { "type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.1S" }
              }
            ]
          }
          """;
      ChaosPlan plan = ChaosPlanMapper.read(json);
      assertThat(plan.scenarios().get(0).activationPolicy().allowDestructiveEffects()).isFalse();
    }

    @Test
    @DisplayName("allowDestructiveEffects=false in JSON sets flag to false")
    void allowDestructiveEffectsFalseDeserializes() {
      String json =
          """
          {
            "metadata": { "name": "safe-plan", "description": "" },
            "scenarios": [
              {
                "id": "delay-test",
                "scope": "JVM",
                "selector": {
                  "type": "executor",
                  "operations": ["EXECUTOR_SUBMIT"]
                },
                "effect": { "type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.1S" },
                "activationPolicy": { "allowDestructiveEffects": false }
              }
            ]
          }
          """;
      ChaosPlan plan = ChaosPlanMapper.read(json);
      assertThat(plan.scenarios().get(0).activationPolicy().allowDestructiveEffects()).isFalse();
    }
  }

  private static ChaosPlan samplePlan(String name, long delayMillis) {
    return new ChaosPlan(
        new ChaosPlan.Metadata(name, "test plan"),
        null,
        List.of(
            ChaosScenario.builder("delay-scenario")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(delayMillis)))
                .activationPolicy(ActivationPolicy.always())
                .build()));
  }

  @Nested
  @DisplayName("parse failure messages")
  class ParseFailureMessages {

    @Test
    @DisplayName("name the position, the kind of failure and the JSON path")
    void reportPositionKindAndPath() {
      final String json =
          """
          {
            "metadata": {"name": "p"},
            "scenarios": [
              {
                "id": "a",
                "scope": "JVM",
                "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
                "effect": {"type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.1S"},
                "precedencee": 1
              }
            ]
          }
          """;

      assertThatThrownBy(() -> ChaosPlanMapper.read(json))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("at line 10")
          .hasMessageContaining("unknown property 'precedencee'")
          .hasMessageContaining("scenarios[0]");
    }

    @Test
    @DisplayName("report the agent's validation message for a rejected value")
    void reportValidationMessage() {
      final String json =
          """
          {"metadata": {"name": "p"}, "scenarios": [{"id": "a", "scope": "JVM",
            "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
            "effect": {"type": "delay", "minDelay": "PT0.1S"}}]}
          """;

      assertThatThrownBy(() -> ChaosPlanMapper.read(json))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("invalid value: delay bounds must be non-null")
          .hasMessageContaining("scenarios[0].effect");
    }

    @Test
    @DisplayName("never echo values from the input")
    void doNotEchoValues() {
      final String json =
          """
          {"metadata": {"name": "p"}, "scenarios": [{"id": "a", "scope": "JVM",
            "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
            "effect": {"type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.1S"},
            "precedence": "s3cr3t-token"}]}
          """;

      assertThatThrownBy(() -> ChaosPlanMapper.read(json))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("precedence")
          .hasMessageNotContaining("s3cr3t-token");
    }
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName("reads and writes replayMatches")
  void replayMatchesRoundTrip() {
    final String json =
        """
        {"metadata":{"name":"replay"},"scenarios":[{"id":"r","scope":"JVM",
         "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
         "effect":{"type":"reject","message":"x"},
         "activationPolicy":{"replayMatches":[17,3,9]}}]}
        """;
    final com.macstab.chaos.jvm.api.ChaosPlan plan = ChaosPlanMapper.read(json);
    assertThat(plan.scenarios().get(0).activationPolicy().replayMatches())
        .containsExactly(3L, 9L, 17L);
    assertThat(ChaosPlanMapper.read(ChaosPlanMapper.write(plan))).isEqualTo(plan);
  }
}

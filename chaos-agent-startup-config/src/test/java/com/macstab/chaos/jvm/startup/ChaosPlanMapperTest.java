package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.type.TypeFactory;
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
    @DisplayName("relay the validation message without control characters and capped")
    void sanitiseValidationMessage() {
      final String scenario =
          """
          {"id": "%s", "scope": "JVM",
            "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
            "effect": {"type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.1S"}}
          """;
      final String escaped = "a\\u001b[2Jforged";
      final String escapedPlan =
          "{\"scenarios\": ["
              + scenario.formatted(escaped)
              + ","
              + scenario.formatted(escaped)
              + "]}";
      final String longId = "x".repeat(500);
      final String longPlan =
          "{\"scenarios\": ["
              + scenario.formatted(longId)
              + ","
              + scenario.formatted(longId)
              + "]}";

      assertThatThrownBy(() -> ChaosPlanMapper.read(escapedPlan))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("invalid value: duplicate scenario id in plan")
          .hasMessageNotContaining("\u001b");
      assertThatThrownBy(() -> ChaosPlanMapper.read(longPlan))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("invalid value: duplicate scenario id in plan")
          .hasMessageNotContaining(longId);
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

    @Test
    @DisplayName("call a syntax error malformed JSON, also inside a value being bound")
    void reportMalformedJson() {
      assertThatThrownBy(() -> ChaosPlanMapper.read("{not valid}"))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining(": malformed JSON");
      assertThatThrownBy(() -> ChaosPlanMapper.read("{\"metadata\": {\"name\": tru}}"))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining(": malformed JSON at metadata");
    }

    @Test
    @DisplayName("name an unclassified failure by its exception type, without the value")
    void reportUnclassifiedFailureByType() {
      final String json =
          """
          {"metadata": {"name": "p"}, "scenarios": [{"id": "a", "scope": "JVM",
            "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
            "effect": {"type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.1S"},
            "precedence": 99999999999999999}]}
          """;

      assertThatThrownBy(() -> ChaosPlanMapper.read(json))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining(": JsonMappingException at scenarios[0].precedence")
          .hasMessageNotContaining("99999999999999999");
    }
  }

  @Test
  @DisplayName("toJson writes compact JSON that reads back as the same plan")
  void toJsonIsCompact() {
    final ChaosPlan plan =
        new ChaosPlan(
            new ChaosPlan.Metadata("compact", ""),
            null,
            List.of(
                ChaosScenario.builder("s1")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(10)))
                    .activationPolicy(ActivationPolicy.always())
                    .build()));

    final String json = ChaosPlanMapper.toJson(plan);

    assertThat(json).doesNotContain("\n").startsWith("{");
    assertThat(ChaosPlanMapper.read(json)).isEqualTo(plan);
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

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "readPlanOrScenarios recognises plans, single scenarios and arrays")
  void readPlanOrScenarios() {
    final String scenario =
        """
        {"id": "mentions-scenarios", "description": "has a \\"scenarios\\" word", "scope": "JVM",
         "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
         "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
        """;
    assertThat(ChaosPlanMapper.readPlanOrScenarios(scenario, "w").scenarios())
        .singleElement()
        .satisfies(s -> assertThat(s.id()).isEqualTo("mentions-scenarios"));
    assertThat(ChaosPlanMapper.readPlanOrScenarios("[" + scenario + "]", "w").metadata().name())
        .isEqualTo("w");
    assertThat(
            ChaosPlanMapper.readPlanOrScenarios(
                    "{\"metadata\":{\"name\":\"p\"},\"scenarios\":[" + scenario + "]}", "w")
                .metadata()
                .name())
        .isEqualTo("p");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ChaosPlanMapper.readPlanOrScenarios("{ broken", "w"))
        .isInstanceOf(ConfigLoadException.class);
  }

  @Nested
  @DisplayName("failure descriptions without a parser position")
  class UnpositionedFailures {

    @Test
    @DisplayName("a failure without a location is described without a position")
    void noLocation() {
      final UnrecognizedPropertyException unknown =
          new UnrecognizedPropertyException(null, "unknown", null, ChaosPlan.class, "delayy", null);

      assertThat(ChaosPlanMapper.parseFailureMessage(unknown))
          .isEqualTo("failed to parse chaos plan JSON: unknown property 'delayy'");
      assertThat(ChaosPlanMapper.parseFailureReason(unknown))
          .isEqualTo("unknown property 'delayy'");
    }

    @Test
    @DisplayName("a location without a line number is described without a position")
    void unknownLine() {
      final JsonMappingException mapping =
          new JsonMappingException(null, "failed", com.fasterxml.jackson.core.JsonLocation.NA);

      assertThat(ChaosPlanMapper.parseFailureMessage(mapping))
          .isEqualTo("failed to parse chaos plan JSON: JsonMappingException");
    }

    @Test
    @DisplayName("an instantiation failure without a cause message is an invalid value")
    void instantiationWithoutDetail() {
      final com.fasterxml.jackson.databind.JavaType plan =
          TypeFactory.defaultInstance().constructType(ChaosPlan.class);

      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  ValueInstantiationException.from(null, "failed", plan)))
          .isEqualTo("invalid value");
      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  ValueInstantiationException.from(
                      null, "failed", plan, new NullPointerException())))
          .isEqualTo("invalid value");
    }

    @Test
    @DisplayName("a path step that is neither a field nor an index adds nothing to the path")
    void pathStepWithoutFieldOrIndex() {
      final JsonMappingException mapping = new JsonMappingException(null, "failed");
      mapping.prependPath(new JsonMappingException.Reference(null));

      assertThat(ChaosPlanMapper.parseFailureReason(mapping)).isEqualTo("JsonMappingException");
    }
  }

  @Test
  @DisplayName("toJson names the type it cannot serialise")
  void toJsonFailureNamesType() {
    assertThatThrownBy(() -> ChaosPlanMapper.toJson(new Object()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("failed to serialize java.lang.Object")
        .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
  }

  @Test
  @DisplayName("a load failure without a separate reason reports its message as the reason")
  void reasonDefaultsToMessage() {
    final ConfigLoadException failure =
        new ConfigLoadException("broken", "inline-json", null, 0, 0, null);

    assertThat(failure.reason()).isEqualTo("broken");
    assertThat(failure.line()).isZero();
    assertThat(failure.column()).isZero();
  }

  @Test
  @DisplayName("the JSON literal null is not a plan")
  void nullLiteralIsRejected() {
    assertThatThrownBy(() -> ChaosPlanMapper.read("null"))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("chaos plan JSON is null");
    assertThatThrownBy(() -> ChaosPlanMapper.read("  null  "))
        .isInstanceOf(ConfigLoadException.class);
  }
}

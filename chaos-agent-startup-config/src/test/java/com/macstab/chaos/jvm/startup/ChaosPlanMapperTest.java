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

  @Test
  @DisplayName("publishes its nullness: the package is @NullMarked")
  void nullMarked() {
    assertThat(
            ChaosPlanMapper.class
                .getPackage()
                .isAnnotationPresent(org.jspecify.annotations.NullMarked.class))
        .isTrue();
  }

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

    @Test
    @DisplayName("reads back the ANY name patterns it writes")
    void anyPatternRoundTrip() {
      final ChaosPlan original =
          new ChaosPlan(
              new ChaosPlan.Metadata("any", ""),
              null,
              List.of(
                  ChaosScenario.builder("any-thread")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          new ChaosSelector.ThreadSelector(
                              Set.of(OperationType.THREAD_START),
                              ChaosSelector.ThreadKind.ANY,
                              com.macstab.chaos.jvm.api.NamePattern.any(),
                              null))
                      .effect(ChaosEffect.delay(Duration.ofMillis(5)))
                      .build()));

      final String json = ChaosPlanMapper.write(original);

      assertThat(json).contains("\"ANY\"");
      assertThat(ChaosPlanMapper.read(json)).isEqualTo(original);
    }

    @Test
    @DisplayName("writes durations and instants in ISO-8601 and leaves out absent values")
    void isoWithoutNulls() {
      final String json = ChaosPlanMapper.write(samplePlan("iso", 100));

      assertThat(json).contains("\"PT0.1S\"").doesNotContain("null");
    }

    @Test
    @DisplayName("serialises a diagnostics snapshot's capturedAt in ISO-8601")
    void isoSnapshot() {
      final String json =
          ChaosPlanMapper.toJson(
              new com.macstab.chaos.jvm.api.ChaosDiagnostics.Snapshot(
                  Instant.parse("2026-11-08T10:15:30Z"), List.of(), List.of(), java.util.Map.of()));

      assertThat(json).contains("\"capturedAt\":\"2026-11-08T10:15:30Z\"");
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

    /** One scenario with {@code effect} and {@code policy} (a JSON object's members). */
    private static String scenario(final String effect, final String policy) {
      return """
          {"metadata":{"name":"p"},"scenarios":[{"id":"s","scope":"JVM",
           "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
           "effect":{"type":"delay",%s},
           "activationPolicy":{%s}}]}
          """
          .formatted(effect, policy);
    }

    private static final String DELAY = "\"minDelay\":\"PT0.005S\",\"maxDelay\":\"PT0.005S\"";

    @Test
    @DisplayName("refuses a number for a duration, also one that overflows")
    void numericDurations() {
      assertThatThrownBy(
              () -> ChaosPlanMapper.read(scenario("\"minDelay\":1,\"maxDelay\":\"PT1S\"", "")))
          .isInstanceOf(ConfigLoadException.class);
      assertThatThrownBy(
              () -> ChaosPlanMapper.read(scenario("\"minDelay\":\"PT0S\",\"maxDelay\":1e100", "")))
          .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("refuses a number for an instant")
    void numericInstant() {
      assertThatThrownBy(() -> ChaosPlanMapper.read(scenario(DELAY, "\"activeFrom\":1700000000")))
          .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("refuses a fraction for an integer and a string for a number")
    void noNumberCoercion() {
      assertThatThrownBy(() -> ChaosPlanMapper.read(scenario(DELAY, "\"maxApplications\":1.5")))
          .isInstanceOf(ConfigLoadException.class);
      assertThatThrownBy(() -> ChaosPlanMapper.read(scenario(DELAY, "\"maxApplications\":\"5\"")))
          .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("refuses a number for a boolean and for an enum")
    void noScalarCoercion() {
      assertThatThrownBy(
              () -> ChaosPlanMapper.read(scenario(DELAY, "\"allowDestructiveEffects\":1")))
          .isInstanceOf(ConfigLoadException.class);
      assertThatThrownBy(() -> ChaosPlanMapper.read(scenario(DELAY, "").replace("\"JVM\"", "0")))
          .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("refuses a name pattern with a mode and no value instead of matching everything")
    void patternWithoutValue() {
      final String json =
          """
          {"metadata":{"name":"p"},"scenarios":[{"id":"s","scope":"JVM",
           "selector":{"type":"thread","operations":["THREAD_START"],
                       "threadNamePattern":{"mode":"GLOB"}},
           "effect":{"type":"delay","minDelay":"PT0.005S","maxDelay":"PT0.005S"}}]}
          """;

      assertThatThrownBy(() -> ChaosPlanMapper.read(json)).isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("refuses a duplicate property instead of keeping the last value")
    void duplicateProperty() {
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      scenario(DELAY, "").replace("\"id\":\"s\",", "\"id\":\"s\",\"id\":\"t\",")))
          .isInstanceOf(ConfigLoadException.class);
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

    /** A plan of one scenario with these members; {@code extra} adds members, comma-led. */
    private static String plan(
        final String scope, final String selector, final String effect, final String extra) {
      return """
          {"metadata": {"name": "p"}, "scenarios": [{"id": "a", "scope": %s,
            "selector": %s,
            "effect": %s%s}]}
          """
          .formatted(scope, selector, effect, extra);
    }

    private static final String EXECUTOR =
        "{\"type\": \"executor\", \"operations\": [\"EXECUTOR_SUBMIT\"]}";
    private static final String DELAY =
        "{\"type\": \"delay\", \"minDelay\": \"PT0.1S\", \"maxDelay\": \"PT0.1S\"}";

    @Test
    @DisplayName("name the expected constants for an unknown enum value, without the value")
    void unknownEnumValue() {
      assertThatThrownBy(() -> ChaosPlanMapper.read(plan("\"jvm\"", EXECUTOR, DELAY, "")))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("unknown value; expected one of JVM, SESSION at scenarios[0].scope")
          .hasMessageNotContaining("jvm\"");
    }

    @Test
    @DisplayName("call a number too large for its integer field out of range, without the value")
    void numberOutOfRange() {
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      plan("\"JVM\"", EXECUTOR, DELAY, ", \"precedence\": 99999999999")))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("number out of range at scenarios[0].precedence")
          .hasMessageNotContaining("99999999999");
    }

    @Test
    @DisplayName("call a null inside a value and a missing value missing or null")
    void missingOrNull() {
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      plan(
                          "\"JVM\"",
                          "{\"type\": \"executor\", \"operations\": [null]}",
                          DELAY,
                          "")))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("missing or null value")
          .hasMessageContaining("scenarios[0].selector");
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      """
                      {"metadata": {"name": "p"}, "scenarios": [{"id": "a", "scope": "JVM",
                        "effect": {"type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.1S"}}]}
                      """))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("missing or null value: selector at scenarios[0]");
    }

    @Test
    @DisplayName("call a malformed duration or instant invalid, without the value")
    void invalidDurationAndInstant() {
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      plan(
                          "\"JVM\"",
                          EXECUTOR,
                          "{\"type\": \"delay\", \"minDelay\": \"5 seconds\", \"maxDelay\": \"PT1S\"}",
                          "")))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("invalid duration at scenarios[0].effect.minDelay")
          .hasMessageNotContaining("5 seconds");
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      plan(
                          "\"JVM\"",
                          EXECUTOR,
                          DELAY,
                          ", \"activationPolicy\": {\"activeFrom\": 1}")))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("invalid instant at scenarios[0].activationPolicy.activeFrom");
    }

    @Test
    @DisplayName("name a property given twice")
    void duplicateProperty() {
      assertThatThrownBy(
              () -> ChaosPlanMapper.read(plan("\"JVM\", \"id\": \"b\"", EXECUTOR, DELAY, "")))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("duplicate property at scenarios[0].id");
    }

    @Test
    @DisplayName("report an unknown property before the missing value it caused")
    void unknownPropertyFirst() {
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      plan(
                          "\"JVM\"",
                          EXECUTOR,
                          "{\"type\": \"delay\", \"minDelay\": \"PT0.1S\", \"maxDelayy\": \"PT1S\"}",
                          "")))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("unknown property 'maxDelayy' at scenarios[0].effect");
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
    @DisplayName(
        "an instantiation failure without a cause message is an invalid or a missing value")
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
          .isEqualTo("missing or null value");
    }

    @Test
    @DisplayName("classify failures by kind without a parser")
    void classifyWithoutParser() {
      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  new com.fasterxml.jackson.core.exc.InputCoercionException(
                      null,
                      "out of range",
                      com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT,
                      Integer.TYPE)))
          .isEqualTo("number out of range");
      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  com.fasterxml.jackson.databind.exc.InvalidNullException.from(
                      null,
                      com.fasterxml.jackson.databind.PropertyName.construct("operations"),
                      TypeFactory.defaultInstance().constructType(Set.class))))
          .isEqualTo("missing or null value");
      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  new com.fasterxml.jackson.core.JsonParseException(null, (String) null)))
          .isEqualTo("malformed JSON");
      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  com.fasterxml.jackson.databind.exc.MismatchedInputException.from(
                      null, ChaosScenario.ScenarioScope.class, "not a string")))
          .isEqualTo("value of the wrong type");
      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  com.fasterxml.jackson.databind.exc.MismatchedInputException.from(
                      null, (Class<?>) null, "no target")))
          .isEqualTo("value of the wrong type");
    }

    @Test
    @DisplayName("fall back to the constructor's message when the tree does not hold the object")
    void instantiationOutsideTheTree() throws Exception {
      final com.fasterxml.jackson.databind.JsonNode tree =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"scenarios\": [1]}");
      final com.fasterxml.jackson.databind.JavaType scenario =
          TypeFactory.defaultInstance().constructType(ChaosScenario.class);
      final ValueInstantiationException missingStep =
          ValueInstantiationException.from(
              null, "failed", scenario, new IllegalArgumentException("bad"));
      missingStep.prependPath(new JsonMappingException.Reference(null, "x"));
      missingStep.prependPath(new JsonMappingException.Reference(null, "metadata"));
      final ValueInstantiationException notAnObject =
          ValueInstantiationException.from(
              null, "failed", scenario, new IllegalArgumentException("bad"));
      notAnObject.prependPath(new JsonMappingException.Reference(null, 0));
      notAnObject.prependPath(new JsonMappingException.Reference(null, "scenarios"));
      final ValueInstantiationException untyped =
          ValueInstantiationException.from(
              null, "failed", null, new IllegalArgumentException("bad"));

      assertThat(ChaosPlanMapper.parseFailureReason(missingStep, tree))
          .isEqualTo("invalid value: bad at metadata.x");
      assertThat(ChaosPlanMapper.parseFailureReason(notAnObject, tree))
          .isEqualTo("invalid value: bad at scenarios[0]");
      assertThat(ChaosPlanMapper.parseFailureReason(untyped, tree)).isEqualTo("invalid value: bad");
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

  @Nested
  @DisplayName("documented defaults")
  class DocumentedDefaults {

    private ChaosEffect effect(final String json) {
      return ChaosPlanMapper.read(
              "{\"id\": \"defaults\", \"selector\": {\"type\": \"executor\", \"operations\":"
                  + " [\"EXECUTOR_SUBMIT\"]}, \"effect\": "
                  + json
                  + "}")
          .scenarios()
          .get(0)
          .effect();
    }

    @Test
    @DisplayName("an injected exception has a stack trace unless withStackTrace says otherwise")
    void withStackTrace() {
      assertThat(
              effect(
                  "{\"type\": \"exceptionInjection\", \"exceptionClassName\":"
                      + " \"java.lang.IllegalStateException\", \"message\": \"boom\"}"))
          .isEqualTo(
              new ChaosEffect.ExceptionInjectionEffect(
                  "java.lang.IllegalStateException", "boom", true));
    }

    @Test
    @DisplayName("an injected exception outside java. and javax. is refused while reading")
    void injectedExceptionOutsideAllowlist() {
      assertThatThrownBy(
              () ->
                  effect(
                      "{\"type\": \"exceptionInjection\", \"exceptionClassName\":"
                          + " \"jakarta.ws.rs.NotFoundException\", \"message\": \"boom\"}"))
          .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("metaspace pressure retains its classes by default")
    void retain() {
      assertThat(effect("{\"type\": \"metaspacePressure\", \"generatedClassCount\": 10}"))
          .isEqualTo(new ChaosEffect.MetaspacePressureEffect(10, 0, true));
    }

    @Test
    @DisplayName("GC pressure allocates 1 KiB objects by default")
    void objectSizeBytes() {
      assertThat(
              effect(
                  "{\"type\": \"gcPressure\", \"allocationRateBytesPerSecond\": 1000,"
                      + " \"duration\": \"PT1S\"}"))
          .isEqualTo(
              new ChaosEffect.GcPressureEffect(
                  1000L,
                  ChaosEffect.GcPressureEffect.DEFAULT_OBJECT_SIZE_BYTES,
                  false,
                  Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a deadlock waits one second between its lock acquisitions by default")
    void acquisitionDelay() {
      assertThat(effect("{\"type\": \"deadlock\", \"participantCount\": 2}"))
          .isEqualTo(new ChaosEffect.DeadlockEffect(2, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a missing required count keeps its message")
    void missingRequiredCount() {
      assertThatThrownBy(() -> effect("{\"type\": \"deadlock\"}"))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("participantCount must be >= 2");
    }

    @Test
    @DisplayName("a partial observability block keeps the other switches at their defaults")
    void partialObservability() {
      final ChaosPlan plan =
          ChaosPlanMapper.read(
              "{\"observability\": {\"debugDumpOnStart\": true}, \"scenarios\": [{\"id\": \"o\","
                  + " \"selector\": {\"type\": \"executor\", \"operations\":"
                  + " [\"EXECUTOR_SUBMIT\"]}, \"effect\": {\"type\": \"suppress\"}}]}");

      assertThat(plan.observability()).isEqualTo(new ChaosPlan.Observability(true, true, true));
    }

    @Test
    @DisplayName("a policy without start mode and probability starts automatically, always firing")
    void policyDefaults() {
      final ActivationPolicy policy =
          ChaosPlanMapper.read(
                  "{\"id\": \"p\", \"selector\": {\"type\": \"executor\", \"operations\":"
                      + " [\"EXECUTOR_SUBMIT\"]}, \"effect\": {\"type\": \"suppress\"},"
                      + " \"activationPolicy\": {}}")
              .scenarios()
              .get(0)
              .activationPolicy();

      assertThat(policy.startMode()).isEqualTo(ActivationPolicy.StartMode.AUTOMATIC);
      assertThat(policy.probability()).isEqualTo(1.0d);
    }
  }

  @Nested
  @DisplayName("accepted shapes")
  class AcceptedShapes {

    private static final String SCENARIO =
        """
        {"id": "shape", "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
         "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}""";

    @Test
    @DisplayName("a single scenario is a plan named default")
    void singleScenario() {
      final ChaosPlan plan = ChaosPlanMapper.read(SCENARIO);

      assertThat(plan.metadata().name()).isEqualTo("default");
      assertThat(plan.scenarios()).extracting(ChaosScenario::id).containsExactly("shape");
    }

    @Test
    @DisplayName("an array of scenarios is a plan named default")
    void arrayOfScenarios() {
      final ChaosPlan plan =
          ChaosPlanMapper.read("[" + SCENARIO + ", " + SCENARIO.replace("shape", "other") + "]");

      assertThat(plan.metadata().name()).isEqualTo("default");
      assertThat(plan.scenarios()).extracting(ChaosScenario::id).containsExactly("shape", "other");
    }

    @Test
    @DisplayName("an empty array is no plan")
    void emptyArray() {
      assertThatThrownBy(() -> ChaosPlanMapper.read("[]"))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("scenarios must not be empty");
    }

    @Test
    @DisplayName("an object with plan members only is a plan without scenarios")
    void planMembersOnly() {
      assertThatThrownBy(() -> ChaosPlanMapper.read("{\"metadata\": {\"name\": \"p\"}}"))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("scenarios must not be empty");
    }

    @Test
    @DisplayName("an empty object is read as a scenario and names what it lacks")
    void emptyObject() {
      assertThatThrownBy(() -> ChaosPlanMapper.read("{}"))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageNotContaining("scenarios must not be empty");
    }

    @Test
    @DisplayName("a byte order mark before the JSON is skipped")
    void byteOrderMark() {
      assertThat(ChaosPlanMapper.read("﻿{\"scenarios\": [" + SCENARIO + "]}").scenarios())
          .hasSize(1);
      assertThat(ChaosPlanMapper.read("﻿" + SCENARIO).scenarios()).hasSize(1);
    }

    @Test
    @DisplayName("a failure in a bare scenario is reported at its position in the input")
    void positionInTheInput() {
      final String annotated =
          """
          {"id": "positioned",
           "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
           "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelayy": "PT0.001S"}}
          """;

      assertThatThrownBy(() -> ChaosPlanMapper.readPlanOrScenarios(annotated, "@WithChaos"))
          .isInstanceOfSatisfying(
              ConfigLoadException.class,
              failure -> {
                assertThat(failure.line()).isEqualTo(3);
                assertThat(failure.getMessage()).contains("at line 3,").contains("at effect");
              });
    }

    @Test
    @DisplayName("a syntax error names the member it is in, also inside an array")
    void syntaxErrorPath() {
      assertThatThrownBy(() -> ChaosPlanMapper.read("[" + SCENARIO + ", {\"id\": tru}]"))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining(": malformed JSON at [1].id");
    }

    @Test
    @DisplayName("empty input is no plan")
    void emptyInput() {
      assertThatThrownBy(() -> ChaosPlanMapper.read("")).isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("a failure that is neither a syntax nor a mapping error has no path")
    void failureWithoutPath() {
      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  new com.fasterxml.jackson.core.JsonParseException(null, "broken")))
          .isEqualTo("malformed JSON");
      assertThat(
              ChaosPlanMapper.parseFailureReason(
                  new com.fasterxml.jackson.core.JsonProcessingException("broken") {}))
          .doesNotContain(" at ");
    }

    @Test
    @DisplayName("bare scenarios are named after the caller's plan name")
    void callersPlanName() {
      assertThat(ChaosPlanMapper.readPlanOrScenarios(SCENARIO, "@WithChaos").metadata().name())
          .isEqualTo("@WithChaos");
    }
  }

  @Test
  @DisplayName("a written plan lists a selector's operations in declaration order")
  void writtenOperationsKeepDeclarationOrder() {
    // EnumSet iterates in declaration order.
    final List<OperationType> declared =
        List.copyOf(
            java.util.EnumSet.of(
                OperationType.FILE_IO_READ,
                OperationType.FILE_IO_WRITE,
                OperationType.FILE_IO_DELETE,
                OperationType.FILE_IO_SYNC,
                OperationType.FILE_IO_TRUNCATE,
                OperationType.FILE_IO_MAP,
                OperationType.FILE_IO_OPEN,
                OperationType.FILE_IO_CLOSE));
    final List<OperationType> reversed = new java.util.ArrayList<>(declared);
    java.util.Collections.reverse(reversed);
    final ChaosPlan plan =
        new ChaosPlan(
            new ChaosPlan.Metadata("ordered", ""),
            null,
            List.of(
                ChaosScenario.builder("files")
                    .selector(ChaosSelector.fileIo(new java.util.LinkedHashSet<>(reversed)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .build()));

    final String json = ChaosPlanMapper.write(plan);

    final java.util.regex.Matcher operations =
        java.util.regex.Pattern.compile("\"operations\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
    assertThat(operations.find()).as(json).isTrue();
    assertThat(
            java.util.Arrays.stream(operations.group(1).split(","))
                .map(name -> name.replaceAll("[\\s\"]", ""))
                .toList())
        .containsExactlyElementsOf(declared.stream().map(OperationType::name).toList());
  }
}

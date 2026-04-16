package com.macstab.chaos.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
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
}

package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosPlan")
class ChaosPlanTest {

  private static ChaosScenario scenario(final String id) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.reject("x"))
        .build();
  }

  @Test
  @DisplayName("missing metadata and observability fall back to the defaults")
  void defaults() {
    final ChaosPlan plan = new ChaosPlan(null, null, List.of(scenario("a")));

    assertThat(plan.metadata()).isEqualTo(new ChaosPlan.Metadata("default", ""));
    assertThat(plan.observability()).isEqualTo(new ChaosPlan.Observability(true, true, false));
  }

  @Test
  @DisplayName("given metadata and observability are kept")
  void keepsGivenParts() {
    final ChaosPlan.Metadata metadata = new ChaosPlan.Metadata("checkout", "peak load");
    final ChaosPlan.Observability observability = new ChaosPlan.Observability(false, false, true);

    final ChaosPlan plan = new ChaosPlan(metadata, observability, List.of(scenario("a")));

    assertThat(plan.metadata()).isSameAs(metadata);
    assertThat(plan.observability()).isSameAs(observability);
  }

  @Test
  @DisplayName("a plan needs at least one scenario")
  void needsScenarios() {
    assertThatThrownBy(() -> new ChaosPlan(null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("scenarios must not be empty");
    assertThatThrownBy(() -> new ChaosPlan(null, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("scenarios must not be empty");
  }

  @Test
  @DisplayName("a null scenario entry is rejected with a message naming the problem")
  void rejectsNullEntries() {
    final List<ChaosScenario> scenarios = Arrays.asList(scenario("a"), null);

    assertThatThrownBy(() -> new ChaosPlan(null, null, scenarios))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("scenarios must not contain null entries");
  }

  @Test
  @DisplayName("two scenarios with the same id are rejected, naming the id")
  void rejectsDuplicateIds() {
    final List<ChaosScenario> scenarios = List.of(scenario("a"), scenario("b"), scenario("a"));

    assertThatThrownBy(() -> new ChaosPlan(null, null, scenarios))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate scenario id in plan: 'a'");
  }

  @Test
  @DisplayName("the scenarios are an immutable copy in their given order")
  void copiesScenarios() {
    final List<ChaosScenario> scenarios = new ArrayList<>(List.of(scenario("b"), scenario("a")));

    final ChaosPlan plan = new ChaosPlan(null, null, scenarios);
    scenarios.clear();

    assertThat(plan.scenarios()).extracting(ChaosScenario::id).containsExactly("b", "a");
    assertThatThrownBy(() -> plan.scenarios().add(scenario("c")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Nested
  @DisplayName("Metadata")
  class MetadataTests {

    @Test
    @DisplayName("a plan name must be non-blank")
    void nameMustBeNonBlank() {
      assertThatThrownBy(() -> new ChaosPlan.Metadata(null, "d"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("plan name must be non-blank");
      assertThatThrownBy(() -> new ChaosPlan.Metadata("  ", "d"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("plan name must be non-blank");
    }

    @Test
    @DisplayName("a missing description becomes empty, a given one is kept")
    void description() {
      assertThat(new ChaosPlan.Metadata("p", null).description()).isEmpty();
      assertThat(new ChaosPlan.Metadata("p", "why").description()).isEqualTo("why");
    }
  }
}

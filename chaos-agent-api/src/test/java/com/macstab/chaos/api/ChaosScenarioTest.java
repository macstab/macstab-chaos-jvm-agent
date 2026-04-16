package com.macstab.chaos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosScenario")
class ChaosScenarioTest {

  private static final ChaosSelector EXECUTOR_SELECTOR =
      ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT));

  private static final ChaosEffect DELAY_EFFECT = ChaosEffect.delay(Duration.ofMillis(10));

  @Nested
  @DisplayName("id validation")
  class IdValidation {

    @Test
    @DisplayName("null id throws")
    void nullIdThrows() {
      assertThatThrownBy(
              () ->
                  ChaosScenario.builder(null)
                      .selector(EXECUTOR_SELECTOR)
                      .effect(DELAY_EFFECT)
                      .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank id throws")
    void blankIdThrows() {
      assertThatThrownBy(
              () ->
                  ChaosScenario.builder("   ")
                      .selector(EXECUTOR_SELECTOR)
                      .effect(DELAY_EFFECT)
                      .build())
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty id throws")
    void emptyIdThrows() {
      assertThatThrownBy(
              () ->
                  ChaosScenario.builder("")
                      .selector(EXECUTOR_SELECTOR)
                      .effect(DELAY_EFFECT)
                      .build())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("selector validation")
  class SelectorValidation {

    @Test
    @DisplayName("null selector throws")
    void nullSelectorThrows() {
      assertThatThrownBy(() -> ChaosScenario.builder("test").effect(DELAY_EFFECT).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("effect validation")
  class EffectValidation {

    @Test
    @DisplayName("null effect throws")
    void nullEffectThrows() {
      assertThatThrownBy(() -> ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("null scope defaults to JVM")
  class NullScopeDefault {

    @Test
    @DisplayName("constructor null scope defaults to JVM")
    void nullScopeDefaultsToJvm() {
      ChaosScenario scenario =
          new ChaosScenario("test", null, null, EXECUTOR_SELECTOR, DELAY_EFFECT, null, 0, null);
      assertThat(scenario.scope()).isEqualTo(ChaosScenario.ScenarioScope.JVM);
    }

    @Test
    @DisplayName("builder default scope is JVM")
    void builderDefaultScopeIsJvm() {
      ChaosScenario scenario =
          ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
      assertThat(scenario.scope()).isEqualTo(ChaosScenario.ScenarioScope.JVM);
    }
  }

  @Nested
  @DisplayName("null activationPolicy defaults to always()")
  class NullActivationPolicyDefault {

    @Test
    @DisplayName("constructor null policy defaults to always()")
    void nullActivationPolicyDefaultsToAlways() {
      ChaosScenario scenario =
          new ChaosScenario("test", null, null, EXECUTOR_SELECTOR, DELAY_EFFECT, null, 0, null);
      assertThat(scenario.activationPolicy()).isNotNull();
      assertThat(scenario.activationPolicy().probability()).isEqualTo(1.0d);
      assertThat(scenario.activationPolicy().startMode())
          .isEqualTo(ActivationPolicy.StartMode.AUTOMATIC);
    }

    @Test
    @DisplayName("builder default activation policy is always()")
    void builderDefaultActivationPolicyIsAlways() {
      ChaosScenario scenario =
          ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
      assertThat(scenario.activationPolicy().probability()).isEqualTo(1.0d);
    }
  }

  @Nested
  @DisplayName("builder sets all fields correctly")
  class BuilderFields {

    @Test
    @DisplayName("id")
    void builderSetsIdCorrectly() {
      ChaosScenario scenario =
          ChaosScenario.builder("my-scenario")
              .selector(EXECUTOR_SELECTOR)
              .effect(DELAY_EFFECT)
              .build();
      assertThat(scenario.id()).isEqualTo("my-scenario");
    }

    @Test
    @DisplayName("description")
    void builderSetsDescriptionCorrectly() {
      ChaosScenario scenario =
          ChaosScenario.builder("test")
              .description("Simulates slow executor")
              .selector(EXECUTOR_SELECTOR)
              .effect(DELAY_EFFECT)
              .build();
      assertThat(scenario.description()).isEqualTo("Simulates slow executor");
    }

    @Test
    @DisplayName("scope")
    void builderSetsScopeCorrectly() {
      ChaosScenario scenario =
          ChaosScenario.builder("test")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(DELAY_EFFECT)
              .build();
      assertThat(scenario.scope()).isEqualTo(ChaosScenario.ScenarioScope.SESSION);
    }

    @Test
    @DisplayName("selector")
    void builderSetsSelectorCorrectly() {
      ChaosScenario scenario =
          ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
      assertThat(scenario.selector()).isEqualTo(EXECUTOR_SELECTOR);
    }

    @Test
    @DisplayName("effect")
    void builderSetsEffectCorrectly() {
      ChaosScenario scenario =
          ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
      assertThat(scenario.effect()).isEqualTo(DELAY_EFFECT);
    }

    @Test
    @DisplayName("activationPolicy")
    void builderSetsActivationPolicyCorrectly() {
      ActivationPolicy policy = ActivationPolicy.manual();
      ChaosScenario scenario =
          ChaosScenario.builder("test")
              .selector(EXECUTOR_SELECTOR)
              .effect(DELAY_EFFECT)
              .activationPolicy(policy)
              .build();
      assertThat(scenario.activationPolicy()).isEqualTo(policy);
    }

    @Test
    @DisplayName("precedence")
    void builderSetsPrecedenceCorrectly() {
      ChaosScenario scenario =
          ChaosScenario.builder("test")
              .selector(EXECUTOR_SELECTOR)
              .effect(DELAY_EFFECT)
              .precedence(42)
              .build();
      assertThat(scenario.precedence()).isEqualTo(42);
    }

    @Test
    @DisplayName("default precedence is zero")
    void builderDefaultPrecedenceIsZero() {
      ChaosScenario scenario =
          ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
      assertThat(scenario.precedence()).isZero();
    }

    @Test
    @DisplayName("null description normalised to empty string")
    void nullDescriptionNormalisedToEmpty() {
      ChaosScenario scenario =
          new ChaosScenario("test", null, null, EXECUTOR_SELECTOR, DELAY_EFFECT, null, 0, null);
      assertThat(scenario.description()).isEqualTo("");
    }
  }

  @Nested
  @DisplayName("tag()")
  class TagMethod {

    @Test
    @DisplayName("adds key-value to tags")
    void tagAddsKeyValueToTags() {
      ChaosScenario scenario =
          ChaosScenario.builder("test")
              .selector(EXECUTOR_SELECTOR)
              .effect(DELAY_EFFECT)
              .tag("env", "test")
              .tag("team", "platform")
              .build();
      assertThat(scenario.tags()).containsEntry("env", "test").containsEntry("team", "platform");
    }

    @Test
    @DisplayName("multiple tags accumulate")
    void multipleTagsAccumulate() {
      ChaosScenario scenario =
          ChaosScenario.builder("test")
              .selector(EXECUTOR_SELECTOR)
              .effect(DELAY_EFFECT)
              .tag("a", "1")
              .tag("b", "2")
              .tag("c", "3")
              .build();
      assertThat(scenario.tags()).hasSize(3);
    }

    @Test
    @DisplayName("no tags produces empty map")
    void noTagsProducesEmptyMap() {
      ChaosScenario scenario =
          ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
      assertThat(scenario.tags()).isEmpty();
    }
  }
}

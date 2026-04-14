package io.macstab.chaos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChaosScenarioTest {

  private static final ChaosSelector EXECUTOR_SELECTOR =
      ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT));

  private static final ChaosEffect DELAY_EFFECT = ChaosEffect.delay(Duration.ofMillis(10));

  // ── id validation ────────────────────────────────────────────────────────

  @Test
  void nullIdThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ChaosScenario.builder(null)
                .selector(EXECUTOR_SELECTOR)
                .effect(DELAY_EFFECT)
                .build());
  }

  @Test
  void blankIdThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ChaosScenario.builder("   ")
                .selector(EXECUTOR_SELECTOR)
                .effect(DELAY_EFFECT)
                .build());
  }

  @Test
  void emptyIdThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ChaosScenario.builder("")
                .selector(EXECUTOR_SELECTOR)
                .effect(DELAY_EFFECT)
                .build());
  }

  // ── selector validation ───────────────────────────────────────────────────

  @Test
  void nullSelectorThrows() {
    assertThrows(
        NullPointerException.class,
        () -> ChaosScenario.builder("test").effect(DELAY_EFFECT).build());
  }

  // ── effect validation ─────────────────────────────────────────────────────

  @Test
  void nullEffectThrows() {
    assertThrows(
        NullPointerException.class,
        () -> ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).build());
  }

  // ── null scope defaults to JVM ────────────────────────────────────────────

  @Test
  void nullScopeDefaultsToJvm() {
    ChaosScenario scenario =
        new ChaosScenario(
            "test", null, null, EXECUTOR_SELECTOR, DELAY_EFFECT, null, 0, null);
    assertEquals(ChaosScenario.ScenarioScope.JVM, scenario.scope());
  }

  @Test
  void builderDefaultScopeIsJvm() {
    ChaosScenario scenario =
        ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
    assertEquals(ChaosScenario.ScenarioScope.JVM, scenario.scope());
  }

  // ── null activationPolicy defaults to always() ────────────────────────────

  @Test
  void nullActivationPolicyDefaultsToAlways() {
    ChaosScenario scenario =
        new ChaosScenario(
            "test", null, null, EXECUTOR_SELECTOR, DELAY_EFFECT, null, 0, null);
    assertNotNull(scenario.activationPolicy());
    assertEquals(1.0d, scenario.activationPolicy().probability(), 0.0001);
    assertEquals(
        ActivationPolicy.StartMode.AUTOMATIC, scenario.activationPolicy().startMode());
  }

  @Test
  void builderDefaultActivationPolicyIsAlways() {
    ChaosScenario scenario =
        ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
    assertEquals(1.0d, scenario.activationPolicy().probability(), 0.0001);
  }

  // ── builder sets all fields correctly ────────────────────────────────────

  @Test
  void builderSetsIdCorrectly() {
    ChaosScenario scenario =
        ChaosScenario.builder("my-scenario")
            .selector(EXECUTOR_SELECTOR)
            .effect(DELAY_EFFECT)
            .build();
    assertEquals("my-scenario", scenario.id());
  }

  @Test
  void builderSetsDescriptionCorrectly() {
    ChaosScenario scenario =
        ChaosScenario.builder("test")
            .description("Simulates slow executor")
            .selector(EXECUTOR_SELECTOR)
            .effect(DELAY_EFFECT)
            .build();
    assertEquals("Simulates slow executor", scenario.description());
  }

  @Test
  void builderSetsScopeCorrectly() {
    ChaosScenario scenario =
        ChaosScenario.builder("test")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(DELAY_EFFECT)
            .build();
    assertEquals(ChaosScenario.ScenarioScope.SESSION, scenario.scope());
  }

  @Test
  void builderSetsSelectorCorrectly() {
    ChaosScenario scenario =
        ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
    assertEquals(EXECUTOR_SELECTOR, scenario.selector());
  }

  @Test
  void builderSetsEffectCorrectly() {
    ChaosScenario scenario =
        ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
    assertEquals(DELAY_EFFECT, scenario.effect());
  }

  @Test
  void builderSetsActivationPolicyCorrectly() {
    ActivationPolicy policy = ActivationPolicy.manual();
    ChaosScenario scenario =
        ChaosScenario.builder("test")
            .selector(EXECUTOR_SELECTOR)
            .effect(DELAY_EFFECT)
            .activationPolicy(policy)
            .build();
    assertEquals(policy, scenario.activationPolicy());
  }

  @Test
  void builderSetsPrecedenceCorrectly() {
    ChaosScenario scenario =
        ChaosScenario.builder("test")
            .selector(EXECUTOR_SELECTOR)
            .effect(DELAY_EFFECT)
            .precedence(42)
            .build();
    assertEquals(42, scenario.precedence());
  }

  @Test
  void builderDefaultPrecedenceIsZero() {
    ChaosScenario scenario =
        ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
    assertEquals(0, scenario.precedence());
  }

  // ── tag() adds to tags ────────────────────────────────────────────────────

  @Test
  void tagAddsKeyValueToTags() {
    ChaosScenario scenario =
        ChaosScenario.builder("test")
            .selector(EXECUTOR_SELECTOR)
            .effect(DELAY_EFFECT)
            .tag("env", "test")
            .tag("team", "platform")
            .build();
    assertEquals("test", scenario.tags().get("env"));
    assertEquals("platform", scenario.tags().get("team"));
  }

  @Test
  void multipleTagsAccumulate() {
    ChaosScenario scenario =
        ChaosScenario.builder("test")
            .selector(EXECUTOR_SELECTOR)
            .effect(DELAY_EFFECT)
            .tag("a", "1")
            .tag("b", "2")
            .tag("c", "3")
            .build();
    assertEquals(3, scenario.tags().size());
  }

  @Test
  void noTagsProducesEmptyMap() {
    ChaosScenario scenario =
        ChaosScenario.builder("test").selector(EXECUTOR_SELECTOR).effect(DELAY_EFFECT).build();
    assertTrue(scenario.tags().isEmpty());
  }

  @Test
  void nullDescriptionNormalisedToEmpty() {
    ChaosScenario scenario =
        new ChaosScenario("test", null, null, EXECUTOR_SELECTOR, DELAY_EFFECT, null, 0, null);
    assertEquals("", scenario.description());
  }
}

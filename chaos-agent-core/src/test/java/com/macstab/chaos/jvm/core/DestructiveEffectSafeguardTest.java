package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Full coverage for the destructive-effect safeguard introduced in {@link
 * ActivationPolicy#allowDestructiveEffects()}.
 *
 * <p>{@link ChaosEffect.DeadlockEffect} and {@link ChaosEffect.ThreadLeakEffect} create
 * non-recoverable JVM state. The safeguard requires explicit opt-in via {@link
 * ActivationPolicy#withDestructiveEffects()} or {@code allowDestructiveEffects=true} in the JSON
 * plan. Validation fires at registration time in {@link CompatibilityValidator#validate}, not at
 * effect-application time.
 *
 * <p>Test dimensions covered:
 *
 * <ul>
 *   <li>ActivationPolicy factory semantics — {@code always()}, {@code manual()}, {@code
 *       withDestructiveEffects()} flag values
 *   <li>Rejection without flag — both destructive effects, correct exception type and message
 *   <li>Acceptance with flag — both destructive effects pass validation
 *   <li>Non-destructive stressors — not blocked by the guard
 *   <li>End-to-end via {@link ChaosRuntime#activate} — guard fires through the full stack
 * </ul>
 */
@DisplayName("Destructive effect safeguard")
class DestructiveEffectSafeguardTest {

  private static final FeatureSet FEATURE_SET = new FeatureSet();

  // ── ActivationPolicy factory semantics ────────────────────────────────────

  @Nested
  @DisplayName("ActivationPolicy factory methods")
  class FactoryMethodTests {

    @Test
    @DisplayName("always() sets allowDestructiveEffects=false")
    void alwaysHasDestructiveFlagFalse() {
      assertThat(ActivationPolicy.always().allowDestructiveEffects()).isFalse();
    }

    @Test
    @DisplayName("manual() sets allowDestructiveEffects=false")
    void manualHasDestructiveFlagFalse() {
      assertThat(ActivationPolicy.manual().allowDestructiveEffects()).isFalse();
    }

    @Test
    @DisplayName("withDestructiveEffects() sets allowDestructiveEffects=true")
    void withDestructiveEffectsHasFlagTrue() {
      assertThat(ActivationPolicy.withDestructiveEffects().allowDestructiveEffects()).isTrue();
    }

    @Test
    @DisplayName("withDestructiveEffects() startMode is AUTOMATIC")
    void withDestructiveEffectsIsAutomatic() {
      assertThat(ActivationPolicy.withDestructiveEffects().startMode())
          .isEqualTo(ActivationPolicy.StartMode.AUTOMATIC);
    }

    @Test
    @DisplayName("withDestructiveEffects() probability is 1.0")
    void withDestructiveEffectsProbabilityIsOne() {
      assertThat(ActivationPolicy.withDestructiveEffects().probability()).isEqualTo(1.0d);
    }
  }

  // ── DeadlockEffect guard ──────────────────────────────────────────────────

  @Nested
  @DisplayName("DeadlockEffect")
  class DeadlockEffectTests {

    @Test
    @DisplayName("throws ChaosActivationException without allowDestructiveEffects flag")
    void deadlockWithoutFlagThrows() {
      final ChaosScenario scenario = deadlockScenario(ActivationPolicy.always());
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, FEATURE_SET))
          .isInstanceOf(ChaosActivationException.class);
    }

    @Test
    @DisplayName("error message mentions non-recoverable state")
    void deadlockErrorMessageMentionsNonRecoverable() {
      final ChaosScenario scenario = deadlockScenario(ActivationPolicy.always());
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, FEATURE_SET))
          .hasMessageContaining("non-recoverable");
    }

    @Test
    @DisplayName("error message tells the user how to opt in")
    void deadlockErrorMessageMentionsOptIn() {
      final ChaosScenario scenario = deadlockScenario(ActivationPolicy.always());
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, FEATURE_SET))
          .hasMessageContaining("withDestructiveEffects");
    }

    @Test
    @DisplayName("passes validation when allowDestructiveEffects=true")
    void deadlockWithFlagPassesValidation() {
      final ChaosScenario scenario = deadlockScenario(ActivationPolicy.withDestructiveEffects());
      assertThatCode(() -> CompatibilityValidator.validate(scenario, FEATURE_SET))
          .doesNotThrowAnyException();
    }
  }

  // ── ThreadLeakEffect guard ────────────────────────────────────────────────

  @Nested
  @DisplayName("ThreadLeakEffect")
  class ThreadLeakEffectTests {

    @Test
    @DisplayName("throws ChaosActivationException without allowDestructiveEffects flag")
    void threadLeakWithoutFlagThrows() {
      final ChaosScenario scenario = threadLeakScenario(ActivationPolicy.always());
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, FEATURE_SET))
          .isInstanceOf(ChaosActivationException.class);
    }

    @Test
    @DisplayName("error message mentions non-recoverable state")
    void threadLeakErrorMessageMentionsNonRecoverable() {
      final ChaosScenario scenario = threadLeakScenario(ActivationPolicy.always());
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, FEATURE_SET))
          .hasMessageContaining("non-recoverable");
    }

    @Test
    @DisplayName("error message tells the user how to opt in")
    void threadLeakErrorMessageMentionsOptIn() {
      final ChaosScenario scenario = threadLeakScenario(ActivationPolicy.always());
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, FEATURE_SET))
          .hasMessageContaining("withDestructiveEffects");
    }

    @Test
    @DisplayName("passes validation when allowDestructiveEffects=true")
    void threadLeakWithFlagPassesValidation() {
      final ChaosScenario scenario = threadLeakScenario(ActivationPolicy.withDestructiveEffects());
      assertThatCode(() -> CompatibilityValidator.validate(scenario, FEATURE_SET))
          .doesNotThrowAnyException();
    }
  }

  // ── Non-destructive stressors not blocked ─────────────────────────────────

  @Nested
  @DisplayName("Non-destructive stressors are not blocked")
  class NonDestructiveStressorTests {

    @Test
    @DisplayName("HeapPressureEffect passes without flag")
    void heapPressureNotBlocked() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.HEAP,
                          new ChaosEffect.HeapPressureEffect(1024L, 512),
                          ActivationPolicy.always()),
                      FEATURE_SET))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("KeepAliveEffect passes without flag")
    void keepAliveNotBlocked() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.KEEPALIVE,
                          new ChaosEffect.KeepAliveEffect("t", true, Duration.ofSeconds(1)),
                          ActivationPolicy.always()),
                      FEATURE_SET))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GcPressureEffect passes without flag")
    void gcPressureNotBlocked() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.GC_PRESSURE,
                          new ChaosEffect.GcPressureEffect(
                              1024L * 1024L, 1024, false, Duration.ofSeconds(10)),
                          ActivationPolicy.always()),
                      FEATURE_SET))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MonitorContentionEffect passes without flag")
    void monitorContentionNotBlocked() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.MONITOR_CONTENTION,
                          new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 2, false),
                          ActivationPolicy.always()),
                      FEATURE_SET))
          .doesNotThrowAnyException();
    }
  }

  // ── End-to-end via ChaosRuntime.activate() ────────────────────────────────

  @Nested
  @DisplayName("End-to-end via ChaosRuntime.activate()")
  class EndToEndTests {

    @Test
    @DisplayName("activating DeadlockEffect without flag throws at registration time")
    void chaosRuntimeRejectsDeadlockWithoutFlag() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatThrownBy(() -> runtime.activate(deadlockScenario(ActivationPolicy.always())))
          .isInstanceOf(ChaosActivationException.class)
          .hasMessageContaining("non-recoverable");
    }

    @Test
    @DisplayName("activating ThreadLeakEffect without flag throws at registration time")
    void chaosRuntimeRejectsThreadLeakWithoutFlag() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThatThrownBy(() -> runtime.activate(threadLeakScenario(ActivationPolicy.always())))
          .isInstanceOf(ChaosActivationException.class)
          .hasMessageContaining("non-recoverable");
    }

    @Test
    @DisplayName("failed activation leaves no scenario registered in the runtime")
    void failedActivationLeavesNoScenarioRegistered() {
      final ChaosRuntime runtime = new ChaosRuntime();
      try {
        runtime.activate(deadlockScenario(ActivationPolicy.always()));
      } catch (ChaosActivationException ignored) {
        // expected
      }
      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
    }

    @Test
    @DisplayName("activating DeadlockEffect with flag succeeds and registers scenario")
    void chaosRuntimeAcceptsDeadlockWithFlag() {
      final ChaosRuntime runtime = new ChaosRuntime();
      // Must close the handle immediately — we do NOT want to actually deadlock the JVM.
      // Activation succeeds; we stop the scenario before the deadlock threads can acquire locks.
      final var handle =
          runtime.activate(deadlockScenario(ActivationPolicy.withDestructiveEffects()));
      handle.stop(); // stops stressor threads before deadlock manifests
      assertThat(runtime.diagnostics().snapshot().scenarios()).hasSize(1);
    }

    @Test
    @DisplayName("allowDestructiveEffects=false on valid non-destructive scenario has no effect")
    void nonDestructiveScenarioUnaffectedByFlag() {
      final ChaosRuntime runtime = new ChaosRuntime();
      // heap pressure is safe — should activate without any flag
      final ChaosScenario scenario =
          ChaosScenario.builder("heap-ok")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
              .effect(new ChaosEffect.HeapPressureEffect(1024L, 16))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatCode(() -> runtime.activate(scenario).close()).doesNotThrowAnyException();
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static ChaosScenario deadlockScenario(final ActivationPolicy policy) {
    return ChaosScenario.builder("deadlock-test")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DEADLOCK))
        .effect(new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(100)))
        .activationPolicy(policy)
        .build();
  }

  private static ChaosScenario threadLeakScenario(final ActivationPolicy policy) {
    return ChaosScenario.builder("thread-leak-test")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.stress(ChaosSelector.StressTarget.THREAD_LEAK))
        .effect(new ChaosEffect.ThreadLeakEffect(1, "test-leak-", true, null))
        .activationPolicy(policy)
        .build();
  }

  private static ChaosScenario stressScenario(
      final ChaosSelector.StressTarget target,
      final ChaosEffect effect,
      final ActivationPolicy policy) {
    return ChaosScenario.builder("stress-test")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.stress(target))
        .effect(effect)
        .activationPolicy(policy)
        .build();
  }
}

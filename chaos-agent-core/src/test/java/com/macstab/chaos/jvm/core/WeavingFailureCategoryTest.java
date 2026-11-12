package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosInstrumentationException;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The diagnostics category a weaving failure is recorded with (L21). */
@DisplayName("The category of a weaving failure")
class WeavingFailureCategoryTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void close() {
    runtime.close();
  }

  private static ChaosScenario submitDelay(final String id) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private ChaosDiagnostics.ActivationFailure onlyFailure() {
    final List<ChaosDiagnostics.ActivationFailure> failures =
        runtime.diagnostics().snapshot().failures();
    assertThat(failures).hasSize(1);
    return failures.get(0);
  }

  @Test
  @DisplayName(
      "a plan whose hooks the runtime cannot support is UNSUPPORTED_RUNTIME, under the plan")
  void unsupportedPlanWeaving() {
    runtime.setHookDemand(
        operations -> {
          throw new ChaosUnsupportedFeatureException("no retransformation here");
        });

    assertThatThrownBy(
            () ->
                runtime.activate(
                    new ChaosPlan(
                        new ChaosPlan.Metadata("unwoven", ""), null, List.of(submitDelay("a")))))
        .isInstanceOf(ChaosUnsupportedFeatureException.class);

    final ChaosDiagnostics.ActivationFailure failure = onlyFailure();
    assertThat(failure.scenarioId()).isEqualTo("plan:unwoven");
    assertThat(failure.category()).isEqualTo(ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME);
  }

  @Test
  @DisplayName("a scenario whose hooks fail to weave is INSTRUMENTATION_FAILURE, not invalid")
  void failingScenarioWeaving() {
    runtime.setHookDemand(
        operations -> {
          throw new IllegalStateException("retransformation failed");
        });

    assertThatThrownBy(() -> runtime.activate(submitDelay("b")))
        .isInstanceOf(ChaosInstrumentationException.class)
        .hasMessageContaining("retransformation failed");

    final ChaosDiagnostics.ActivationFailure failure = onlyFailure();
    assertThat(failure.scenarioId()).isEqualTo("b");
    assertThat(failure.category())
        .isEqualTo(ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE);
  }
}

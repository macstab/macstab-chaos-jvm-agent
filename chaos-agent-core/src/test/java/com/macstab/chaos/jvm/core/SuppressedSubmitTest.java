package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SUPPRESS on EXECUTOR_SUBMIT")
class SuppressedSubmitTest {

  @Test
  @DisplayName("a suppressed FutureTask is cancelled instead of left pending forever")
  void suppressedFutureIsCancelled() {
    final ChaosRuntime runtime = new ChaosRuntime();
    try {
      runtime.activate(
          ChaosScenario.builder("suppress-submit")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.suppress())
              .activationPolicy(ActivationPolicy.always())
              .build());
      final FutureTask<String> submitted = new FutureTask<>(() -> "ran");

      // What the executor runs in place of the submitted task.
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, submitted).run();

      assertThat(submitted.isDone()).isTrue();
      assertThatThrownBy(() -> submitted.get(1, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
    } finally {
      runtime.close();
    }
  }
}

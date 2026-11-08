package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SUPPRESS on EXECUTOR_SUBMIT")
class SuppressedSubmitTest {

  @Test
  @DisplayName("a suppressed plain Runnable is replaced by a no-op: the task is dropped")
  void suppressedRunnableIsDropped() {
    final ChaosRuntime runtime = suppressing();
    try {
      final AtomicBoolean ran = new AtomicBoolean();

      final Runnable queued =
          runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> ran.set(true));
      queued.run();

      assertThat(ran).as("the submitted task never runs").isFalse();
    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName("a suppressed Callable is replaced by a no-op that returns null")
  void suppressedCallableReturnsNull() throws Exception {
    final ChaosRuntime runtime = suppressing();
    try {
      final AtomicBoolean ran = new AtomicBoolean();
      final Callable<String> task =
          () -> {
            ran.set(true);
            return "ran";
          };

      // What a virtual-thread-per-task executor builds its future around: it completes with null.
      final Callable<String> queued =
          runtime.decorateExecutorCallable("EXECUTOR_SUBMIT", this, task);

      assertThat(queued.call()).isNull();
      assertThat(ran).as("the submitted task never runs").isFalse();
    } finally {
      runtime.close();
    }
  }

  private static ChaosRuntime suppressing() {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("suppress-submit")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.suppress())
            .activationPolicy(ActivationPolicy.always())
            .build());
    return runtime;
  }

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
      final Runnable queued = runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, submitted);

      assertThat(queued).as("the pool keeps the task it was given").isSameAs(submitted);
      assertThat(submitted.isCancelled()).isTrue();
      queued.run();
      assertThat(submitted.isDone()).isTrue();
      assertThatThrownBy(() -> submitted.get(1, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
    } finally {
      runtime.close();
    }
  }
}

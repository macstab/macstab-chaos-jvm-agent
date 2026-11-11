package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosBridge")
class ChaosBridgeTest {

  private ChaosRuntime runtime;
  private ChaosBridge bridge;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
    bridge = new ChaosBridge(runtime);
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Nested
  @DisplayName("executor runnable decoration")
  class ExecutorRunnableDecoration {

    @Test
    @DisplayName("returns original task unchanged when no scenarios are active")
    void returnsOriginalTaskWhenNoScenarios() throws Throwable {
      final Runnable task = () -> {};
      assertThat(bridge.decorateExecutorRunnable("EXECUTOR_SUBMIT", new Object(), task))
          .isSameAs(task);
    }
  }

  @Nested
  @DisplayName("executor callable decoration")
  class ExecutorCallableDecoration {

    @Test
    @DisplayName("returns original callable unchanged when no scenarios are active")
    void returnsOriginalCallableWhenNoScenarios() throws Throwable {
      final Callable<String> task = () -> "result";
      assertThat(bridge.decorateExecutorCallable("EXECUTOR_SUBMIT", new Object(), task))
          .isSameAs(task);
    }
  }

  /** Activates a 1 ms delay on {@code selector} in the bridge's runtime. */
  private ChaosActivationHandle delayOn(final ChaosSelector selector) {
    return runtime.activate(
        ChaosScenario.builder("bridge-reaches-runtime")
            .selector(selector)
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  @Nested
  @DisplayName("void dispatch methods reach the runtime's scenarios")
  class VoidDispatchMethods {

    @Test
    @DisplayName("beforeThreadStart is a THREAD_START")
    void beforeThreadStartReachesTheRuntime() throws Throwable {
      final ChaosActivationHandle handle =
          delayOn(
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY));
      bridge.beforeThreadStart(new Thread(() -> {}), false);
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("beforeWorkerRun is an EXECUTOR_WORKER_RUN")
    void beforeWorkerRunReachesTheRuntime() throws Throwable {
      final ChaosActivationHandle handle =
          delayOn(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)));
      bridge.beforeWorkerRun(new Object(), Thread.currentThread(), () -> {});
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("beforeForkJoinTaskRun is a FORK_JOIN_TASK_RUN")
    void beforeForkJoinTaskRunReachesTheRuntime() throws Throwable {
      final ChaosActivationHandle handle =
          delayOn(ChaosSelector.executor(Set.of(OperationType.FORK_JOIN_TASK_RUN)));
      bridge.beforeForkJoinTaskRun(ForkJoinTask.adapt(() -> {}));
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("beforeQueueOperation is the queue operation it names")
    void beforeQueueOperationReachesTheRuntime() throws Throwable {
      final ChaosActivationHandle handle =
          delayOn(ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)));
      bridge.beforeQueueOperation("QUEUE_TAKE", new Object());
      assertThat(handle.matchedCount()).isZero();
      bridge.beforeQueueOperation("QUEUE_PUT", new Object());
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("beforeClassLoad is a CLASS_LOAD of the class it names")
    void beforeClassLoadReachesTheRuntime() throws Throwable {
      final ChaosActivationHandle handle =
          delayOn(
              ChaosSelector.classLoading(
                  Set.of(OperationType.CLASS_LOAD), NamePattern.exact("com.example.Foo")));
      bridge.beforeClassLoad(null, "com.example.Other");
      assertThat(handle.matchedCount()).isZero();
      bridge.beforeClassLoad(null, "com.example.Foo");
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("beforeExecutorShutdown is the shutdown operation it names")
    void beforeExecutorShutdownReachesTheRuntime() throws Throwable {
      final ChaosActivationHandle handle =
          delayOn(ChaosSelector.shutdown(Set.of(OperationType.EXECUTOR_SHUTDOWN)));
      bridge.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", new Object(), 0L);
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("without a scenario every void dispatch returns at once")
    void withoutAScenarioNothingIsEvaluated() {
      assertThatCode(
              () -> {
                bridge.beforeThreadStart(new Thread(() -> {}), false);
                bridge.beforeWorkerRun(new Object(), Thread.currentThread(), () -> {});
                bridge.beforeForkJoinTaskRun(ForkJoinTask.adapt(() -> {}));
                bridge.beforeQueueOperation("QUEUE_PUT", new Object());
                bridge.beforeClassLoad(null, "com.example.Foo");
                bridge.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", new Object(), 0L);
              })
          .doesNotThrowAnyException();
      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
    }
  }

  @Nested
  @DisplayName("scheduled tick")
  class ScheduledTick {

    @Test
    @DisplayName("returns true — tick allowed — when no scenarios are active")
    void returnsTrueWhenNoScenarios() throws Throwable {
      assertThat(bridge.beforeScheduledTick(new Object(), new Object(), false)).isTrue();
    }
  }

  @Nested
  @DisplayName("boolean queue operation")
  class BooleanQueueOperation {

    @Test
    @DisplayName("returns null when no scenarios are active")
    void returnsNullWhenNoScenarios() throws Throwable {
      assertThat(bridge.beforeBooleanQueueOperation("QUEUE_OFFER", new Object())).isNull();
    }
  }

  @Nested
  @DisplayName("completable future completion")
  class CompletableFutureCompletion {

    @Test
    @DisplayName("returns null when no scenarios are active")
    void returnsNullWhenNoScenarios() throws Throwable {
      assertThat(
              bridge.beforeCompletableFutureComplete(
                  "ASYNC_COMPLETE", new CompletableFuture<>(), null))
          .isNull();
    }
  }

  @Nested
  @DisplayName("resource lookup")
  class ResourceLookup {

    @Test
    @DisplayName("returns currentValue unchanged when no scenarios are active")
    void returnsCurrentValueWhenNoScenarios() throws Throwable {
      final URL url = URI.create("file:/test-resource").toURL();
      assertThat(bridge.afterResourceLookup(null, "test-resource", url)).isSameAs(url);
    }
  }

  @Nested
  @DisplayName("shutdown hook lifecycle")
  class ShutdownHookLifecycle {

    @Test
    @DisplayName("decorateShutdownHook evaluates a hook without a scenario and does not run it")
    void decorateShutdownHookEvaluates() {
      final java.util.concurrent.atomic.AtomicInteger runs =
          new java.util.concurrent.atomic.AtomicInteger();
      final Thread hook = new Thread(runs::incrementAndGet, "probe-hook");
      assertThatCode(() -> bridge.decorateShutdownHook(hook)).doesNotThrowAnyException();
      assertThat(runs).hasValue(0);
    }
  }

  @Nested
  @DisplayName("higher-level time API adjustments")
  class HigherLevelTimeApis {

    @Test
    @DisplayName("adjustInstantNow returns the real instant unchanged when no scenarios are active")
    void adjustInstantNowPassthrough() throws Throwable {
      final java.time.Instant real = java.time.Instant.ofEpochSecond(1_700_000_000L);
      assertThat(bridge.adjustInstantNow(real)).isEqualTo(real);
    }

    @Test
    @DisplayName(
        "adjustLocalDateTimeNow returns the real value unchanged when no scenarios are active")
    void adjustLocalDateTimeNowPassthrough() throws Throwable {
      final java.time.LocalDateTime real = java.time.LocalDateTime.of(2026, 4, 18, 12, 0);
      assertThat(bridge.adjustLocalDateTimeNow(real)).isEqualTo(real);
    }

    @Test
    @DisplayName(
        "adjustZonedDateTimeNow returns the real value unchanged when no scenarios are active")
    void adjustZonedDateTimeNowPassthrough() throws Throwable {
      final java.time.ZonedDateTime real =
          java.time.ZonedDateTime.of(2026, 4, 18, 12, 0, 0, 0, java.time.ZoneId.of("UTC"));
      assertThat(bridge.adjustZonedDateTimeNow(real)).isEqualTo(real);
    }

    @Test
    @DisplayName("adjustDateNew returns the real millis unchanged when no scenarios are active")
    void adjustDateNewPassthrough() throws Throwable {
      assertThat(bridge.adjustDateNew(1_700_000_000_000L)).isEqualTo(1_700_000_000_000L);
    }
  }
}

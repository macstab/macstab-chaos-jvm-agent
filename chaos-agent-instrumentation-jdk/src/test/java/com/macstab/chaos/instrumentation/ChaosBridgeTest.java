package com.macstab.chaos.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.core.ChaosRuntime;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosBridge")
class ChaosBridgeTest {

  private ChaosBridge bridge;

  @BeforeEach
  void setUp() {
    bridge = new ChaosBridge(new ChaosRuntime());
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

  @Nested
  @DisplayName("void dispatch methods")
  class VoidDispatchMethods {

    @Test
    @DisplayName("beforeThreadStart completes without throwing when no scenarios are active")
    void beforeThreadStartCompletes() {
      assertThatCode(() -> bridge.beforeThreadStart(new Thread(() -> {})))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("beforeWorkerRun completes without throwing when no scenarios are active")
    void beforeWorkerRunCompletes() {
      assertThatCode(() -> bridge.beforeWorkerRun(new Object(), Thread.currentThread(), () -> {}))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("beforeForkJoinTaskRun completes without throwing when no scenarios are active")
    void beforeForkJoinTaskRunCompletes() {
      final ForkJoinTask<?> task = ForkJoinTask.adapt(() -> {});
      assertThatCode(() -> bridge.beforeForkJoinTaskRun(task)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("beforeQueueOperation completes without throwing when no scenarios are active")
    void beforeQueueOperationCompletes() {
      assertThatCode(() -> bridge.beforeQueueOperation("QUEUE_PUT", new Object()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("beforeClassLoad completes without throwing when no scenarios are active")
    void beforeClassLoadCompletes() {
      assertThatCode(() -> bridge.beforeClassLoad(null, "com.example.Foo"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("beforeExecutorShutdown completes without throwing when no scenarios are active")
    void beforeExecutorShutdownCompletes() {
      assertThatCode(() -> bridge.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", new Object(), 0L))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("schedule delay adjustment")
  class ScheduleDelayAdjustment {

    @Test
    @DisplayName("returns original delay unchanged when no scenarios are active")
    void returnsOriginalDelayWhenNoScenarios() throws Throwable {
      assertThat(
              bridge.adjustScheduleDelay(
                  "SCHEDULE_SUBMIT", new Object(), new Object(), 500L, false))
          .isEqualTo(500L);
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
    @DisplayName("decorateShutdownHook returns a non-null Thread")
    void decorateShutdownHookReturnsThread() throws Throwable {
      final Thread hook = new Thread(() -> {}, "probe-hook");
      assertThat(bridge.decorateShutdownHook(hook)).isNotNull();
    }

    @Test
    @DisplayName("resolveShutdownHook returns a non-null Thread")
    void resolveShutdownHookReturnsThread() throws Throwable {
      final Thread hook = new Thread(() -> {}, "probe-hook");
      final Thread decorated = bridge.decorateShutdownHook(hook);
      assertThat(bridge.resolveShutdownHook(decorated)).isNotNull();
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

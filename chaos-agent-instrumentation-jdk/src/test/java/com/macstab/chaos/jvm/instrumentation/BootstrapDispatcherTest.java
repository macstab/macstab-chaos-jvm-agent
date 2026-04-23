package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BootstrapDispatcher")
class BootstrapDispatcherTest {

  private final Object executor = new Object();
  private final Object task = new Object();

  @AfterEach
  void resetBridge() {
    BootstrapDispatcher.install(null, null);
  }

  @Nested
  @DisplayName("when bridge is not installed")
  class WhenNotInstalled {

    @Test
    @DisplayName("decorateExecutorRunnable returns original task unchanged")
    void decorateExecutorRunnableReturnsFallback() throws Throwable {
      final Runnable original = () -> {};
      assertThat(
              BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", executor, original))
          .isSameAs(original);
    }

    @Test
    @DisplayName("beforeScheduledTick returns true — task execution is allowed")
    void beforeScheduledTickReturnsTrueAsFallback() throws Throwable {
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, false)).isTrue();
    }

    @Test
    @DisplayName("beforeScheduledTick returns true for periodic tasks")
    void beforeScheduledTickReturnsTrueForPeriodic() throws Throwable {
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, true)).isTrue();
    }

    @Test
    @DisplayName("afterResourceLookup returns currentValue unchanged")
    void afterResourceLookupReturnsCurrentValue() throws Throwable {
      final URL url = URI.create("file:/test-resource").toURL();
      assertThat(BootstrapDispatcher.afterResourceLookup(null, "test-resource", url)).isSameAs(url);
    }

    @Test
    @DisplayName("afterResourceLookup with null currentValue returns null")
    void afterResourceLookupWithNullReturnNull() throws Throwable {
      assertThat(BootstrapDispatcher.afterResourceLookup(null, "missing", null)).isNull();
    }

    @Test
    @DisplayName("decorateShutdownHook returns original hook unchanged")
    void decorateShutdownHookReturnsFallback() throws Throwable {
      final Thread hook = new Thread(() -> {});
      assertThat(BootstrapDispatcher.decorateShutdownHook(hook)).isSameAs(hook);
    }

    @Test
    @DisplayName("resolveShutdownHook returns original thread unchanged")
    void resolveShutdownHookReturnsFallback() {
      final Thread original = new Thread(() -> {});
      assertThat(BootstrapDispatcher.resolveShutdownHook(original)).isSameAs(original);
    }

    @Test
    @DisplayName("beforeBooleanQueueOperation returns null")
    void beforeBooleanQueueOperationReturnsNull() throws Throwable {
      assertThat(BootstrapDispatcher.beforeBooleanQueueOperation("QUEUE_OFFER", new Object()))
          .isNull();
    }

    @Test
    @DisplayName("beforeCompletableFutureComplete returns null")
    void beforeCompletableFutureCompleteReturnsNull() throws Throwable {
      assertThat(BootstrapDispatcher.beforeCompletableFutureComplete("ASYNC_COMPLETE", null, null))
          .isNull();
    }

    @Test
    @DisplayName("adjustScheduleDelay returns original delay unchanged")
    void adjustScheduleDelayReturnsFallback() throws Throwable {
      assertThat(
              BootstrapDispatcher.adjustScheduleDelay(
                  "SCHEDULE_SUBMIT", executor, task, 500L, false))
          .isEqualTo(500L);
    }

    @Test
    @DisplayName("void dispatch methods complete without throwing")
    void voidDispatchMethodsCompleteWithoutThrowing() {
      final Thread thread = new Thread(() -> {});
      assertThatCode(() -> BootstrapDispatcher.beforeThreadStart(thread))
          .doesNotThrowAnyException();
      assertThatCode(() -> BootstrapDispatcher.beforeWorkerRun(executor, thread, () -> {}))
          .doesNotThrowAnyException();
      assertThatCode(() -> BootstrapDispatcher.beforeQueueOperation("QUEUE_PUT", new Object()))
          .doesNotThrowAnyException();
      assertThatCode(
              () -> BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", executor, 0L))
          .doesNotThrowAnyException();
      assertThatCode(() -> BootstrapDispatcher.beforeClassLoad(null, "com.example.Foo"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adjustInstantNow returns real instant unchanged")
    void adjustInstantNowReturnsFallback() throws Throwable {
      final java.time.Instant real = java.time.Instant.ofEpochSecond(1_700_000_000L);
      assertThat(BootstrapDispatcher.adjustInstantNow(real)).isSameAs(real);
    }

    @Test
    @DisplayName("adjustLocalDateTimeNow returns real value unchanged")
    void adjustLocalDateTimeNowReturnsFallback() throws Throwable {
      final java.time.LocalDateTime real = java.time.LocalDateTime.of(2026, 4, 18, 12, 0);
      assertThat(BootstrapDispatcher.adjustLocalDateTimeNow(real)).isSameAs(real);
    }

    @Test
    @DisplayName("adjustZonedDateTimeNow returns real value unchanged")
    void adjustZonedDateTimeNowReturnsFallback() throws Throwable {
      final java.time.ZonedDateTime real =
          java.time.ZonedDateTime.of(2026, 4, 18, 12, 0, 0, 0, java.time.ZoneId.of("UTC"));
      assertThat(BootstrapDispatcher.adjustZonedDateTimeNow(real)).isSameAs(real);
    }

    @Test
    @DisplayName("adjustDateNew returns real millis unchanged")
    void adjustDateNewReturnsFallback() throws Throwable {
      assertThat(BootstrapDispatcher.adjustDateNew(1_700_000_000_000L))
          .isEqualTo(1_700_000_000_000L);
    }
  }

  @Nested
  @DisplayName("when bridge is installed")
  class WhenInstalled {

    @Test
    @DisplayName("beforeScheduledTick forwards call to bridge and returns bridge result")
    void beforeScheduledTickForwardsToBridge() throws Throwable {
      final AtomicInteger calls = new AtomicInteger(0);
      installTickBridge(
          (e, t, p) -> {
            assertThat(e).isSameAs(executor);
            assertThat(t).isSameAs(task);
            assertThat(p).isTrue();
            calls.incrementAndGet();
            return false;
          });

      final boolean result = BootstrapDispatcher.beforeScheduledTick(executor, task, true);

      assertThat(result).isFalse();
      assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("decorateExecutorRunnable forwards call to bridge and returns decorated task")
    void decorateExecutorRunnableForwardsToBridge() throws Throwable {
      final Runnable original = () -> {};
      final Runnable decorated = () -> {};
      installDecorateBridge(
          (op, ex, t) -> {
            assertThat(op).isEqualTo("EXECUTOR_SUBMIT");
            assertThat(ex).isSameAs(executor);
            assertThat(t).isSameAs(original);
            return decorated;
          });

      final Runnable result =
          BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", executor, original);

      assertThat(result).isSameAs(decorated);
    }

    @Test
    @DisplayName("reinstalling bridge with null uninstalls — fallback behaviour resumes")
    void reinstallWithNullRestoresFallback() throws Throwable {
      installTickBridge((e, t, p) -> false);
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, false)).isFalse();

      BootstrapDispatcher.install(null, null);
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, false)).isTrue();
    }
  }

  @Nested
  @DisplayName("reentrancy guard")
  class ReentrancyGuard {

    @Test
    @DisplayName(
        "nested dispatch during bridge execution returns fallback without re-entering bridge")
    void nestedCallReturnsFallbackWithoutReenteringBridge() throws Throwable {
      final AtomicInteger bridgeCallCount = new AtomicInteger(0);
      installTickBridge(
          (e, t, p) -> {
            bridgeCallCount.incrementAndGet();
            // Nested call: DEPTH=1, guard fires immediately, returns fallback true
            final boolean nested = BootstrapDispatcher.beforeScheduledTick(e, t, p);
            assertThat(nested).as("nested call must return fallback (true)").isTrue();
            return false;
          });

      final boolean result = BootstrapDispatcher.beforeScheduledTick(executor, task, false);

      assertThat(result).isFalse();
      assertThat(bridgeCallCount.get())
          .as("bridge must be entered exactly once despite nested call")
          .isEqualTo(1);
    }

    @Test
    @DisplayName("depth is reset after normal call — subsequent call dispatches to bridge")
    void depthResetAfterNormalCall() throws Throwable {
      installTickBridge((e, t, p) -> false);
      BootstrapDispatcher.beforeScheduledTick(executor, task, false);

      // Replace bridge and call again — must dispatch, not return fallback
      final AtomicInteger secondCount = new AtomicInteger(0);
      installTickBridge(
          (e, t, p) -> {
            secondCount.incrementAndGet();
            return true;
          });

      final boolean second = BootstrapDispatcher.beforeScheduledTick(executor, task, false);
      assertThat(second).isTrue();
      assertThat(secondCount.get()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("exception propagation")
  class ExceptionPropagation {

    @Test
    @DisplayName("RuntimeException from bridge propagates as original type without wrapping")
    void runtimeExceptionPropagatesUnwrapped() throws Exception {
      final RuntimeException injected = new RuntimeException("bridge-failure");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });

      assertThatThrownBy(() -> BootstrapDispatcher.beforeScheduledTick(executor, task, false))
          .isSameAs(injected)
          .isExactlyInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("checked exception from bridge propagates via sneaky throw as original type")
    void checkedExceptionPropagatesSneakily() throws Exception {
      final Exception injected = new Exception("checked-bridge-failure");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });

      assertThatThrownBy(() -> BootstrapDispatcher.beforeScheduledTick(executor, task, false))
          .isSameAs(injected)
          .isExactlyInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Error from bridge propagates as original type without wrapping")
    void errorPropagatesUnwrapped() throws Exception {
      final Error injected = new AssertionError("bridge-error");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });

      assertThatThrownBy(() -> BootstrapDispatcher.beforeScheduledTick(executor, task, false))
          .isSameAs(injected)
          .isExactlyInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("depth is reset after exception — subsequent call dispatches normally")
    void depthResetAfterException() throws Throwable {
      final RuntimeException ex = new RuntimeException("fail");
      installTickBridge(
          (e, t, p) -> {
            throw ex;
          });
      assertThatThrownBy(() -> BootstrapDispatcher.beforeScheduledTick(executor, task, false))
          .isSameAs(ex);

      // DEPTH must have been reset in the finally block — next call must enter bridge
      final AtomicInteger recoveryCount = new AtomicInteger(0);
      installTickBridge(
          (e, t, p) -> {
            recoveryCount.incrementAndGet();
            return false;
          });
      final boolean recovered = BootstrapDispatcher.beforeScheduledTick(executor, task, false);
      assertThat(recovered).isFalse();
      assertThat(recoveryCount.get()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("void dispatch with bridge installed")
  class VoidDispatchWithBridgeInstalled {

    @Test
    @DisplayName("beforeThreadStart forwards call to bridge")
    void beforeThreadStartForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      final Thread thread = new Thread(() -> {});
      installThreadStartBridge(
          t -> {
            assertThat(t).isSameAs(thread);
            called.set(true);
          });

      BootstrapDispatcher.beforeThreadStart(thread);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeWorkerRun forwards call to bridge")
    void beforeWorkerRunForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      final Thread worker = new Thread(() -> {});
      final Runnable task = () -> {};
      installWorkerRunBridge(
          (e, w, t) -> {
            assertThat(e).isSameAs(executor);
            assertThat(w).isSameAs(worker);
            assertThat(t).isSameAs(task);
            called.set(true);
          });

      BootstrapDispatcher.beforeWorkerRun(executor, worker, task);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeForkJoinTaskRun forwards call to bridge")
    void beforeForkJoinTaskRunForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      final ForkJoinTask<?> fjTask = ForkJoinTask.adapt(() -> {});
      installForkJoinTaskBridge(
          t -> {
            assertThat(t).isSameAs(fjTask);
            called.set(true);
          });

      BootstrapDispatcher.beforeForkJoinTaskRun(fjTask);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeQueueOperation forwards call to bridge")
    void beforeQueueOperationForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      installQueueOperationBridge(
          (op, q) -> {
            assertThat(op).isEqualTo("QUEUE_PUT");
            assertThat(q).isSameAs(task);
            called.set(true);
          });

      BootstrapDispatcher.beforeQueueOperation("QUEUE_PUT", task);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeExecutorShutdown forwards call to bridge")
    void beforeExecutorShutdownForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      installExecutorShutdownBridge(
          (op, e, t) -> {
            assertThat(op).isEqualTo("EXECUTOR_SHUTDOWN");
            assertThat(e).isSameAs(executor);
            assertThat(t).isEqualTo(1000L);
            called.set(true);
          });

      BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", executor, 1000L);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeClassLoad forwards call to bridge")
    void beforeClassLoadForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      installClassLoadBridge(
          (loader, name) -> {
            assertThat(loader).isNull();
            assertThat(name).isEqualTo("com.example.Foo");
            called.set(true);
          });

      BootstrapDispatcher.beforeClassLoad(null, "com.example.Foo");

      assertThat(called).isTrue();
    }
  }

  // — bridge test infrastructure —

  @FunctionalInterface
  interface TickBehavior {
    boolean tick(Object executor, Object task, boolean periodic) throws Throwable;
  }

  @FunctionalInterface
  interface DecorateBehavior {
    Runnable decorate(String operation, Object executor, Runnable task) throws Throwable;
  }

  /**
   * Installs a minimal bridge with only {@code BEFORE_SCHEDULED_TICK} wired. All other slots are
   * left null; they will NPE if accessed, which is intentional — tests that trigger unrelated slots
   * have a design problem.
   */
  private static void installTickBridge(final TickBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_SCHEDULED_TICK] =
        MethodHandles.lookup()
            .findVirtual(
                TickBehavior.class,
                "tick",
                MethodType.methodType(boolean.class, Object.class, Object.class, boolean.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  /** Installs a minimal bridge with only {@code DECORATE_EXECUTOR_RUNNABLE} wired. */
  private static void installDecorateBridge(final DecorateBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.DECORATE_EXECUTOR_RUNNABLE] =
        MethodHandles.lookup()
            .findVirtual(
                DecorateBehavior.class,
                "decorate",
                MethodType.methodType(Runnable.class, String.class, Object.class, Runnable.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  @FunctionalInterface
  interface ThreadStartBehavior {
    void act(Thread thread) throws Throwable;
  }

  @FunctionalInterface
  interface WorkerRunBehavior {
    void act(Object executor, Thread worker, Runnable task) throws Throwable;
  }

  @FunctionalInterface
  interface ForkJoinTaskBehavior {
    void act(ForkJoinTask<?> task) throws Throwable;
  }

  @FunctionalInterface
  interface QueueOperationBehavior {
    void act(String operation, Object queue) throws Throwable;
  }

  @FunctionalInterface
  interface ExecutorShutdownBehavior {
    void act(String operation, Object executor, long timeoutMillis) throws Throwable;
  }

  @FunctionalInterface
  interface ClassLoadBehavior {
    void act(ClassLoader loader, String className) throws Throwable;
  }

  private static void installThreadStartBridge(final ThreadStartBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_THREAD_START] =
        MethodHandles.lookup()
            .findVirtual(
                ThreadStartBehavior.class, "act", MethodType.methodType(void.class, Thread.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installWorkerRunBridge(final WorkerRunBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_WORKER_RUN] =
        MethodHandles.lookup()
            .findVirtual(
                WorkerRunBehavior.class,
                "act",
                MethodType.methodType(void.class, Object.class, Thread.class, Runnable.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installForkJoinTaskBridge(final ForkJoinTaskBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_FORK_JOIN_TASK_RUN] =
        MethodHandles.lookup()
            .findVirtual(
                ForkJoinTaskBehavior.class,
                "act",
                MethodType.methodType(void.class, ForkJoinTask.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installQueueOperationBridge(final QueueOperationBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_QUEUE_OPERATION] =
        MethodHandles.lookup()
            .findVirtual(
                QueueOperationBehavior.class,
                "act",
                MethodType.methodType(void.class, String.class, Object.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installExecutorShutdownBridge(final ExecutorShutdownBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_EXECUTOR_SHUTDOWN] =
        MethodHandles.lookup()
            .findVirtual(
                ExecutorShutdownBehavior.class,
                "act",
                MethodType.methodType(void.class, String.class, Object.class, long.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installClassLoadBridge(final ClassLoadBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_CLASS_LOAD] =
        MethodHandles.lookup()
            .findVirtual(
                ClassLoadBehavior.class,
                "act",
                MethodType.methodType(void.class, ClassLoader.class, String.class));
    BootstrapDispatcher.install(behavior, handles);
  }
}

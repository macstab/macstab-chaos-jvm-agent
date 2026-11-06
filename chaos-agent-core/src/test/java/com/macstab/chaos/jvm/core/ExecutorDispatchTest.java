package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The executor, queue, future, scheduler and shutdown-hook entry points: which target each one
 * reports to the selectors, and that a composed delay still runs before a terminal action that ends
 * the call early.
 */
@DisplayName("Executor dispatch")
class ExecutorDispatchTest {

  /** Elapsed time a composed {@link #DELAY} must at least take; below it, the delay was skipped. */
  private static final Duration DELAY = Duration.ofMillis(60);

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private void activate(final String id, final ChaosSelector selector, final ChaosEffect effect) {
    runtime.activate(
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  /** Activates a delay and a terminal effect on the same selector. */
  private void delayThen(final ChaosSelector selector, final ChaosEffect terminal) {
    activate("delay", selector, ChaosEffect.delay(DELAY));
    activate("terminal", selector, terminal);
  }

  private static Duration timed(final ThrowingRunnable action) throws Throwable {
    final long start = System.nanoTime();
    action.run();
    return Duration.ofNanos(System.nanoTime() - start);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Throwable;
  }

  /** An executor type a selector can name. */
  static final class PaymentsExecutor {}

  /** A queue type a selector can name. */
  static final class OrdersQueue {}

  /** A shutdown hook type a selector can name. */
  static final class FlushHook extends Thread {
    FlushHook(final Runnable body) {
      super(body, "flush-hook");
    }
  }

  private static ChaosSelector.ExecutorSelector submitsTo(final NamePattern executorClass) {
    return new ChaosSelector.ExecutorSelector(
        Set.of(OperationType.EXECUTOR_SUBMIT), executorClass, NamePattern.any(), null);
  }

  @Nested
  @DisplayName("task submission")
  class Submission {

    private final Runnable runnable = new AtomicInteger()::incrementAndGet;
    private final Callable<String> callable = () -> "result";

    @Test
    @DisplayName("an executor-class pattern matches the executor the task is submitted to")
    void executorClassIsTheTarget() {
      activate(
          "payments-only",
          submitsTo(NamePattern.exact(PaymentsExecutor.class.getName())),
          ChaosEffect.reject("payments full"));

      assertThatThrownBy(
              () ->
                  runtime.decorateExecutorRunnable(
                      "EXECUTOR_SUBMIT", new PaymentsExecutor(), runnable))
          .isInstanceOf(RejectedExecutionException.class);
      assertThatThrownBy(
              () ->
                  runtime.decorateExecutorCallable(
                      "EXECUTOR_SUBMIT", new PaymentsExecutor(), callable))
          .isInstanceOf(RejectedExecutionException.class);
      assertThat(runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, runnable))
          .isSameAs(runnable);
      assertThat(runtime.decorateExecutorCallable("EXECUTOR_SUBMIT", this, callable))
          .isSameAs(callable);
    }

    @Test
    @DisplayName("a submission without a known executor matches as \"unknown\"")
    void unknownExecutor() {
      activate("unknown", submitsTo(NamePattern.exact("unknown")), ChaosEffect.reject("no"));

      assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", null, runnable))
          .isInstanceOf(RejectedExecutionException.class);
      assertThatThrownBy(() -> runtime.decorateExecutorCallable("EXECUTOR_SUBMIT", null, callable))
          .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("a delay alone submits the task itself, after waiting")
    void delayKeepsTheTask() {
      activate(
          "delay",
          ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)),
          ChaosEffect.delay(Duration.ofMillis(1)));

      assertThat(runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, runnable))
          .isSameAs(runnable);
      assertThat(runtime.decorateExecutorCallable("EXECUTOR_SUBMIT", this, callable))
          .isSameAs(callable);
    }

    @Test
    @DisplayName("a delay composed with suppress runs before the runnable is dropped")
    void delayBeforeSuppressedRunnable() throws Throwable {
      delayThen(
          ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)), ChaosEffect.suppress());
      final AtomicInteger runs = new AtomicInteger();

      final Runnable[] submitted = new Runnable[1];
      final Duration elapsed =
          timed(
              () ->
                  submitted[0] =
                      runtime.decorateExecutorRunnable(
                          "EXECUTOR_SUBMIT", this, runs::incrementAndGet));

      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
      submitted[0].run();
      assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("a delay composed with suppress runs before the callable is dropped")
    void delayBeforeSuppressedCallable() throws Throwable {
      delayThen(
          ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)), ChaosEffect.suppress());

      final Duration elapsed =
          timed(
              () ->
                  assertThat(
                          runtime
                              .decorateExecutorCallable("EXECUTOR_SUBMIT", this, callable)
                              .call())
                      .isNull());

      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
    }
  }

  @Nested
  @DisplayName("worker runs and fork-join tasks")
  class WorkerRuns {

    @Test
    @DisplayName("a worker run without a known worker thread is still evaluated")
    void unknownWorker() throws Throwable {
      activate(
          "worker",
          ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)),
          ChaosEffect.delay(Duration.ofMillis(1)));

      runtime.beforeWorkerRun(this, null, () -> {});

      assertThat(runtime.diagnostics().scenario("worker").orElseThrow().appliedCount())
          .isEqualTo(1L);
    }

    @Test
    @DisplayName("a fork-join task run is evaluated")
    void forkJoinTaskRun() throws Throwable {
      activate(
          "fork-join",
          ChaosSelector.executor(Set.of(OperationType.FORK_JOIN_TASK_RUN)),
          ChaosEffect.delay(Duration.ofMillis(1)));

      runtime.beforeForkJoinTaskRun(java.util.concurrent.ForkJoinTask.adapt(() -> {}));

      assertThat(runtime.diagnostics().scenario("fork-join").orElseThrow().appliedCount())
          .isEqualTo(1L);
    }

    @Test
    @DisplayName("a reject on a worker or fork-join run is refused: the pool cannot fail the task")
    void rejectRefused() {
      for (final OperationType operation :
          Set.of(OperationType.EXECUTOR_WORKER_RUN, OperationType.FORK_JOIN_TASK_RUN)) {
        assertThatThrownBy(
                () ->
                    activate(
                        "reject-" + operation,
                        ChaosSelector.executor(Set.of(operation)),
                        ChaosEffect.reject("no")))
            .isInstanceOf(com.macstab.chaos.jvm.api.ChaosValidationException.class)
            .hasMessageContaining(operation.name());
      }
      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
    }
  }

  @Nested
  @DisplayName("queue operations")
  class QueueOperations {

    private ChaosSelector.QueueSelector queue(
        final OperationType operation, final NamePattern queueClass) {
      return new ChaosSelector.QueueSelector(Set.of(operation), queueClass);
    }

    @Test
    @DisplayName("a queue-class pattern matches the queue; an unknown queue is \"unknown\"")
    void queueClassIsTheTarget() throws Throwable {
      activate(
          "orders",
          queue(OperationType.QUEUE_PUT, NamePattern.exact(OrdersQueue.class.getName())),
          ChaosEffect.reject("orders full"));
      activate(
          "unknown", queue(OperationType.QUEUE_TAKE, NamePattern.exact("unknown")), reject("?"));

      assertThatThrownBy(() -> runtime.beforeQueueOperation("QUEUE_PUT", new OrdersQueue()))
          .hasMessageContaining("orders full");
      runtime.beforeQueueOperation("QUEUE_PUT", new ArrayBlockingQueue<>(1));
      assertThatThrownBy(() -> runtime.beforeQueueOperation("QUEUE_TAKE", null))
          .hasMessageContaining("?");
    }

    @Test
    @DisplayName("an offer rejected through the void entry point fails as a suppressed operation")
    void rejectedOfferThroughVoidEntryPoint() {
      activate(
          "offer", queue(OperationType.QUEUE_OFFER, NamePattern.any()), ChaosEffect.reject("x"));

      assertThatThrownBy(() -> runtime.beforeQueueOperation("QUEUE_OFFER", new OrdersQueue()))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessage("operation suppressed by chaos agent");
    }

    @Test
    @DisplayName("a boolean queue operation keeps its real result under a delay alone")
    void delayKeepsTheRealResult() throws Throwable {
      activate(
          "delay",
          queue(OperationType.QUEUE_OFFER, NamePattern.any()),
          ChaosEffect.delay(Duration.ofMillis(1)));

      assertThat(runtime.beforeBooleanQueueOperation("QUEUE_OFFER", new OrdersQueue())).isNull();
    }

    @Test
    @DisplayName("a rejected offer reports false, after a composed delay")
    void rejectedOfferReportsFalseAfterTheDelay() throws Throwable {
      delayThen(queue(OperationType.QUEUE_OFFER, NamePattern.any()), ChaosEffect.reject("full"));

      final Boolean[] result = new Boolean[1];
      final Duration elapsed =
          timed(() -> result[0] = runtime.beforeBooleanQueueOperation("QUEUE_OFFER", null));

      assertThat(result[0]).isFalse();
      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
    }

    @Test
    @DisplayName("a boolean operation on an unknown queue matches as \"unknown\"")
    void unknownQueueForBooleanOperation() throws Throwable {
      activate(
          "unknown", queue(OperationType.QUEUE_POLL, NamePattern.exact("unknown")), suppress());

      assertThat(runtime.beforeBooleanQueueOperation("QUEUE_POLL", null)).isFalse();
      assertThat(runtime.beforeBooleanQueueOperation("QUEUE_POLL", new OrdersQueue())).isNull();
    }
  }

  @Nested
  @DisplayName("CompletableFuture completion and cancellation")
  class Futures {

    private final CompletableFuture<String> future = new CompletableFuture<>();

    @Test
    @DisplayName("a completion without a payload is still evaluated")
    void nullPayload() throws Throwable {
      activate("complete", ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)), suppress());

      assertThat(runtime.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, null)).isFalse();
    }

    @Test
    @DisplayName("a suppressed completion waits out a composed delay first")
    void delayBeforeSuppressedCompletion() throws Throwable {
      delayThen(ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)), suppress());

      final Duration elapsed =
          timed(() -> runtime.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, "v"));

      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
    }

    @Test
    @DisplayName("an exceptional completion of a future already completed reports false")
    void exceptionalCompletionOfACompletedFuture() throws Throwable {
      activate(
          "exceptional",
          ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)),
          ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.IO, "io"));
      future.complete("real");

      assertThat(runtime.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, "v")).isFalse();
      assertThat(future.join()).isEqualTo("real");
    }

    @Test
    @DisplayName("an exceptional completion at cancel completes the future and skips the cancel")
    void exceptionalCompletionAtCancel() throws Throwable {
      activate(
          "cancel",
          ChaosSelector.async(Set.of(OperationType.ASYNC_CANCEL)),
          ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.TIMEOUT, "late"));

      assertThat(runtime.beforeAsyncCancel(future, true)).isTrue();
      assertThat(future.isCompletedExceptionally()).isTrue();
      assertThat(future.isCancelled()).isFalse();
      assertThat(future.exceptionNow())
          .isInstanceOf(java.util.concurrent.TimeoutException.class)
          .hasMessage("late");
    }

    @Test
    @DisplayName("a suppressed cancel waits out a composed delay first")
    void delayBeforeSuppressedCancel() throws Throwable {
      delayThen(ChaosSelector.async(Set.of(OperationType.ASYNC_CANCEL)), suppress());

      final boolean[] suppressed = new boolean[1];
      final Duration elapsed = timed(() -> suppressed[0] = runtime.beforeAsyncCancel(future, true));

      assertThat(suppressed[0]).isTrue();
      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
    }
  }

  @Nested
  @DisplayName("scheduled ticks")
  class ScheduledTicks {

    @Test
    @DisplayName("an executor-class pattern matches the scheduler; an unknown one is \"unknown\"")
    void schedulerClassIsTheTarget() throws Throwable {
      activate(
          "payments",
          new ChaosSelector.SchedulingSelector(
              Set.of(OperationType.SCHEDULE_TICK),
              NamePattern.exact(PaymentsExecutor.class.getName()),
              null),
          suppress());

      assertThat(runtime.beforeScheduledTick(new PaymentsExecutor(), (Runnable) () -> {}, true))
          .isFalse();
      assertThat(runtime.beforeScheduledTick(this, (Runnable) () -> {}, true)).isTrue();
      assertThat(runtime.beforeScheduledTick(null, (Runnable) () -> {}, true)).isTrue();
    }

    @Test
    @DisplayName("a suppressed tick waits out a composed delay first")
    void delayBeforeSuppressedTick() throws Throwable {
      delayThen(ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_TICK)), suppress());

      final boolean[] runs = new boolean[1];
      final Duration elapsed =
          timed(() -> runs[0] = runtime.beforeScheduledTick(null, (Runnable) () -> {}, false));

      assertThat(runs[0]).isFalse();
      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
    }
  }

  @Nested
  @DisplayName("executor shutdown")
  class Shutdown {

    @Test
    @DisplayName("a target-class pattern matches the executor; an unknown one is \"unknown\"")
    void executorClassIsTheTarget() throws Throwable {
      activate(
          "payments",
          new ChaosSelector.ShutdownSelector(
              Set.of(OperationType.EXECUTOR_SHUTDOWN),
              NamePattern.exact(PaymentsExecutor.class.getName())),
          ChaosEffect.reject("stay up"));
      activate(
          "unknown",
          new ChaosSelector.ShutdownSelector(
              Set.of(OperationType.EXECUTOR_AWAIT_TERMINATION), NamePattern.exact("unknown")),
          ChaosEffect.reject("unknown executor"));

      assertThatThrownBy(
              () -> runtime.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", new PaymentsExecutor(), 0L))
          .hasMessageContaining("stay up");
      runtime.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", this, 0L);
      assertThatThrownBy(
              () -> runtime.beforeExecutorShutdown("EXECUTOR_AWAIT_TERMINATION", null, 10L))
          .hasMessageContaining("unknown executor");
    }
  }

  @Nested
  @DisplayName("shutdown hooks")
  class ShutdownHooks {

    private final AtomicInteger runs = new AtomicInteger();

    @Test
    @DisplayName("a target-class pattern matches the hook's own class")
    void hookClassIsTheTarget() throws Throwable {
      activate(
          "flush",
          new ChaosSelector.ShutdownSelector(
              Set.of(OperationType.SHUTDOWN_HOOK_REGISTER),
              NamePattern.exact(FlushHook.class.getName())),
          ChaosEffect.reject("no flush"));

      assertThatThrownBy(() -> runtime.decorateShutdownHook(new FlushHook(runs::incrementAndGet)))
          .isInstanceOf(RejectedExecutionException.class);
      assertThat(runtime.decorateShutdownHook(new Thread(runs::incrementAndGet))).isNotNull();
    }

    @Test
    @DisplayName("a missing hook is evaluated as a plain Thread and fails with the rejection")
    void missingHook() {
      activate(
          "hooks",
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.reject("no hooks"));

      assertThatThrownBy(() -> runtime.decorateShutdownHook(null))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessageContaining("no hooks");
    }

    @Test
    @DisplayName("a missing hook without a matching scenario reaches the JDK as null")
    void missingHookPassesThrough() throws Throwable {
      activate(
          "hooks",
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.delay(Duration.ofMillis(1)));

      assertThat(runtime.decorateShutdownHook(null)).isNull();
    }

    @Test
    @DisplayName("a hook registered twice gets its first wrapper, so the JDK rejects the second")
    void hookRegisteredTwice() throws Throwable {
      activate(
          "hooks",
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.delay(Duration.ofMillis(1)));
      final Thread hook = new Thread(runs::incrementAndGet, "twice-hook");

      final Thread first = runtime.decorateShutdownHook(hook);

      assertThat(runtime.decorateShutdownHook(hook)).isSameAs(first);
      assertThat(runtime.resolveShutdownHook(hook)).isSameAs(first);
      assertThat(runtime.resolveShutdownHook(hook)).isSameAs(hook);
    }

    @Test
    @DisplayName("the wrapper of a daemon hook is a daemon too")
    void daemonWrapper() throws Throwable {
      activate(
          "hooks",
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.delay(Duration.ofMillis(1)));
      final Thread daemon = new Thread(runs::incrementAndGet, "daemon-hook");
      daemon.setDaemon(true);
      final Thread plain = new Thread(runs::incrementAndGet, "plain-hook");

      assertThat(runtime.decorateShutdownHook(daemon).isDaemon()).isTrue();
      assertThat(runtime.decorateShutdownHook(plain).isDaemon()).isFalse();
    }

    @Test
    @DisplayName("a removed hook's wrapper no longer runs the hook's body")
    void removedHookDoesNotRun() throws Throwable {
      activate(
          "hooks",
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.delay(Duration.ofMillis(1)));
      final Thread hook = new Thread(runs::incrementAndGet, "user-hook");
      final Thread wrapper = runtime.decorateShutdownHook(hook);

      assertThat(runtime.resolveShutdownHook(hook)).isSameAs(wrapper);
      wrapper.start();
      wrapper.join(5_000L);

      assertThat(runs).hasValue(0);
    }
  }

  private static ChaosEffect reject(final String message) {
    return ChaosEffect.reject(message);
  }

  private static ChaosEffect suppress() {
    return ChaosEffect.suppress();
  }
}

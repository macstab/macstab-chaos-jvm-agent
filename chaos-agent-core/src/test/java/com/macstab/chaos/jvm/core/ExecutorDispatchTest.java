package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
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
    @DisplayName("a reject on take or poll is refused: the JDK's take() and poll() do not fail so")
    void takeAndPollRejectRefused() throws Throwable {
      for (final OperationType operation :
          Set.of(OperationType.QUEUE_TAKE, OperationType.QUEUE_POLL)) {
        assertThatThrownBy(
                () ->
                    activate(
                        "reject-" + operation, queue(operation, NamePattern.any()), reject("no")))
            .isInstanceOf(com.macstab.chaos.jvm.api.ChaosValidationException.class)
            .hasMessageContaining(operation.name());
      }
      activate(
          "slow-take",
          queue(OperationType.QUEUE_TAKE, NamePattern.any()),
          ChaosEffect.delay(Duration.ofMillis(1)));
      runtime.beforeQueueOperation("QUEUE_TAKE", new ArrayBlockingQueue<>(1));
      assertThat(runtime.diagnostics().scenario("slow-take").orElseThrow().appliedCount())
          .isEqualTo(1L);
    }

    @Test
    @DisplayName("a queue-class pattern matches the queue; an unknown queue is \"unknown\"")
    void queueClassIsTheTarget() throws Throwable {
      activate(
          "orders",
          queue(OperationType.QUEUE_PUT, NamePattern.exact(OrdersQueue.class.getName())),
          ChaosEffect.reject("orders full"));
      activate(
          "unknown", queue(OperationType.QUEUE_PUT, NamePattern.exact("unknown")), reject("?"));

      assertThatThrownBy(() -> runtime.beforeQueueOperation("QUEUE_PUT", new OrdersQueue()))
          .isInstanceOf(InterruptedException.class)
          .hasMessageContaining("orders full");
      runtime.beforeQueueOperation("QUEUE_PUT", new ArrayBlockingQueue<>(1));
      assertThatThrownBy(() -> runtime.beforeQueueOperation("QUEUE_PUT", null))
          .isInstanceOf(InterruptedException.class)
          .hasMessageContaining("?");
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
  @DisplayName("an offer to a queue without a capacity limit")
  class QueueOfferOnUnboundedQueue {

    private ChaosActivationHandle offers(final String id, final ChaosEffect effect) {
      return runtime.activate(
          ChaosScenario.builder(id)
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.queue(Set.of(OperationType.QUEUE_OFFER)))
              .effect(effect)
              .activationPolicy(ActivationPolicy.always())
              .build());
    }

    @Test
    @DisplayName("is neither rejected nor suppressed, and counts no application")
    void unboundedQueueKeepsTheOffer() throws Throwable {
      // Arrange
      final ChaosActivationHandle rejecting = offers("reject", reject("full"));
      final ChaosActivationHandle suppressing = offers("suppress", suppress());

      // Act + Assert: a queue that can never be full has no "full" to report
      assertThat(
              runtime.beforeBooleanQueueOperation(
                  "QUEUE_OFFER", new PriorityBlockingQueue<String>()))
          .isNull();
      assertThat(
              runtime.beforeBooleanQueueOperation("QUEUE_OFFER", new LinkedBlockingQueue<String>()))
          .isNull();
      assertThat(rejecting.appliedCount()).isZero();
      assertThat(suppressing.appliedCount()).isZero();
    }

    @Test
    @DisplayName("is still rejected on a bounded queue")
    void boundedQueueIsRejected() throws Throwable {
      // Arrange
      final ChaosActivationHandle rejecting = offers("reject", reject("full"));

      // Act
      final Boolean forced =
          runtime.beforeBooleanQueueOperation("QUEUE_OFFER", new ArrayBlockingQueue<String>(1));

      // Assert
      assertThat(forced).isFalse();
      assertThat(rejecting.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("is treated as bounded when the queue cannot report its capacity")
    void failingCapacityCountsAsBounded() throws Throwable {
      // Arrange
      final ChaosActivationHandle rejecting = offers("reject", reject("full"));
      final LinkedBlockingQueue<String> broken =
          new LinkedBlockingQueue<>() {
            private static final long serialVersionUID = 1L;

            @Override
            public int remainingCapacity() {
              throw new IllegalStateException("no capacity");
            }
          };

      // Act + Assert
      assertThat(runtime.beforeBooleanQueueOperation("QUEUE_OFFER", broken)).isFalse();
      assertThat(rejecting.appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("still waits out a delay")
    void delayStillApplies() throws Throwable {
      // Arrange
      final ChaosActivationHandle delaying = offers("delay", ChaosEffect.delay(DELAY));

      // Act
      final Duration elapsed =
          timed(
              () ->
                  runtime.beforeBooleanQueueOperation(
                      "QUEUE_OFFER", new LinkedBlockingQueue<String>()));

      // Assert
      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
      assertThat(delaying.appliedCount()).isEqualTo(1L);
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
              Set.of(OperationType.EXECUTOR_AWAIT_TERMINATION),
              NamePattern.exact(PaymentsExecutor.class.getName())),
          ChaosEffect.reject("stay up"));
      activate(
          "unknown",
          new ChaosSelector.ShutdownSelector(
              Set.of(OperationType.EXECUTOR_AWAIT_TERMINATION), NamePattern.exact("unknown")),
          ChaosEffect.reject("unknown executor"));

      assertThatThrownBy(
              () ->
                  runtime.beforeExecutorShutdown(
                      "EXECUTOR_AWAIT_TERMINATION", new PaymentsExecutor(), 10L))
          .isInstanceOf(InterruptedException.class)
          .hasMessageContaining("stay up");
      runtime.beforeExecutorShutdown("EXECUTOR_AWAIT_TERMINATION", this, 10L);
      assertThatThrownBy(
              () -> runtime.beforeExecutorShutdown("EXECUTOR_AWAIT_TERMINATION", null, 10L))
          .isInstanceOf(InterruptedException.class)
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
          .isExactlyInstanceOf(IllegalStateException.class);
      assertThatCode(() -> runtime.decorateShutdownHook(new Thread(runs::incrementAndGet)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a missing hook is evaluated as a plain Thread and fails with the rejection")
    void missingHook() {
      activate(
          "hooks",
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.reject("no hooks"));

      assertThatThrownBy(() -> runtime.decorateShutdownHook(null))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Shutdown in progress");
    }

    @Test
    @DisplayName("a missing hook without a rejecting scenario is left to the JDK")
    void missingHookPassesThrough() {
      activate(
          "hooks",
          ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
          ChaosEffect.delay(Duration.ofMillis(1)));

      assertThatCode(() -> runtime.decorateShutdownHook(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a hook is evaluated without being run, with or without a scenario applied")
    void hookIsEvaluatedNotRun() throws Throwable {
      final Thread idle = new Thread(runs::incrementAndGet, "idle-hook");
      runtime.decorateShutdownHook(idle);

      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("hooks")
                  .selector(ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Thread hook = new Thread(runs::incrementAndGet, "user-hook");

      runtime.decorateShutdownHook(hook);
      assertThat(handle.appliedCount()).isEqualTo(1L);
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

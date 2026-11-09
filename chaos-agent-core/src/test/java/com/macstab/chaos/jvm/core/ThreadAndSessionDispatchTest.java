package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Thread starts, and how a session travels with work handed to other threads: the session wrappers
 * of executor submissions and the hand-off of thread starts and fork-join tasks.
 */
@DisplayName("Thread and session dispatch")
class ThreadAndSessionDispatchTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private void activate(final ChaosSelector selector, final ChaosEffect effect) {
    runtime.activate(
        ChaosScenario.builder("thread")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  /** Runs {@code body} on a fresh thread and waits for it. */
  private static void onOtherThread(final Runnable body) throws InterruptedException {
    final Thread thread = new Thread(body, "other");
    thread.start();
    thread.join(10_000L);
  }

  @Nested
  @DisplayName("thread starts")
  class ThreadStarts {

    @Test
    @DisplayName("a thread-name pattern matches the thread being started")
    void threadName() throws Throwable {
      runtime.beforeThreadStart(new Thread(() -> {}, "worker-1"), false);
      activate(
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.ANY,
              NamePattern.prefix("worker-"),
              null),
          ChaosEffect.reject("no workers"));

      // the JVM's own failure to create the native thread, whatever the scenario's message
      assertThatThrownBy(() -> runtime.beforeThreadStart(new Thread(() -> {}, "worker-1"), false))
          .isExactlyInstanceOf(OutOfMemoryError.class)
          .hasMessageStartingWith("unable to create native thread");
      assertThatCode(() -> runtime.beforeThreadStart(new Thread(() -> {}, "main-loop"), false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a daemon filter matches daemon threads only")
    void daemonFilter() {
      activate(
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.ANY,
              NamePattern.any(),
              Boolean.TRUE),
          ChaosEffect.suppress());
      final Thread daemon = new Thread(() -> {});
      daemon.setDaemon(true);

      assertThatThrownBy(() -> runtime.beforeThreadStart(daemon, false))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessage("thread start suppressed by chaos agent");
      assertThatCode(() -> runtime.beforeThreadStart(new Thread(() -> {}), false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a virtual thread start is its own operation, matched by the VIRTUAL kind")
    void virtualThread() {
      activate(
          ChaosSelector.thread(
              Set.of(OperationType.VIRTUAL_THREAD_START), ChaosSelector.ThreadKind.VIRTUAL),
          ChaosEffect.reject("no virtual threads"));

      assertThatThrownBy(
              () -> runtime.beforeThreadStart(Thread.ofVirtual().unstarted(() -> {}), false))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessage("no virtual threads");
      assertThatCode(() -> runtime.beforeThreadStart(new Thread(() -> {}), false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a platform-kind selector does not match a virtual thread")
    void platformKind() {
      activate(
          ChaosSelector.thread(
              Set.of(OperationType.THREAD_START, OperationType.VIRTUAL_THREAD_START),
              ChaosSelector.ThreadKind.PLATFORM),
          ChaosEffect.reject("no platform threads"));

      assertThatCode(() -> runtime.beforeThreadStart(Thread.ofVirtual().unstarted(() -> {}), false))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> runtime.beforeThreadStart(new Thread(() -> {}), false))
          .isExactlyInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("an unknown thread is evaluated without a name, daemon flag or kind")
    void unknownThread() {
      activate(
          ChaosSelector.thread(Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY),
          ChaosEffect.reject("no threads"));

      assertThatThrownBy(() -> runtime.beforeThreadStart(null, false))
          .isExactlyInstanceOf(OutOfMemoryError.class);
    }
  }

  @Nested
  @DisplayName("session hand-off")
  class Handoff {

    @Test
    @DisplayName("a started thread runs in the starter's session, and leaves it when done")
    void threadStartCarriesTheSession() throws Throwable {
      final AtomicReference<String> during = new AtomicReference<>();
      final AtomicReference<String> after = new AtomicReference<>("unset");
      final Thread[] started = new Thread[1];
      started[0] =
          new Thread(
              () -> {
                runtime.enterSession(started[0]);
                during.set(runtime.currentSessionId());
                runtime.exitSession(started[0]);
                after.set(runtime.currentSessionId());
              },
              "started");
      try (ChaosSession session = runtime.openSession("handoff")) {
        runtime.beforeThreadStart(started[0], false);
        started[0].start();
        started[0].join(10_000L);

        assertThat(during.get()).isEqualTo(session.id());
        assertThat(after.get()).isNull();
      }
    }

    @Test
    @DisplayName("a pool worker started in a session does not inherit it")
    void poolWorkerDoesNotInherit() throws Throwable {
      final AtomicReference<String> during = new AtomicReference<>("unset");
      final Thread[] worker = new Thread[1];
      worker[0] =
          new Thread(
              () -> {
                runtime.enterSession(worker[0]);
                during.set(runtime.currentSessionId());
                runtime.exitSession(worker[0]);
              },
              "pool-worker");
      try (ChaosSession session = runtime.openSession("handoff")) {
        runtime.beforeThreadStart(worker[0], true);
        worker[0].start();
        worker[0].join(10_000L);

        assertThat(during.get()).isNull();
      }
    }

    @Test
    @DisplayName("a thread's session is bound only on that thread itself")
    void threadSessionBindsOnlyOnItself() throws Throwable {
      final Thread notStarted = new Thread(() -> {}, "not-started");
      final AtomicReference<String> elsewhere = new AtomicReference<>("unset");
      try (ChaosSession session = runtime.openSession("handoff")) {
        runtime.beforeThreadStart(notStarted, false);
        onOtherThread(
            () -> {
              runtime.enterSession(notStarted);
              elsewhere.set(runtime.currentSessionId());
              runtime.exitSession(notStarted);
            });

        assertThat(elsewhere.get()).isNull();
      }
    }

    @Test
    @DisplayName("a captured task runs in the capturing session on another thread")
    void taskCarriesTheSession() throws Throwable {
      final Object task = new Object();
      final AtomicReference<String> during = new AtomicReference<>();
      final AtomicReference<String> after = new AtomicReference<>("unset");
      try (ChaosSession session = runtime.openSession("handoff")) {
        runtime.captureSession(task);
        onOtherThread(
            () -> {
              runtime.enterSession(task);
              during.set(runtime.currentSessionId());
              runtime.exitSession(task);
              after.set(runtime.currentSessionId());
            });

        assertThat(during.get()).isEqualTo(session.id());
        assertThat(after.get()).isNull();
      }
    }
  }

  @Nested
  @DisplayName("session wrappers of executor submissions")
  class Wrappers {

    /** A task is wrapped for its session only once the session has a scenario. */
    private void activateInSession(final ChaosSession session) {
      session.activate(
          ChaosScenario.builder("session-has-scenario")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());
    }

    @Test
    @DisplayName("a callable submitted in a session runs in it on another thread")
    void callableRunsInTheSession() throws Throwable {
      final AtomicReference<String> during = new AtomicReference<>();
      final AtomicReference<Object> result = new AtomicReference<>();
      final AtomicReference<String> after = new AtomicReference<>("unset");
      try (ChaosSession session = runtime.openSession("wrap")) {
        activateInSession(session);
        final Callable<String> submitted =
            runtime.decorateExecutorCallable(
                "EXECUTOR_SUBMIT",
                this,
                () -> {
                  during.set(runtime.currentSessionId());
                  return "value";
                });
        onOtherThread(
            () -> {
              try {
                result.set(submitted.call());
              } catch (final Exception failure) {
                result.set(failure);
              }
              after.set(runtime.currentSessionId());
            });

        assertThat(result.get()).isEqualTo("value");
        assertThat(during.get()).isEqualTo(session.id());
        assertThat(after.get()).isNull();
      }
    }

    @Test
    @DisplayName("a FutureTask submitted in a session stays a Future that forwards to the task")
    void futureTaskWrapperForwards() throws Throwable {
      final AtomicReference<String> during = new AtomicReference<>();
      final FutureTask<String> task =
          new FutureTask<>(
              () -> {
                during.set(runtime.currentSessionId());
                return "value";
              });
      final FutureTask<String> cancelled = new FutureTask<>(() -> "never");
      try (ChaosSession session = runtime.openSession("wrap")) {
        activateInSession(session);
        final Runnable submitted = runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, task);
        final Runnable toCancel =
            runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, cancelled);
        assertThat(submitted).isInstanceOf(RunnableFuture.class);
        final RunnableFuture<?> future = (RunnableFuture<?>) submitted;
        assertThat(future.isDone()).isFalse();

        onOtherThread(submitted);

        assertThat(during.get()).isEqualTo(session.id());
        assertThat(future.isDone()).isTrue();
        assertThat(future.isCancelled()).isFalse();
        assertThat(future.get()).isEqualTo("value");
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo("value");
        assertThat(future.cancel(true)).as("a completed task cannot be cancelled").isFalse();

        final RunnableFuture<?> other = (RunnableFuture<?>) toCancel;
        assertThat(other.cancel(false)).isTrue();
        assertThat(other.isCancelled()).isTrue();
        assertThat(cancelled.isCancelled()).isTrue();
      }
    }

    @Test
    @DisplayName("a pool worker evaluates a wrapped task in the session it was submitted under")
    void workerRunSeesTheSubmittersSession() throws Throwable {
      final FutureTask<String> task = new FutureTask<>(new Charge());
      try (ChaosSession session = runtime.openSession("wrap")) {
        final com.macstab.chaos.jvm.api.ChaosActivationHandle handle =
            session.activate(
                ChaosScenario.builder("worker")
                    .scope(ChaosScenario.ScenarioScope.SESSION)
                    .selector(
                        new ChaosSelector.ExecutorSelector(
                            Set.of(OperationType.EXECUTOR_WORKER_RUN),
                            NamePattern.any(),
                            NamePattern.exact(FutureTask.class.getName()),
                            null))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build());
        final Runnable submitted = runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, task);

        try (ChaosSession.ScopeBinding outside = runtime.bindSession(null)) {
          assertThat(runtime.currentSessionId()).isNull();
          runtime.beforeWorkerRun(this, null, submitted);
          assertThat(handle.appliedCount()).isEqualTo(1L);
          runtime.beforeWorkerRun(this, null, task);
          assertThat(handle.appliedCount()).as("the bare task carries no session").isEqualTo(1L);
        }
      }
    }

    @Test
    @DisplayName("a scheduled tick evaluates a wrapped callable in its session, by its own class")
    void scheduledTickSeesTheCallablesSession() throws Throwable {
      try (ChaosSession session = runtime.openSession("wrap")) {
        session.activate(
            ChaosScenario.builder("tick")
                .scope(ChaosScenario.ScenarioScope.SESSION)
                .selector(
                    new ChaosSelector.SchedulingSelector(
                        Set.of(OperationType.SCHEDULE_TICK), NamePattern.any(), null))
                .effect(ChaosEffect.suppress())
                .activationPolicy(ActivationPolicy.always())
                .build());
        final Callable<String> submitted =
            runtime.decorateExecutorCallable("EXECUTOR_SUBMIT", this, new Charge());

        try (ChaosSession.ScopeBinding outside = runtime.bindSession(null)) {
          assertThat(runtime.beforeScheduledTick(this, submitted, false)).isFalse();
          assertThat(runtime.beforeScheduledTick(this, new Charge(), false)).isTrue();
        }
      }
    }
  }

  /** An application task whose class a selector can name. */
  static final class Charge implements Callable<String> {
    @Override
    public String call() {
      return "charged";
    }
  }
}

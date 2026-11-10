package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;

final class ExecutorAdvice {
  private ExecutorAdvice() {}

  static final class ExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task)
        throws Throwable {
      task =
          BootstrapDispatcher.decorateExecutorRunnable(
              OperationTags.EXECUTOR_SUBMIT, executor, task);
    }
  }

  /**
   * {@code execute(Runnable)}, {@code submit(Runnable)} and {@code submit(Runnable, T)} of a {@code
   * ScheduledThreadPoolExecutor} or {@code ThreadPerTaskExecutor} (EXECUTOR_SUBMIT): evaluated by
   * the outermost call only ({@link BootstrapDispatcher#SCOPE_EXECUTOR_SUBMIT}).
   */
  static final class SubmitRunnableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task)
        throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_EXECUTOR_SUBMIT)) {
        return;
      }
      try {
        task =
            BootstrapDispatcher.decorateExecutorRunnable(
                OperationTags.EXECUTOR_SUBMIT, executor, task);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_EXECUTOR_SUBMIT);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_EXECUTOR_SUBMIT);
    }
  }

  /** {@code submit(Callable)} of the same executors (see {@link SubmitRunnableAdvice}). */
  static final class SubmitCallableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Callable<?> task)
        throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_EXECUTOR_SUBMIT)) {
        return;
      }
      try {
        task =
            BootstrapDispatcher.decorateExecutorCallable(
                OperationTags.EXECUTOR_SUBMIT, executor, task);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_EXECUTOR_SUBMIT);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_EXECUTOR_SUBMIT);
    }
  }

  /**
   * {@code beforeExecute(Thread, Runnable)} of {@code ThreadPoolExecutor} and of every subclass
   * that overrides it (Spring's executors do, without calling {@code super}): one {@code
   * EXECUTOR_WORKER_RUN} per task ({@link BootstrapDispatcher#SCOPE_WORKER_RUN}).
   */
  static final class BeforeExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(0) final Thread worker,
        @Advice.Argument(1) final Runnable task)
        throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_WORKER_RUN)) {
        return;
      }
      try {
        BootstrapDispatcher.beforeWorkerRun(executor, worker, task);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_WORKER_RUN);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_WORKER_RUN);
    }
  }

  static final class ShutdownAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object executor) throws Throwable {
      BootstrapDispatcher.beforeExecutorShutdown(OperationTags.EXECUTOR_SHUTDOWN, executor, 0L);
    }
  }

  /**
   * Advice for {@code ExecutorService.shutdownNow()}. The previous installer wired only {@code
   * shutdown()}, leaving {@code shutdownNow()} — the variant that interrupts in-flight tasks and
   * returns the queue of never-executed runnables — completely unintercepted. Chaos scenarios
   * targeting shutdown semantics (fault-inject on orderly shutdown, suppress forced shutdown to
   * observe pool drain behaviour) would silently skip any service whose shutdown path went through
   * {@code shutdownNow()}. Route both variants through the same operation tag so a single selector
   * covers the full shutdown surface.
   */
  static final class ShutdownNowAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object executor) throws Throwable {
      BootstrapDispatcher.beforeExecutorShutdown(OperationTags.EXECUTOR_SHUTDOWN, executor, 0L);
    }
  }

  static final class AwaitTerminationAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(0) final long timeout,
        @Advice.Argument(1) final TimeUnit unit)
        throws Throwable {
      // Dispatcher contract for beforeExecutorShutdown expects the timeout in milliseconds.
      // Reading the raw long argument without converting via the TimeUnit silently reports a
      // timeout that's off by 10^3/10^6/10^9 whenever the caller used SECONDS / MICROS / NANOS.
      BootstrapDispatcher.beforeExecutorShutdown(
          OperationTags.EXECUTOR_AWAIT_TERMINATION, executor, unit.toMillis(timeout));
    }
  }
}

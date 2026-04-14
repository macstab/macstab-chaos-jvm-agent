package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;

final class ExecutorAdvice {
  private ExecutorAdvice() {}

  static final class ExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This Object executor, @Advice.Argument(value = 0, readOnly = false) Runnable task)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", executor, task);
    }
  }

  static final class SubmitRunnableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This Object executor, @Advice.Argument(value = 0, readOnly = false) Runnable task)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", executor, task);
    }
  }

  static final class SubmitCallableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This Object executor,
        @Advice.Argument(value = 0, readOnly = false) Callable<?> task)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorCallable("EXECUTOR_SUBMIT", executor, task);
    }
  }

  static final class BeforeExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This Object executor,
        @Advice.Argument(0) Thread worker,
        @Advice.Argument(1) Runnable task)
        throws Throwable {
      BootstrapDispatcher.beforeWorkerRun(executor, worker, task);
    }
  }

  static final class ShutdownAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object executor) throws Throwable {
      BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", executor, 0L);
    }
  }

  static final class AwaitTerminationAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object executor, @Advice.Argument(0) long timeout)
        throws Throwable {
      BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_AWAIT_TERMINATION", executor, timeout);
    }
  }
}

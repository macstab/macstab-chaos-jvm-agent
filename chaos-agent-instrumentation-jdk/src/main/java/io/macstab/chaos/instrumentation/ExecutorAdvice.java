package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

final class ExecutorAdvice {
  private ExecutorAdvice() {}

  static final class ExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", executor, task);
    }
  }

  static final class BeforeExecuteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(0) final Thread worker,
        @Advice.Argument(1) final Runnable task)
        throws Throwable {
      BootstrapDispatcher.beforeWorkerRun(executor, worker, task);
    }
  }

  static final class ShutdownAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object executor) throws Throwable {
      BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", executor, 0L);
    }
  }

  static final class AwaitTerminationAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object executor, @Advice.Argument(0) final long timeout)
        throws Throwable {
      BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_AWAIT_TERMINATION", executor, timeout);
    }
  }
}

package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
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
      BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", executor, 0L);
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
          "EXECUTOR_AWAIT_TERMINATION", executor, unit.toMillis(timeout));
    }
  }
}

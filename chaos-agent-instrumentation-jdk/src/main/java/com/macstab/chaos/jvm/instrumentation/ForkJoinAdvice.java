package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

final class ForkJoinAdvice {
  private ForkJoinAdvice() {}

  /**
   * Runs the task in the session it was submitted or forked in (bound before the run is evaluated,
   * released when it ends), then evaluates {@code FORK_JOIN_TASK_RUN}.
   */
  static final class DoExecAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final ForkJoinTask<?> task) throws Throwable {
      BootstrapDispatcher.enterSession(task);
      try {
        BootstrapDispatcher.beforeForkJoinTaskRun(task);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitSession(task);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.This final ForkJoinTask<?> task) {
      BootstrapDispatcher.exitSession(task);
    }
  }

  /** {@code ForkJoinTask.fork()}: the forked task runs in the forking thread's session. */
  static final class ForkAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final ForkJoinTask<?> task) {
      BootstrapDispatcher.captureSession(task);
    }
  }

  /**
   * {@code ForkJoinPool.poolSubmit(boolean, ForkJoinTask)}, the funnel of external submissions
   * ({@code execute}, {@code submit}, {@code invoke}, and so {@code CompletableFuture.*Async}): the
   * task runs in the submitting thread's session.
   */
  static final class PoolSubmitAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(1) final ForkJoinTask<?> task) {
      BootstrapDispatcher.captureSession(task);
    }
  }
}

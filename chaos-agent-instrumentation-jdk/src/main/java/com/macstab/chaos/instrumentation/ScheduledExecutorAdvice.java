package com.macstab.chaos.instrumentation;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;

final class ScheduledExecutorAdvice {
  private ScheduledExecutorAdvice() {}

  static final class ScheduleRunnableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task,
        @Advice.Argument(value = 1, readOnly = false) long delay)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("SCHEDULE_SUBMIT", executor, task);
      // Capture the decorated (but not yet wrapped) task so adjustScheduleDelay sees the
      // original task class name in the InvocationContext, not ScheduledRunnableWrapper.
      final Runnable taskForDelay = task;
      task = new ScheduledRunnableWrapper(executor, task, false);
      delay =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, taskForDelay, delay, false);
    }
  }

  static final class ScheduleCallableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Callable<?> task,
        @Advice.Argument(value = 1, readOnly = false) long delay)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorCallable("SCHEDULE_SUBMIT", executor, task);
      final Callable<?> taskForDelay = task;
      task = new ScheduledCallableWrapper<>(executor, task);
      delay =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, taskForDelay, delay, false);
    }
  }

  static final class PeriodicAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task,
        @Advice.Argument(value = 1, readOnly = false) long initialDelay)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("SCHEDULE_SUBMIT", executor, task);
      final Runnable taskForDelay = task;
      task = new ScheduledRunnableWrapper(executor, task, true);
      initialDelay =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, taskForDelay, initialDelay, true);
    }
  }
}

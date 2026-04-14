package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;

final class ScheduledExecutorAdvice {
  private ScheduledExecutorAdvice() {}

  static final class ScheduleRunnableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task,
        @Advice.Argument(value = 1, readOnly = false) long delay)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("SCHEDULE_SUBMIT", executor, task);
      task = new ScheduledRunnableWrapper(executor, task, false);
      delay =
          BootstrapDispatcher.adjustScheduleDelay("SCHEDULE_SUBMIT", executor, task, delay, false);
    }
  }

  static final class ScheduleCallableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This Object executor,
        @Advice.Argument(value = 0, readOnly = false) Callable<?> task,
        @Advice.Argument(value = 1, readOnly = false) long delay)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorCallable("SCHEDULE_SUBMIT", executor, task);
      task = new ScheduledCallableWrapper<>(executor, task);
      delay =
          BootstrapDispatcher.adjustScheduleDelay("SCHEDULE_SUBMIT", executor, task, delay, false);
    }
  }

  static final class PeriodicAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task,
        @Advice.Argument(value = 1, readOnly = false) long initialDelay)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("SCHEDULE_SUBMIT", executor, task);
      task = new ScheduledRunnableWrapper(executor, task, true);
      initialDelay =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, task, initialDelay, true);
    }
  }
}

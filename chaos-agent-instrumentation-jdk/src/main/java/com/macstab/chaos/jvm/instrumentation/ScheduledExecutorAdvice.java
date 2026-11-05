package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code ScheduledThreadPoolExecutor.schedule}, {@code scheduleAtFixedRate} and {@code
 * scheduleWithFixedDelay}.
 *
 * <p>Each schedule call is evaluated exactly once, for {@code SCHEDULE_SUBMIT}, by the
 * decorateExecutor* dispatch: a delay or gate blocks the scheduling thread (the registration is
 * delayed, as for {@code EXECUTOR_SUBMIT}), a reject throws from {@code schedule}, and a suppress
 * replaces the task with a no-op. The task is then wrapped so each run is evaluated for {@code
 * SCHEDULE_TICK}.
 *
 * <p>The caller's delay, period and {@link java.util.concurrent.TimeUnit} are never rewritten:
 * normalising to milliseconds would turn sub-millisecond schedules into 1 ms schedules even with no
 * scenario active. SCHEDULE_SUBMIT is evaluated once, so its delay is applied once.
 */
final class ScheduledExecutorAdvice {
  private ScheduledExecutorAdvice() {}

  static final class ScheduleRunnableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task)
        throws Throwable {
      task =
          BootstrapDispatcher.decorateExecutorRunnable(
              OperationTags.SCHEDULE_SUBMIT, executor, task);
      task = new ScheduledRunnableWrapper(executor, task, false);
    }
  }

  static final class ScheduleCallableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Callable<?> task)
        throws Throwable {
      task =
          BootstrapDispatcher.decorateExecutorCallable(
              OperationTags.SCHEDULE_SUBMIT, executor, task);
      task = new ScheduledCallableWrapper<>(executor, task);
    }
  }

  static final class PeriodicAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task)
        throws Throwable {
      task =
          BootstrapDispatcher.decorateExecutorRunnable(
              OperationTags.SCHEDULE_SUBMIT, executor, task);
      task = new ScheduledRunnableWrapper(executor, task, true);
    }
  }
}

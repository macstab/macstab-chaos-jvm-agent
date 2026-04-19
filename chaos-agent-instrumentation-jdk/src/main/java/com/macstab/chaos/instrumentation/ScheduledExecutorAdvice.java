package com.macstab.chaos.instrumentation;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;

final class ScheduledExecutorAdvice {
  private ScheduledExecutorAdvice() {}

  static final class ScheduleRunnableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task,
        @Advice.Argument(value = 1, readOnly = false) long delay,
        @Advice.Argument(value = 2, readOnly = false) TimeUnit unit)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("SCHEDULE_SUBMIT", executor, task);
      // Capture the decorated (but not yet wrapped) task so adjustScheduleDelay sees the
      // original task class name in the InvocationContext, not ScheduledRunnableWrapper.
      final Runnable taskForDelay = task;
      task = new ScheduledRunnableWrapper(executor, task, false);
      // Dispatcher speaks in milliseconds only; normalize the caller's unit before dispatch
      // and rewrite both arguments so the executor interprets the adjusted value as millis.
      // Without this, schedule(r, 5, SECONDS) would hand 5 to the dispatcher (treated as 5ms),
      // then the returned millis would be written back but interpreted as 5 seconds upstream.
      final long delayMillis = unit.toMillis(delay);
      delay =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, taskForDelay, delayMillis, false);
      unit = TimeUnit.MILLISECONDS;
    }
  }

  static final class ScheduleCallableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Callable<?> task,
        @Advice.Argument(value = 1, readOnly = false) long delay,
        @Advice.Argument(value = 2, readOnly = false) TimeUnit unit)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorCallable("SCHEDULE_SUBMIT", executor, task);
      final Callable<?> taskForDelay = task;
      task = new ScheduledCallableWrapper<>(executor, task);
      final long delayMillis = unit.toMillis(delay);
      delay =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, taskForDelay, delayMillis, false);
      unit = TimeUnit.MILLISECONDS;
    }
  }

  static final class PeriodicAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task,
        @Advice.Argument(value = 1, readOnly = false) long initialDelay,
        @Advice.Argument(value = 2, readOnly = false) long period,
        @Advice.Argument(value = 3, readOnly = false) TimeUnit unit)
        throws Throwable {
      task = BootstrapDispatcher.decorateExecutorRunnable("SCHEDULE_SUBMIT", executor, task);
      final Runnable taskForDelay = task;
      task = new ScheduledRunnableWrapper(executor, task, true);
      // Period must be normalized alongside initialDelay because we rewrite the unit to
      // MILLISECONDS — leaving period in the original unit would multiply or divide the
      // repeat interval by a factor of 10^3/10^6/10^9 depending on the caller's unit.
      final long initialMillis = unit.toMillis(initialDelay);
      final long periodMillis = unit.toMillis(period);
      initialDelay =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, taskForDelay, initialMillis, true);
      // Route period through the same adjustment pipeline so a scenario that extends (or
      // compresses) scheduling affects every repeat, not just the initial fire. Previously the
      // raw normalised millis was assigned unchanged — scenarios targeting scheduling chaos
      // applied once on first tick and were silently ignored for every subsequent repeat.
      period =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, taskForDelay, periodMillis, true);
      unit = TimeUnit.MILLISECONDS;
    }
  }
}

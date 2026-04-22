package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;

final class ScheduledExecutorAdvice {
  private ScheduledExecutorAdvice() {}

  // NOTE ON SUB-MILLISECOND CLAMPING: Every enter() advice below computes
  //   long millis = unit.toMillis(delay);
  //   if (delay > 0 && millis <= 0) millis = 1;
  // rather than delegating to a private helper method on this class. ByteBuddy inlines the
  // advice method body into the target bytecode, but a call to a static method on
  // ScheduledExecutorAdvice stays in the bytecode as an INVOKESTATIC whose owner class must
  // resolve through the instrumented class's classloader — the bootstrap loader for
  // ScheduledThreadPoolExecutor. Only BootstrapDispatcher and the two wrappers are in the
  // injected bridge JAR; the advice class itself is not, so any helper call produces a
  // NoClassDefFoundError at runtime. Keep the clamp inline in each advice.
  //
  // The clamp is necessary because unit.toMillis() truncates: schedule(task, 500, NANOSECONDS)
  // normalises to 0 ms, which the scheduler would then treat as "fire immediately", silently
  // converting a deferred schedule into an immediate one. 1 ms is the coarsest non-zero value
  // we can still represent after the unit rewrite, and it preserves the "caller asked for a
  // delay" semantic.

  static final class ScheduleRunnableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task,
        @Advice.Argument(value = 1, readOnly = false) long delay,
        @Advice.Argument(value = 2, readOnly = false) TimeUnit unit)
        throws Throwable {
      // Capture BEFORE decoration so adjustScheduleDelay sees the application's task class
      // name in the InvocationContext, not the decorator or ScheduledRunnableWrapper wrapper.
      final Runnable taskForDelay = task;
      task = BootstrapDispatcher.decorateExecutorRunnable("SCHEDULE_SUBMIT", executor, task);
      task = new ScheduledRunnableWrapper(executor, task, false);
      // Dispatcher speaks in milliseconds only; normalize the caller's unit before dispatch
      // and rewrite both arguments so the executor interprets the adjusted value as millis.
      // Without this, schedule(r, 5, SECONDS) would hand 5 to the dispatcher (treated as 5ms),
      // then the returned millis would be written back but interpreted as 5 seconds upstream.
      long delayMillis = unit.toMillis(delay);
      if (delay > 0L && delayMillis <= 0L) {
        delayMillis = 1L;
      }
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
      long delayMillis = unit.toMillis(delay);
      if (delay > 0L && delayMillis <= 0L) {
        delayMillis = 1L;
      }
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
      final Runnable taskForDelay = task;
      task = BootstrapDispatcher.decorateExecutorRunnable("SCHEDULE_SUBMIT", executor, task);
      task = new ScheduledRunnableWrapper(executor, task, true);
      // Period must be normalized alongside initialDelay because we rewrite the unit to
      // MILLISECONDS — leaving period in the original unit would multiply or divide the
      // repeat interval by a factor of 10^3/10^6/10^9 depending on the caller's unit. Clamp
      // sub-millisecond period values to 1 ms as well: a period of 0 would cause the STPE
      // to busy-loop on the same tick with no back-pressure.
      long initialMillis = unit.toMillis(initialDelay);
      if (initialDelay > 0L && initialMillis <= 0L) {
        initialMillis = 1L;
      }
      long periodMillis = unit.toMillis(period);
      if (period > 0L && periodMillis <= 0L) {
        periodMillis = 1L;
      }
      initialDelay =
          BootstrapDispatcher.adjustScheduleDelay(
              "SCHEDULE_SUBMIT", executor, taskForDelay, initialMillis, true);
      // Only adjust initialDelay. adjustScheduleDelay routes through a stateful evaluate():
      // matchedCount/appliedCount increment, rate-limit/probability sampling, APPLIED event
      // emission, and maxApplications CAS all fire on each call. A second invocation for
      // `period` within the same schedule(...) makes one caller-visible scheduling decision
      // count as two scenario ticks — capped scenarios run out twice as fast, probability rolls
      // disagree between initial and period, and observability double-emits. Period passes
      // through as normalised millis; per-repeat chaos belongs on the task's run() path, not
      // on re-invoking the schedule-time decision per tick.
      period = periodMillis;
      unit = TimeUnit.MILLISECONDS;
    }
  }
}

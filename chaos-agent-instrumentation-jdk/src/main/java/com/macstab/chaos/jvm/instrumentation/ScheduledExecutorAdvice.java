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
 * SCHEDULE_TICK}. The {@code schedule} an STPE's own {@code execute} / {@code submit} makes is not
 * evaluated: that call is the {@code EXECUTOR_SUBMIT} ({@link
 * BootstrapDispatcher#SCOPE_EXECUTOR_SUBMIT}), whose decoration already carries the session; the
 * task is still wrapped for {@code SCHEDULE_TICK}.
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
      if (!BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_EXECUTOR_SUBMIT)) {
        task =
            BootstrapDispatcher.decorateExecutorRunnable(
                OperationTags.SCHEDULE_SUBMIT, executor, task);
      }
      task = new ScheduledRunnableWrapper(executor, task, false);
    }
  }

  static final class ScheduleCallableAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object executor,
        @Advice.Argument(value = 0, readOnly = false) Callable<?> task)
        throws Throwable {
      if (!BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_EXECUTOR_SUBMIT)) {
        task =
            BootstrapDispatcher.decorateExecutorCallable(
                OperationTags.SCHEDULE_SUBMIT, executor, task);
      }
      task = new ScheduledCallableWrapper<>(executor, task);
    }
  }

  /**
   * JDK 21: a scope-only bracket of {@code CompletableFuture$Delayer.delay(Runnable, long,
   * TimeUnit)} when it schedules a JDK timer ({@code $Timeout}, {@code $DelayedCompleter}): that
   * {@code SCHEDULE_SUBMIT} is the JDK's own ({@link BootstrapDispatcher#SCOPE_JDK_INTERNAL}). A
   * {@code delayedExecutor} task ({@code $TaskSubmitter}) stays evaluated.
   */
  static final class JdkTimerDelayAdvice {
    @Advice.OnMethodEnter
    static boolean enter(@Advice.Argument(0) final Runnable command) {
      if (command == null) {
        return false;
      }
      final String name = command.getClass().getName();
      if (name.equals("java.util.concurrent.CompletableFuture$Timeout")
          || name.equals("java.util.concurrent.CompletableFuture$DelayedCompleter")) {
        BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JDK_INTERNAL);
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final boolean entered) {
      if (entered) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JDK_INTERNAL);
      }
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

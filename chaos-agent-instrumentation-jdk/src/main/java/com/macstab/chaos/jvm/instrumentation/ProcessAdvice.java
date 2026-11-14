package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} classes for process starts and waits ({@code PROCESS_START}, {@code
 * PROCESS_WAIT}).
 *
 * <p>Each nested class targets one method or overload family. The outer class is not instantiated;
 * it exists only as a namespace.
 */
final class ProcessAdvice {
  private ProcessAdvice() {}

  /** Intercepts {@code ProcessImpl.waitFor()} (PROCESS_WAIT): delays or rejects the wait. */
  static final class ProcessWaitAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object process) throws Throwable {
      BootstrapDispatcher.beforeProcessWait(process, 0L);
    }
  }

  /**
   * Intercepts {@code ProcessImpl.waitFor(long, TimeUnit)} (PROCESS_WAIT): a timeout waits the
   * timeout out and reports that the child has not exited.
   */
  static final class ProcessTimedWaitAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object process,
        @Advice.Argument(0) final long timeout,
        @Advice.Argument(1) final java.util.concurrent.TimeUnit unit)
        throws Throwable {
      // waitFor(0, unit) does not wait: no timeout to wait out (-1).
      return BootstrapDispatcher.beforeProcessWait(
          process, unit == null || timeout <= 0 ? -1L : Math.max(1L, unit.toMillis(timeout)));
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean timedOut, @Advice.Return(readOnly = false) boolean exited) {
      if (timedOut) {
        exited = false;
      }
    }
  }

  /**
   * Intercepts {@code ProcessBuilder.start(Redirect[])}: every process start, evaluated at entry;
   * on return the started process is recorded with its program, so its wait is matched by the same
   * name. The receiver is declared as {@code Object}: an advice signature naming {@code
   * ProcessBuilder} makes ByteBuddy resolve that class while it is being loaded and transformed
   * (ClassCircularityError).
   */
  static final class ProcessStartAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object builder) throws Throwable {
      final java.util.List<String> command = ((ProcessBuilder) builder).command();
      BootstrapDispatcher.beforeProcessStart(command.isEmpty() ? null : command.get(0));
    }

    /** Records the program the child was started as, for its {@code PROCESS_WAIT}. */
    @Advice.OnMethodExit
    static void exit(@Advice.This final Object builder, @Advice.Return final Object process) {
      final java.util.List<String> command = ((ProcessBuilder) builder).command();
      BootstrapDispatcher.afterProcessStart(process, command.isEmpty() ? null : command.get(0));
    }
  }
}

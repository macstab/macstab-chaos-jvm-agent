package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

final class ForkJoinAdvice {
  private ForkJoinAdvice() {}

  static final class DoExecAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final ForkJoinTask<?> task) throws Throwable {
      BootstrapDispatcher.beforeForkJoinTaskRun(task);
    }
  }
}

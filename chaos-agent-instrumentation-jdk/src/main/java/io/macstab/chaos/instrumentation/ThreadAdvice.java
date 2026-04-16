package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

final class ThreadAdvice {
  private ThreadAdvice() {}

  static final class StartAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Thread thread) throws Throwable {
      BootstrapDispatcher.beforeThreadStart(thread);
    }
  }
}

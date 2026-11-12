package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

final class ShutdownAdvice {
  private ShutdownAdvice() {}

  static final class AddShutdownHookAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) Thread hook) throws Throwable {
      BootstrapDispatcher.decorateShutdownHook(hook);
    }
  }
}

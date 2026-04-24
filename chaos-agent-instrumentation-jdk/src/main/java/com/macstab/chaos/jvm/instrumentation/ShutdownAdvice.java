package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

final class ShutdownAdvice {
  private ShutdownAdvice() {}

  static final class AddShutdownHookAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(value = 0, readOnly = false) Thread hook) throws Throwable {
      hook = BootstrapDispatcher.decorateShutdownHook(hook);
    }
  }

  static final class RemoveShutdownHookAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(value = 0, readOnly = false) Thread hook) {
      hook = BootstrapDispatcher.resolveShutdownHook(hook);
    }
  }
}

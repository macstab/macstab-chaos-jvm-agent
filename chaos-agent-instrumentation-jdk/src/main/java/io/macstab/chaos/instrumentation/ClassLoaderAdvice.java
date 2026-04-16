package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.net.URL;
import net.bytebuddy.asm.Advice;

final class ClassLoaderAdvice {
  private ClassLoaderAdvice() {}

  static final class LoadClassAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final ClassLoader loader, @Advice.Argument(0) final String className)
        throws Throwable {
      BootstrapDispatcher.beforeClassLoad(loader, className);
    }
  }

  static final class GetResourceAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final ClassLoader loader,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) URL returned)
        throws Throwable {
      returned = BootstrapDispatcher.afterResourceLookup(loader, name, returned);
    }
  }
}

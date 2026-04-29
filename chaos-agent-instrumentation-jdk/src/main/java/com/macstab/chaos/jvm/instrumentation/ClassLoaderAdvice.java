package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
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
    // onThrowable = Throwable.class causes exit to fire on the exceptional path too. Without the
    // @Advice.Thrown guard, BootstrapDispatcher.afterResourceLookup would run even when
    // getResource() failed — and if the dispatcher itself throws (e.g. chaos-injected), ByteBuddy
    // replaces the original exception with the dispatcher's, erasing the legitimate cause from
    // the stack trace. Skip the dispatcher when the original call threw; the @Advice.Return
    // mutation is also meaningless on the exceptional path.
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final ClassLoader loader,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) URL returned,
        @Advice.Thrown final Throwable thrown)
        throws Throwable {
      if (thrown != null) {
        return;
      }
      returned = BootstrapDispatcher.afterResourceLookup(loader, name, returned);
    }
  }
}

package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.net.URL;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code ClassLoader.loadClass} and {@code ClassLoader.getResource}.
 *
 * <p>Both operations delegate: {@code loadClass(String)} calls {@code loadClass(String, boolean)},
 * and both walk the parent chain ({@code parent.loadClass(name, false)}, {@code
 * parent.getResource(name)}). The advice brackets each call with a call-depth scope so that one
 * application-level load or lookup is evaluated once, by the outermost loader.
 */
final class ClassLoaderAdvice {
  private ClassLoaderAdvice() {}

  static final class LoadClassAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final ClassLoader loader, @Advice.Argument(0) final String className)
        throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLASS_LOAD)) {
        try {
          BootstrapDispatcher.beforeClassLoad(loader, className);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLASS_LOAD);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLASS_LOAD);
    }
  }

  static final class GetResourceAdvice {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_RESOURCE_LOAD);
    }

    // The scope is left first, so a throwing dispatcher cannot leave the counter raised. Only the
    // outermost lookup (the one whose exit brings the scope back to zero) is evaluated; nested
    // parent lookups return their result unchanged. When the original call threw, the dispatcher
    // is skipped: running it would let a chaos-injected exception replace the legitimate cause,
    // and the return-value mutation is meaningless on the exceptional path.
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final ClassLoader loader,
        @Advice.Argument(0) final String name,
        @Advice.Return(readOnly = false) URL returned,
        @Advice.Thrown final Throwable thrown)
        throws Throwable {
      if (BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_RESOURCE_LOAD)
          && thrown == null) {
        returned = BootstrapDispatcher.afterResourceLookup(loader, name, returned);
      }
    }
  }
}

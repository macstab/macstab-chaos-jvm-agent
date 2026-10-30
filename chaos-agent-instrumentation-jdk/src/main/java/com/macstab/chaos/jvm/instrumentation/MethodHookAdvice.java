package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Advice woven into the methods a {@code MethodSelector} names (see {@link MethodWeaver}). The
 * declaring class, method name and descriptor are constants of the woven method, so the advice
 * reads no reflection per call.
 */
final class MethodHookAdvice {
  private MethodHookAdvice() {}

  /** Raises {@code METHOD_ENTER} before the method body runs. */
  static final class Enter {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.Origin final Class<?> declaringType,
        @Advice.Origin("#m") final String methodName,
        @Advice.Origin("#d") final String descriptor)
        throws Throwable {
      BootstrapDispatcher.beforeMethodEnter(declaringType, methodName, descriptor);
    }
  }

  /** Raises {@code METHOD_EXIT} on a normal return and lets the result be replaced. */
  static final class Exit {
    @Advice.OnMethodExit
    static void exit(
        @Advice.Origin final Class<?> declaringType,
        @Advice.Origin("#m") final String methodName,
        @Advice.Origin("#d") final String descriptor,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned)
        throws Throwable {
      returned =
          BootstrapDispatcher.afterMethodExit(declaringType, methodName, descriptor, returned);
    }
  }

  /** Raises {@code METHOD_EXIT} on a normal return of a {@code void} method. */
  static final class VoidExit {
    @Advice.OnMethodExit
    static void exit(
        @Advice.Origin final Class<?> declaringType,
        @Advice.Origin("#m") final String methodName,
        @Advice.Origin("#d") final String descriptor)
        throws Throwable {
      BootstrapDispatcher.afterMethodExit(declaringType, methodName, descriptor, null);
    }
  }
}

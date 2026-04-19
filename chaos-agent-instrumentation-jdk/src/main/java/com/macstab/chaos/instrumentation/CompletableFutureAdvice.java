package com.macstab.chaos.instrumentation;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

final class CompletableFutureAdvice {
  private CompletableFutureAdvice() {}

  static final class CompleteAdvice {
    // skipOn = OnNonDefaultValue: a non-null Boolean from the dispatcher means the scenario has
    // decided the outcome (reject → FALSE, or completeExceptionally already transitioned the
    // future), so the real complete() body must NOT run. Without skipOn the future would be
    // completed with `payload` even when the scenario explicitly rejected the call, making the
    // "suppress" contract a lie.
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static Boolean enter(
        @Advice.This final CompletableFuture<?> future, @Advice.Argument(0) final Object payload)
        throws Throwable {
      return BootstrapDispatcher.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, payload);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final Boolean decision, @Advice.Return(readOnly = false) boolean returned) {
      if (decision != null) {
        returned = decision;
      }
    }
  }

  static final class CompleteExceptionallyAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static Boolean enter(
        @Advice.This final CompletableFuture<?> future, @Advice.Argument(0) final Throwable payload)
        throws Throwable {
      return BootstrapDispatcher.beforeCompletableFutureComplete(
          "ASYNC_COMPLETE_EXCEPTIONALLY", future, payload);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final Boolean decision, @Advice.Return(readOnly = false) boolean returned) {
      if (decision != null) {
        returned = decision;
      }
    }
  }
}

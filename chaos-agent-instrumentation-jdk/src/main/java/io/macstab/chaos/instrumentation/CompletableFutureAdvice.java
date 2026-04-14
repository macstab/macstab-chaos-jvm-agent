package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

final class CompletableFutureAdvice {
  private CompletableFutureAdvice() {}

  static final class CompleteAdvice {
    @Advice.OnMethodEnter
    static Boolean enter(
        @Advice.This CompletableFuture<?> future, @Advice.Argument(0) Object payload)
        throws Throwable {
      return BootstrapDispatcher.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, payload);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter Boolean decision, @Advice.Return(readOnly = false) boolean returned) {
      if (decision != null) {
        returned = decision;
      }
    }
  }

  static final class CompleteExceptionallyAdvice {
    @Advice.OnMethodEnter
    static Boolean enter(
        @Advice.This CompletableFuture<?> future, @Advice.Argument(0) Throwable payload)
        throws Throwable {
      return BootstrapDispatcher.beforeCompletableFutureComplete(
          "ASYNC_COMPLETE_EXCEPTIONALLY", future, payload);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter Boolean decision, @Advice.Return(readOnly = false) boolean returned) {
      if (decision != null) {
        returned = decision;
      }
    }
  }
}

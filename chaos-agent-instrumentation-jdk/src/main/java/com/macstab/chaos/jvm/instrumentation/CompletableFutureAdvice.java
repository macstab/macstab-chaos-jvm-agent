package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import org.jspecify.annotations.Nullable;

final class CompletableFutureAdvice {
  private CompletableFutureAdvice() {}

  static final class CompleteAdvice {
    // skipOn = OnNonDefaultValue: a non-null Boolean from the dispatcher means the scenario has
    // decided the outcome (reject → FALSE, or completeExceptionally already transitioned the
    // future), so the real complete() body must NOT run. Without skipOn the future would be
    // completed with `payload` even when the scenario explicitly rejected the call, making the
    // "suppress" contract a lie.
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Boolean enter(
        @Advice.This final CompletableFuture<?> future, @Advice.Argument(0) final Object payload)
        throws Throwable {
      if (BootstrapDispatcher.consumeScope(BootstrapDispatcher.SCOPE_JDK_COMPLETION)) {
        return null; // the completion of a JDK timer (L3a)
      }
      return BootstrapDispatcher.beforeCompletableFutureComplete(
          OperationTags.ASYNC_COMPLETE, future, payload);
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
    static @Nullable Boolean enter(
        @Advice.This final CompletableFuture<?> future, @Advice.Argument(0) final Throwable payload)
        throws Throwable {
      if (BootstrapDispatcher.consumeScope(BootstrapDispatcher.SCOPE_JDK_COMPLETION)) {
        return null; // the completion of a JDK timer (L3a)
      }
      return BootstrapDispatcher.beforeCompletableFutureComplete(
          OperationTags.ASYNC_COMPLETE_EXCEPTIONALLY, future, payload);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final Boolean decision, @Advice.Return(readOnly = false) boolean returned) {
      if (decision != null) {
        returned = decision;
      }
    }
  }

  /**
   * A JDK timer's run ({@code CompletableFuture$Timeout.run}, and {@code $DelayedCompleter.run} on
   * JDK 21): sets the one-shot {@link BootstrapDispatcher#SCOPE_JDK_COMPLETION} marker its
   * completion consumes, and clears it on every exit.
   */
  static final class JdkTimerRunAdvice {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JDK_COMPLETION);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.consumeScope(BootstrapDispatcher.SCOPE_JDK_COMPLETION);
    }
  }
}

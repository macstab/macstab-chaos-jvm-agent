package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

final class QueueAdvice {
  private QueueAdvice() {}

  static final class PutAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object queue) throws Throwable {
      BootstrapDispatcher.beforeQueueOperation("QUEUE_PUT", queue);
    }
  }

  static final class TakeAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object queue) throws Throwable {
      BootstrapDispatcher.beforeQueueOperation("QUEUE_TAKE", queue);
    }
  }

  static final class PollAdvice {
    // Mirrors OfferAdvice's skipOn pattern. A void @OnMethodEnter cannot signal "skip the real
    // poll()" — the previous implementation completely dropped SUPPRESS for QUEUE_POLL. Returning
    // Boolean here lets the suppress terminal (TerminalAction(RETURN, Boolean.FALSE, ...) for
    // QUEUE_POLL in ChaosDispatcher.suppressTerminal) surface as a non-null value, which triggers
    // skipOn = OnNonDefaultValue. The real poll() is skipped and the default reference return
    // (null) is produced — matching "queue drained to nothing" semantics. No exit rewrite is
    // needed: the skipped method's default return is already the correct null.
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static Boolean enter(@Advice.This final Object queue) throws Throwable {
      return BootstrapDispatcher.beforeBooleanQueueOperation("QUEUE_POLL", queue);
    }
  }

  static final class OfferAdvice {
    // skipOn = OnNonDefaultValue with a reference-typed Boolean enter value: the original offer
    // body is skipped when the dispatcher has decided an outcome (non-null decision). The exit
    // advice then rewrites the return to whatever Boolean the dispatcher chose. This mirrors the
    // CompletableFuture pattern in CompleteAdvice and honours the full dispatcher contract
    // ( decision == null → run real offer; decision != null → substitute the boolean ) rather
    // than collapsing both FALSE and TRUE dispatcher decisions into "return false".
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static Boolean enter(@Advice.This final Object queue) throws Throwable {
      return BootstrapDispatcher.beforeBooleanQueueOperation("QUEUE_OFFER", queue);
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

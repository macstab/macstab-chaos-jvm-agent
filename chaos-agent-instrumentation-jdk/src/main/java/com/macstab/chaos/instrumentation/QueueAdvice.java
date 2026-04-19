package com.macstab.chaos.instrumentation;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
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
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object queue) throws Throwable {
      BootstrapDispatcher.beforeQueueOperation("QUEUE_POLL", queue);
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

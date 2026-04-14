package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

final class QueueAdvice {
  private QueueAdvice() {}

  static final class PutAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object queue) throws Throwable {
      BootstrapDispatcher.beforeQueueOperation("QUEUE_PUT", queue);
    }
  }

  static final class TakeAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object queue) throws Throwable {
      BootstrapDispatcher.beforeQueueOperation("QUEUE_TAKE", queue);
    }
  }

  static final class PollAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object queue) throws Throwable {
      BootstrapDispatcher.beforeQueueOperation("QUEUE_POLL", queue);
    }
  }

  static final class OfferAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.This Object queue) throws Throwable {
      Boolean decision =
          BootstrapDispatcher.beforeBooleanQueueOperation("QUEUE_OFFER", queue);
      return Boolean.FALSE.equals(decision);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter boolean skipped, @Advice.Return(readOnly = false) boolean returned) {
      if (skipped) {
        returned = false;
      }
    }
  }
}

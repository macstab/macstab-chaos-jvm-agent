package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import net.bytebuddy.asm.Advice;
import org.jspecify.annotations.Nullable;

/**
 * Advice for {@code BlockingQueue} put / take / poll / offer.
 *
 * <p>Every advice brackets the queue method with {@link BootstrapDispatcher#enterScope(int)} /
 * {@link BootstrapDispatcher#exitScope(int)} on {@link BootstrapDispatcher#SCOPE_QUEUE}, so only
 * the outermost queue call on a thread is evaluated. Queues delegate internally (generic bridge
 * {@code put(Object)} → {@code put(Delayed)} → {@code offer(..)} in {@code DelayQueue} and {@code
 * DelayedWorkQueue}; {@code PriorityBlockingQueue.put} → {@code offer}). Evaluating the nested call
 * as well would apply delays several times and let a rejecting QUEUE_OFFER decision skip the inner
 * {@code offer} while the outer {@code void put()} returns normally, silently dropping the element.
 */
final class QueueAdvice {
  private QueueAdvice() {}

  static final class PutAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object queue) throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_QUEUE)) {
        try {
          BootstrapDispatcher.beforeQueueOperation(OperationTags.QUEUE_PUT, queue);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
    }
  }

  static final class TakeAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object queue) throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_QUEUE)) {
        try {
          BootstrapDispatcher.beforeQueueOperation(OperationTags.QUEUE_TAKE, queue);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
    }
  }

  static final class PollAdvice {
    // Returning a non-null Boolean skips the real poll() (skipOn = OnNonDefaultValue); the skipped
    // method's default reference return (null) is exactly "queue drained to nothing", which is
    // the QUEUE_POLL suppress semantic. Nested calls return null and run the real poll.
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Boolean enter(@Advice.This final Object queue) throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_QUEUE)) {
        return null;
      }
      try {
        return BootstrapDispatcher.beforeBooleanQueueOperation(OperationTags.QUEUE_POLL, queue);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
    }
  }

  /**
   * {@code poll(timeout, unit)}: as {@link PollAdvice}, but a suppressed poll first waits out its
   * timeout, as a real poll of an empty queue does, so a consumer polling in a loop does not spin.
   * The wait is agent-internal work (no scenario sees it as a sleep) and ends early with {@code
   * InterruptedException} when the thread is interrupted, like the real wait.
   */
  static final class TimedPollAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Boolean enter(
        @Advice.This final Object queue,
        @Advice.Argument(0) final long timeout,
        @Advice.Argument(1) final java.util.concurrent.TimeUnit unit)
        throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_QUEUE)) {
        return null;
      }
      try {
        final Boolean decision =
            BootstrapDispatcher.beforeBooleanQueueOperation(OperationTags.QUEUE_POLL, queue);
        if (decision != null) {
          BootstrapDispatcher.beginInternalCall();
          try {
            unit.sleep(timeout);
          } finally {
            BootstrapDispatcher.endInternalCall();
          }
        }
        return decision;
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
    }
  }

  static final class OfferAdvice {
    // skipOn = OnNonDefaultValue with a reference-typed Boolean enter value: the original offer
    // body is skipped when the dispatcher has decided an outcome (non-null decision), and the
    // exit advice rewrites the return to that Boolean. A null decision (no scenario, or a nested
    // offer inside an outer queue call) runs the real offer.
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Boolean enter(@Advice.This final Object queue) throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_QUEUE)) {
        return null;
      }
      try {
        return BootstrapDispatcher.beforeBooleanQueueOperation(OperationTags.QUEUE_OFFER, queue);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final Boolean decision, @Advice.Return(readOnly = false) boolean returned) {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_QUEUE);
      if (decision != null) {
        returned = decision;
      }
    }
  }
}

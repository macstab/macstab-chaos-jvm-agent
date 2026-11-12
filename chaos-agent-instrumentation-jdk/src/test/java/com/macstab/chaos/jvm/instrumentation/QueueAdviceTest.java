package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("QueueAdvice")
class QueueAdviceTest {

  private final Object queue = new Object();

  @AfterEach
  void resetBridge() {
    BootstrapDispatcher.install(null, null);
  }

  /**
   * Runs OfferAdvice's enter and exit as woven code does: every enter is paired with an exit, which
   * leaves the SCOPE_QUEUE call-depth scope.
   */
  private static Boolean enterAndExitOffer(final Object queue) throws Throwable {
    final Boolean decision = QueueAdvice.OfferAdvice.enter(queue);
    QueueAdvice.OfferAdvice.exit(decision, false);
    return decision;
  }

  @Nested
  @DisplayName("OfferAdvice enter \u2014 skip logic")
  class OfferAdviceEnterSkipLogic {

    @Test
    @DisplayName("returns null (no skip) when bridge is not installed")
    void returnsNullWhenBridgeNotInstalled() throws Throwable {
      assertThat(enterAndExitOffer(queue)).isNull();
    }

    @Test
    @DisplayName("returns null (no skip) when bridge returns null")
    void returnsNullWhenBridgeReturnsNull() throws Throwable {
      installBooleanQueueBridge((op, q) -> null);
      assertThat(enterAndExitOffer(queue)).isNull();
    }

    @Test
    @DisplayName("returns TRUE (skip) when bridge returns TRUE \u2014 offer fakes success")
    void returnsTrueWhenBridgeReturnsTrue() throws Throwable {
      // The enter value is what skipOn=OnNonDefaultValue evaluates. Any non-null Boolean
      // means skip the real offer body; exit then substitutes this Boolean as the return
      // value. Previous collapsed contract (TRUE \u2192 no-skip) lost the ability for chaos to
      // report a fake successful offer.
      installBooleanQueueBridge((op, q) -> Boolean.TRUE);
      assertThat(enterAndExitOffer(queue)).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("returns FALSE (skip) when bridge returns FALSE \u2014 offer is suppressed")
    void returnsFalseWhenBridgeReturnsFalse() throws Throwable {
      installBooleanQueueBridge((op, q) -> Boolean.FALSE);
      assertThat(enterAndExitOffer(queue)).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("passes QUEUE_OFFER operation and queue identity to bridge")
    void passesOperationAndQueueToBridge() throws Throwable {
      final AtomicReference<String> capturedOp = new AtomicReference<>();
      final AtomicReference<Object> capturedQueue = new AtomicReference<>();
      installBooleanQueueBridge(
          (op, q) -> {
            capturedOp.set(op);
            capturedQueue.set(q);
            return null;
          });

      enterAndExitOffer(queue);

      assertThat(capturedOp.get()).isEqualTo("QUEUE_OFFER");
      assertThat(capturedQueue.get()).isSameAs(queue);
    }
  }

  @Nested
  @DisplayName("OfferAdvice woven into a queue")
  class WovenOffer {

    @Test
    @DisplayName("a null decision runs the real offer and keeps its result")
    void nullDecisionRunsTheRealOffer() throws Throwable {
      installBooleanQueueBridge((op, q) -> null);
      final Object woven = wovenQueue(false);

      assertThat(offer(woven)).isFalse();
      assertThat(bodyRuns(woven)).isEqualTo(1);
    }

    @Test
    @DisplayName("a FALSE decision skips the real offer and answers false")
    void falseDecisionSkipsAndAnswersFalse() throws Throwable {
      installBooleanQueueBridge((op, q) -> Boolean.FALSE);
      final Object woven = wovenQueue(true);

      assertThat(offer(woven)).isFalse();
      assertThat(bodyRuns(woven)).isZero();
    }

    @Test
    @DisplayName("a TRUE decision skips the real offer and answers true")
    void trueDecisionSkipsAndAnswersTrue() throws Throwable {
      installBooleanQueueBridge((op, q) -> Boolean.TRUE);
      final Object woven = wovenQueue(false);

      assertThat(offer(woven)).isTrue();
      assertThat(bodyRuns(woven)).isZero();
    }

    @Test
    @DisplayName("the woven offer hands its own queue to the bridge")
    void passesTheWovenQueue() throws Throwable {
      final AtomicReference<Object> seen = new AtomicReference<>();
      installBooleanQueueBridge(
          (op, q) -> {
            seen.set(q);
            return null;
          });
      final Object woven = wovenQueue(true);

      offer(woven);

      assertThat(seen.get()).isSameAs(woven);
    }

    /**
     * {@link FakeQueue} with {@link QueueAdvice.OfferAdvice} woven into {@code offer}, defined in a
     * child loader of its own (no agent): the advice runs as the agent weaves it.
     */
    private Object wovenQueue(final boolean realResult) throws Exception {
      final Class<?> type =
          new ByteBuddy()
              .redefine(FakeQueue.class)
              .visit(Advice.to(QueueAdvice.OfferAdvice.class).on(ElementMatchers.named("offer")))
              .make()
              .load(
                  QueueAdviceTest.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
              .getLoaded();
      assertThat(type).isNotSameAs(FakeQueue.class);
      return type.getConstructor(boolean.class).newInstance(realResult);
    }

    private boolean offer(final Object woven) throws Exception {
      return (boolean) woven.getClass().getMethod("offer", Object.class).invoke(woven, "element");
    }

    private int bodyRuns(final Object woven) throws Exception {
      return (int) woven.getClass().getMethod("bodyRuns").invoke(woven);
    }
  }

  /** A queue whose {@code offer} counts its runs and answers a fixed result. */
  public static final class FakeQueue {

    private final boolean realResult;

    private int bodyRuns;

    public FakeQueue(final boolean realResult) {
      this.realResult = realResult;
    }

    public boolean offer(final Object element) {
      bodyRuns++;
      return realResult;
    }

    public int bodyRuns() {
      return bodyRuns;
    }
  }

  // \u2014 bridge test infrastructure \u2014

  @FunctionalInterface
  interface BooleanQueueBehavior {
    Boolean behave(String operation, Object queue) throws Throwable;
  }

  private static void installBooleanQueueBridge(final BooleanQueueBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_BOOLEAN_QUEUE_OPERATION] =
        MethodHandles.lookup()
            .findVirtual(
                BooleanQueueBehavior.class,
                "behave",
                MethodType.methodType(Boolean.class, String.class, Object.class));
    BootstrapDispatcher.install(behavior, handles);
  }
}

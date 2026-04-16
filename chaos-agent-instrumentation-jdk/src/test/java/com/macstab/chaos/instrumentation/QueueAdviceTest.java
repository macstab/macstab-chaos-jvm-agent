package com.macstab.chaos.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;
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

  @Nested
  @DisplayName("OfferAdvice enter — skip logic")
  class OfferAdviceEnterSkipLogic {

    @Test
    @DisplayName("returns false (no skip) when bridge is not installed")
    void returnsFalseWhenBridgeNotInstalled() throws Throwable {
      assertThat(QueueAdvice.OfferAdvice.enter(queue)).isFalse();
    }

    @Test
    @DisplayName("returns false (no skip) when bridge returns null")
    void returnsFalseWhenBridgeReturnsNull() throws Throwable {
      installBooleanQueueBridge((op, q) -> null);
      assertThat(QueueAdvice.OfferAdvice.enter(queue)).isFalse();
    }

    @Test
    @DisplayName("returns false (no skip) when bridge returns true")
    void returnsFalseWhenBridgeReturnsTrue() throws Throwable {
      installBooleanQueueBridge((op, q) -> Boolean.TRUE);
      assertThat(QueueAdvice.OfferAdvice.enter(queue)).isFalse();
    }

    @Test
    @DisplayName("returns true (skip) when bridge returns false — offer is suppressed")
    void returnsTrueWhenBridgeReturnsFalse() throws Throwable {
      installBooleanQueueBridge((op, q) -> Boolean.FALSE);
      assertThat(QueueAdvice.OfferAdvice.enter(queue)).isTrue();
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

      QueueAdvice.OfferAdvice.enter(queue);

      assertThat(capturedOp.get()).isEqualTo("QUEUE_OFFER");
      assertThat(capturedQueue.get()).isSameAs(queue);
    }
  }

  // — bridge test infrastructure —

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

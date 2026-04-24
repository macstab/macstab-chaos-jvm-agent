package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CompletableFutureAdvice")
class CompletableFutureAdviceTest {

  @AfterEach
  void resetBridge() {
    BootstrapDispatcher.install(null, null);
  }

  @Nested
  @DisplayName("CompleteAdvice enter")
  class CompleteAdviceEnter {

    @Test
    @DisplayName("returns null when bridge is not installed")
    void returnsNullWhenBridgeNotInstalled() throws Throwable {
      assertThat(CompletableFutureAdvice.CompleteAdvice.enter(new CompletableFuture<>(), null))
          .isNull();
    }

    @Test
    @DisplayName("returns null when bridge returns null")
    void returnsNullWhenBridgeReturnsNull() throws Throwable {
      installCompleteBridge((op, f, p) -> null);
      assertThat(CompletableFutureAdvice.CompleteAdvice.enter(new CompletableFuture<>(), null))
          .isNull();
    }

    @Test
    @DisplayName("returns true when bridge returns true")
    void returnsTrueWhenBridgeReturnsTrue() throws Throwable {
      installCompleteBridge((op, f, p) -> Boolean.TRUE);
      assertThat(CompletableFutureAdvice.CompleteAdvice.enter(new CompletableFuture<>(), null))
          .isTrue();
    }

    @Test
    @DisplayName("returns false when bridge returns false")
    void returnsFalseWhenBridgeReturnsFalse() throws Throwable {
      installCompleteBridge((op, f, p) -> Boolean.FALSE);
      assertThat(CompletableFutureAdvice.CompleteAdvice.enter(new CompletableFuture<>(), null))
          .isFalse();
    }

    @Test
    @DisplayName("passes ASYNC_COMPLETE operation name and arguments to bridge")
    void passesCorrectOperationNameToBridge() throws Throwable {
      final AtomicReference<String> capturedOp = new AtomicReference<>();
      final CompletableFuture<Object> future = new CompletableFuture<>();
      final Object payload = new Object();
      installCompleteBridge(
          (op, f, p) -> {
            capturedOp.set(op);
            assertThat(f).isSameAs(future);
            assertThat(p).isSameAs(payload);
            return null;
          });

      CompletableFutureAdvice.CompleteAdvice.enter(future, payload);

      assertThat(capturedOp.get()).isEqualTo("ASYNC_COMPLETE");
    }
  }

  @Nested
  @DisplayName("CompleteExceptionallyAdvice enter")
  class CompleteExceptionallyAdviceEnter {

    @Test
    @DisplayName("returns null when bridge is not installed")
    void returnsNullWhenBridgeNotInstalled() throws Throwable {
      assertThat(
              CompletableFutureAdvice.CompleteExceptionallyAdvice.enter(
                  new CompletableFuture<>(), new RuntimeException()))
          .isNull();
    }

    @Test
    @DisplayName("returns bridge result unchanged")
    void returnsBridgeResultUnchanged() throws Throwable {
      installCompleteBridge((op, f, p) -> Boolean.FALSE);
      assertThat(
              CompletableFutureAdvice.CompleteExceptionallyAdvice.enter(
                  new CompletableFuture<>(), new RuntimeException()))
          .isFalse();
    }

    @Test
    @DisplayName(
        "passes ASYNC_COMPLETE_EXCEPTIONALLY operation name — not transposed with ASYNC_COMPLETE")
    void passesCorrectOperationNameNotTransposed() throws Throwable {
      final AtomicReference<String> capturedOp = new AtomicReference<>();
      installCompleteBridge(
          (op, f, p) -> {
            capturedOp.set(op);
            return null;
          });

      CompletableFutureAdvice.CompleteExceptionallyAdvice.enter(
          new CompletableFuture<>(), new RuntimeException());

      assertThat(capturedOp.get()).isEqualTo("ASYNC_COMPLETE_EXCEPTIONALLY");
    }
  }

  // — bridge test infrastructure —

  @FunctionalInterface
  interface CompleteBehavior {
    Boolean behave(String operation, CompletableFuture<?> future, Object payload) throws Throwable;
  }

  private static void installCompleteBridge(final CompleteBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_COMPLETABLE_FUTURE_COMPLETE] =
        MethodHandles.lookup()
            .findVirtual(
                CompleteBehavior.class,
                "behave",
                MethodType.methodType(
                    Boolean.class, String.class, CompletableFuture.class, Object.class));
    BootstrapDispatcher.install(behavior, handles);
  }
}

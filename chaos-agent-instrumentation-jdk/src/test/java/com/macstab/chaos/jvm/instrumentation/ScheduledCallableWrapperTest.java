package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ScheduledCallableWrapper")
class ScheduledCallableWrapperTest {

  private final Object executor = new Object();

  @AfterEach
  void resetBridge() {
    BootstrapDispatcher.install(null, null);
  }

  @Nested
  @DisplayName("when bridge is not installed")
  class WhenBridgeNotInstalled {

    @Test
    @DisplayName("returns delegate result — tick fallback is true")
    void returnsDelegateResultWhenBridgeNotInstalled() throws Exception {
      BootstrapDispatcher.install(null, null);
      final ScheduledCallableWrapper<String> wrapper =
          new ScheduledCallableWrapper<>(executor, () -> "result");
      assertThat(wrapper.call()).isEqualTo("result");
    }
  }

  @Nested
  @DisplayName("when tick is allowed")
  class WhenTickAllowed {

    @Test
    @DisplayName("returns delegate result when bridge returns true")
    void returnsDelegateResultWhenTickAllows() throws Exception {
      installTickBridge((e, t, p) -> true);
      final ScheduledCallableWrapper<String> wrapper =
          new ScheduledCallableWrapper<>(executor, () -> "result");
      assertThat(wrapper.call()).isEqualTo("result");
    }
  }

  @Nested
  @DisplayName("when tick is suppressed")
  class WhenTickSuppressed {

    // Returning null here would silently inject a null into the caller's Future.get() —
    // any unboxing (e.g., Callable<Integer>) would then NPE downstream with no chaos
    // signal in the stack. CancellationException is the only honest option for a
    // Callable<T> that has been vetoed.
    @Test
    @DisplayName("throws CancellationException (not null) when bridge returns false")
    void throwsCancellationWhenTickSuppressed() throws Exception {
      installTickBridge((e, t, p) -> false);
      final ScheduledCallableWrapper<String> wrapper =
          new ScheduledCallableWrapper<>(executor, () -> "should-not-run");
      assertThatThrownBy(wrapper::call)
          .isExactlyInstanceOf(CancellationException.class)
          .hasMessageContaining("suppressed by scenario");
    }

    @Test
    @DisplayName("does not invoke delegate when tick is suppressed")
    void doesNotInvokeDelegateWhenSuppressed() throws Exception {
      installTickBridge((e, t, p) -> false);
      final boolean[] invoked = {false};
      final ScheduledCallableWrapper<Integer> wrapper =
          new ScheduledCallableWrapper<>(
              executor,
              () -> {
                invoked[0] = true;
                return 1;
              });
      assertThatThrownBy(wrapper::call).isExactlyInstanceOf(CancellationException.class);
      assertThat(invoked[0]).isFalse();
    }
  }

  @Nested
  @DisplayName("exception propagation")
  class ExceptionPropagation {

    @Test
    @DisplayName("RuntimeException from tick propagates as original type without wrapping")
    void chaosRuntimeExceptionPropagatesUnwrapped() throws Exception {
      final RuntimeException injected = new RuntimeException("chaos");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });
      final ScheduledCallableWrapper<String> wrapper =
          new ScheduledCallableWrapper<>(executor, () -> "irrelevant");

      assertThatThrownBy(wrapper::call)
          .isSameAs(injected)
          .isExactlyInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("checked exception from tick propagates via sneaky throw as original type")
    void checkedExceptionFromTickPropagatesUnwrapped() throws Exception {
      final Exception injected = new Exception("chaos-checked");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });
      final ScheduledCallableWrapper<String> wrapper =
          new ScheduledCallableWrapper<>(executor, () -> "irrelevant");

      assertThatThrownBy(wrapper::call).isSameAs(injected).isExactlyInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("checked exception from delegate.call() propagates unchanged")
    void checkedExceptionFromDelegatePropagates() throws Exception {
      installTickBridge((e, t, p) -> true);
      final Exception delegateEx = new Exception("from-delegate");
      final ScheduledCallableWrapper<String> wrapper =
          new ScheduledCallableWrapper<>(
              executor,
              () -> {
                throw delegateEx;
              });

      assertThatThrownBy(wrapper::call).isSameAs(delegateEx).isExactlyInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("RuntimeException from delegate.call() propagates unchanged")
    void runtimeExceptionFromDelegatePropagates() throws Exception {
      installTickBridge((e, t, p) -> true);
      final RuntimeException delegateEx = new RuntimeException("from-delegate");
      final ScheduledCallableWrapper<String> wrapper =
          new ScheduledCallableWrapper<>(
              executor,
              () -> {
                throw delegateEx;
              });

      assertThatThrownBy(wrapper::call).isSameAs(delegateEx);
    }
  }

  // — bridge test infrastructure —

  @FunctionalInterface
  interface TickBehavior {
    boolean tick(Object executor, Object task, boolean periodic) throws Throwable;
  }

  /**
   * Installs a minimal bridge with only {@code BEFORE_SCHEDULED_TICK} wired. All other slots are
   * left null; they will NPE if accessed, which is intentional — tests should not trigger them.
   */
  private static void installTickBridge(final TickBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_SCHEDULED_TICK] =
        MethodHandles.lookup()
            .findVirtual(
                TickBehavior.class,
                "tick",
                MethodType.methodType(boolean.class, Object.class, Object.class, boolean.class));
    BootstrapDispatcher.install(behavior, handles);
  }
}

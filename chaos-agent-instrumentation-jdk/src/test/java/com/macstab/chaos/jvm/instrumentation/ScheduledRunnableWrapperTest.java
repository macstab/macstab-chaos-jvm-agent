package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ScheduledRunnableWrapper")
class ScheduledRunnableWrapperTest {

  private final Object executor = new Object();

  @AfterEach
  void resetBridge() {
    BootstrapDispatcher.install(null, null);
  }

  @Nested
  @DisplayName("when bridge is not installed")
  class WhenBridgeNotInstalled {

    @Test
    @DisplayName("runs delegate — tick fallback is true")
    void runsDelegateWhenBridgeNotInstalled() {
      BootstrapDispatcher.install(null, null);
      final AtomicBoolean ran = new AtomicBoolean(false);
      new ScheduledRunnableWrapper(executor, () -> ran.set(true), false).run();
      assertThat(ran).isTrue();
    }
  }

  @Nested
  @DisplayName("when tick is allowed")
  class WhenTickAllowed {

    @Test
    @DisplayName("runs delegate when bridge returns true")
    void runsDelegateWhenTickAllows() throws Exception {
      installTickBridge((e, t, p) -> true);
      final AtomicBoolean ran = new AtomicBoolean(false);
      new ScheduledRunnableWrapper(executor, () -> ran.set(true), false).run();
      assertThat(ran).isTrue();
    }
  }

  @Nested
  @DisplayName("when tick is suppressed")
  class WhenTickSuppressed {

    @Test
    @DisplayName("skips delegate when bridge returns false")
    void skipsDelegateWhenTickSuppressed() throws Exception {
      installTickBridge((e, t, p) -> false);
      final AtomicBoolean ran = new AtomicBoolean(false);
      new ScheduledRunnableWrapper(executor, () -> ran.set(true), false).run();
      assertThat(ran).isFalse();
    }
  }

  @Nested
  @DisplayName("exception propagation")
  class ExceptionPropagation {

    @Test
    @DisplayName("RuntimeException from tick propagates as original type without wrapping")
    void chaosExceptionPropagatesUnwrapped() throws Exception {
      final RuntimeException injected = new RuntimeException("chaos-injected");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });
      final ScheduledRunnableWrapper wrapper =
          new ScheduledRunnableWrapper(executor, () -> {}, false);

      assertThatThrownBy(wrapper::run)
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
      final ScheduledRunnableWrapper wrapper =
          new ScheduledRunnableWrapper(executor, () -> {}, false);

      assertThatThrownBy(wrapper::run).isSameAs(injected).isExactlyInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("RuntimeException from delegate propagates unchanged")
    void delegateRuntimeExceptionPropagates() throws Exception {
      installTickBridge((e, t, p) -> true);
      final RuntimeException delegateEx = new RuntimeException("from-delegate");
      final ScheduledRunnableWrapper wrapper =
          new ScheduledRunnableWrapper(
              executor,
              () -> {
                throw delegateEx;
              },
              false);

      assertThatThrownBy(wrapper::run).isSameAs(delegateEx);
    }

    @Test
    @DisplayName("an interrupt from the tick is rethrown with the thread's interrupt flag restored")
    void interruptFromTickRestoresTheFlag() throws Exception {
      final InterruptedException injected = new InterruptedException("chaos-interrupt");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });
      final ScheduledRunnableWrapper wrapper =
          new ScheduledRunnableWrapper(executor, () -> {}, false);
      try {
        assertThatThrownBy(wrapper::run).isSameAs(injected);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Nested
  @DisplayName("periodic task")
  class PeriodicTask {

    @Test
    @DisplayName("a failure of the tick skips this run but keeps the schedule: nothing is thrown")
    void tickFailureIsSwallowed() throws Exception {
      installTickBridge(
          (e, t, p) -> {
            assertThat(p).isTrue();
            throw new IllegalStateException("chaos-injected");
          });
      final AtomicBoolean ran = new AtomicBoolean(false);
      new ScheduledRunnableWrapper(executor, () -> ran.set(true), true).run();
      assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("an interrupt from the tick is swallowed with the interrupt flag restored")
    void interruptFromTickRestoresTheFlag() throws Exception {
      installTickBridge(
          (e, t, p) -> {
            throw new InterruptedException("chaos-interrupt");
          });
      final AtomicBoolean ran = new AtomicBoolean(false);
      try {
        new ScheduledRunnableWrapper(executor, () -> ran.set(true), true).run();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
      assertThat(ran).isFalse();
    }
  }

  @Nested
  @DisplayName("when the executor is gone")
  class WhenExecutorGone {

    @Test
    @DisplayName("runs the delegate without consulting the tick")
    void runsWithoutTick() throws Exception {
      installTickBridge(
          (e, t, p) -> {
            throw new AssertionError("the tick must not be consulted");
          });
      final AtomicBoolean ran = new AtomicBoolean(false);
      new ScheduledRunnableWrapper(null, () -> ran.set(true), true).run();
      assertThat(ran).isTrue();
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

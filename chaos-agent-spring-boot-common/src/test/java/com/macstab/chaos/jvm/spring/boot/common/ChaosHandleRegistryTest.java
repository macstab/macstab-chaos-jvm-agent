package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry of handles the starter activated: one handle per id, stopping never fails the caller
 * (an endpoint or the context shutdown), and nothing registered during shutdown outlives it.
 */
@DisplayName("Handle registry")
class ChaosHandleRegistryTest {

  private final ChaosHandleRegistry registry = new ChaosHandleRegistry();

  @Test
  @DisplayName("registering an id again stops the handle it replaces")
  void replacesById() {
    final FakeHandle first = new FakeHandle("plan:a");
    final FakeHandle second = new FakeHandle("plan:a");
    registry.register(first);
    registry.register(second);

    assertThat(first.closed).hasValue(1);
    assertThat(second.closed).hasValue(0);
    assertThat(registry.ids()).containsExactly("plan:a");
  }

  @Test
  @DisplayName("registering the same handle twice does not stop it")
  void sameHandleIsKept() {
    final FakeHandle handle = new FakeHandle("plan:a");
    registry.register(handle);
    registry.register(handle);
    assertThat(handle.closed).hasValue(0);
  }

  @Test
  @DisplayName("stop reports whether a tracked handle was stopped, and forgets it either way")
  void stop() {
    registry.register(new FakeHandle("ok"));
    registry.register(new FakeHandle("failing", true));

    assertThat(registry.stop("ok")).isTrue();
    assertThat(registry.stop("ok")).as("already gone").isFalse();
    assertThat(registry.stop("unknown")).isFalse();
    assertThat(registry.stop("failing")).as("its close threw").isFalse();
    assertThat(registry.ids()).isEmpty();
  }

  @Test
  @DisplayName("stopAll stops every handle and counts the ones that stopped cleanly")
  void stopAll() {
    registry.register(new FakeHandle("a"));
    registry.register(new FakeHandle("b"));
    registry.register(new FakeHandle("c", true));

    assertThat(registry.stopAll()).isEqualTo(2);
    assertThat(registry.ids()).isEmpty();
  }

  @Test
  @DisplayName("after the context closes, a late registration is stopped at once and not tracked")
  void registrationDuringShutdown() {
    final FakeHandle early = new FakeHandle("early");
    registry.register(early);
    registry.destroy();
    assertThat(early.closed).hasValue(1);

    final FakeHandle late = new FakeHandle("late");
    final FakeHandle lateFailing = new FakeHandle("late-failing", true);
    registry.register(late);
    registry.register(lateFailing);

    assertThat(late.closed).hasValue(1);
    assertThat(lateFailing.closed).as("its failure is logged, not thrown").hasValue(1);
    assertThat(registry.ids()).isEmpty();
  }

  @Test
  @DisplayName("a replaced handle whose close fails does not prevent the registration")
  void failingReplacedHandle() {
    registry.register(new FakeHandle("plan:a", true));
    final FakeHandle replacement = new FakeHandle("plan:a");
    registry.register(replacement);
    assertThat(registry.ids()).containsExactly("plan:a");
    assertThat(registry.stop("plan:a")).isTrue();
    assertThat(replacement.closed).hasValue(1);
  }

  private static final class FakeHandle implements ChaosActivationHandle {
    private final String id;
    private final boolean failOnClose;
    final AtomicInteger closed = new AtomicInteger();

    FakeHandle(final String id) {
      this(id, false);
    }

    FakeHandle(final String id, final boolean failOnClose) {
      this.id = id;
      this.failOnClose = failOnClose;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public void release() {}

    @Override
    public ChaosDiagnostics.ScenarioState state() {
      return ChaosDiagnostics.ScenarioState.ACTIVE;
    }

    @Override
    public long matchedCount() {
      return 0;
    }

    @Override
    public long appliedCount() {
      return 0;
    }

    @Override
    public void close() {
      closed.incrementAndGet();
      if (failOnClose) {
        throw new IllegalStateException("close failed");
      }
    }
  }
}

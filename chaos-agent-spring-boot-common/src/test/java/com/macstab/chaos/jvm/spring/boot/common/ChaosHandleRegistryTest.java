package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry of handles the starter activated: one handle per id, stopping never fails the
 * context shutdown, and nothing registered during shutdown outlives it.
 */
@DisplayName("Handle registry")
class ChaosHandleRegistryTest {

  private final ChaosHandleRegistry registry = new ChaosHandleRegistry();

  @Test
  @DisplayName("a closing context holds no shared plan, and a second close releases nothing twice")
  void sharedPlanAfterClose() {
    final com.macstab.chaos.jvm.api.ChaosControlPlane plane =
        com.macstab.chaos.jvm.bootstrap.ChaosPlatform.installLocally();
    final com.macstab.chaos.jvm.api.ChaosPlan plan =
        com.macstab.chaos.jvm.startup.ChaosPlanMapper.read(
            """
            {"scenarios": [{"id": "registry-shared", "scope": "JVM",
              "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
              "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]}
            """);
    final ChaosHandleRegistry other = new ChaosHandleRegistry();
    registry.activateShared(plane, plan);
    // A context applying its plan twice holds it once.
    registry.activateShared(plane, plan);
    other.activateShared(plane, plan);

    registry.destroy();
    registry.destroy();
    assertThat(plane.diagnostics().scenario("registry-shared"))
        .as("the other context still holds it")
        .isPresent();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.activateShared(plane, plan))
        .isInstanceOf(IllegalStateException.class);

    other.destroy();
    assertThat(plane.diagnostics().scenario("registry-shared")).isEmpty();
  }

  @Test
  @DisplayName("registering an id again stops the handle it replaces")
  void replacesById() {
    final FakeHandle first = new FakeHandle("plan:a");
    final FakeHandle second = new FakeHandle("plan:a");
    registry.register(first);
    registry.register(second);

    assertThat(first.closed).hasValue(1);
    assertThat(second.closed).hasValue(0);
    registry.destroy();
    assertThat(second.closed).as("the replacement is tracked").hasValue(1);
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
  @DisplayName("closing the context stops every handle, continuing past one that fails")
  void destroyStopsAll() {
    final FakeHandle failing = new FakeHandle("failing", true);
    final FakeHandle ok = new FakeHandle("ok");
    registry.register(failing);
    registry.register(ok);

    registry.destroy();

    assertThat(failing.closed).hasValue(1);
    assertThat(ok.closed).hasValue(1);
  }

  @Test
  @DisplayName("a failed stop logs the handle's id on one line, its line breaks replaced")
  void failedStopLogsSanitizedId() {
    final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ChaosHandleRegistry.class.getName());
    final java.util.logging.Handler capture =
        new java.util.logging.Handler() {
          @Override
          public void publish(final java.util.logging.LogRecord record) {
            lines.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(capture);
    try {
      registry.register(new FakeHandle("plan:a\n2026-11-09 FORGED", true));
      registry.destroy();
    } finally {
      logger.removeHandler(capture);
    }

    assertThat(lines)
        .singleElement()
        .satisfies(line -> assertThat(line).contains("plan:a").doesNotContain("\n"));
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
    registry.destroy();
    assertThat(late.closed).as("not tracked, so not stopped again").hasValue(1);
  }

  @Test
  @DisplayName("a replaced handle whose close fails does not prevent the registration")
  void failingReplacedHandle() {
    registry.register(new FakeHandle("plan:a", true));
    final FakeHandle replacement = new FakeHandle("plan:a");
    registry.register(replacement);
    registry.destroy();
    assertThat(replacement.closed).as("the replacement is tracked").hasValue(1);
  }

  @Test
  @DisplayName("a registration overtaken by the shutdown stops its own handle, failing or not")
  void registrationOvertakenByShutdown() {
    // The shutdown runs after register() saw the registry open but before it stored the handle
    // (here: while register() reads the handle's id), so the drain cannot have seen it.
    for (final boolean failOnClose : new boolean[] {false, true}) {
      final ChaosHandleRegistry racing = new ChaosHandleRegistry();
      final FakeHandle handle = new FakeHandle("racing", failOnClose, racing::destroy);

      racing.register(handle);

      assertThat(handle.closed).hasValue(1);
      racing.destroy();
      assertThat(handle.closed).as("not tracked, so not stopped again").hasValue(1);
    }
  }

  private static final class FakeHandle implements ChaosActivationHandle {
    private final String id;
    private final boolean failOnClose;
    private Runnable onFirstIdRead;
    final AtomicInteger closed = new AtomicInteger();

    FakeHandle(final String id) {
      this(id, false);
    }

    FakeHandle(final String id, final boolean failOnClose) {
      this(id, failOnClose, null);
    }

    FakeHandle(final String id, final boolean failOnClose, final Runnable onFirstIdRead) {
      this.id = id;
      this.failOnClose = failOnClose;
      this.onFirstIdRead = onFirstIdRead;
    }

    @Override
    public String id() {
      final Runnable action = onFirstIdRead;
      onFirstIdRead = null;
      if (action != null) {
        action.run();
      }
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

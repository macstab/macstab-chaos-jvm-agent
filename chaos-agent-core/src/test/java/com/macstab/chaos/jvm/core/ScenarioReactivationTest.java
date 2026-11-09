package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Scenario re-activation and handle lifecycle")
class ScenarioReactivationTest {

  private ChaosRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
  }

  @Nested
  @DisplayName("re-activating the same scenario id")
  class Reactivation {

    @Test
    @DisplayName("is possible after close() (try-with-resources)")
    void afterClose() {
      // Arrange
      try (ChaosActivationHandle handle = runtime.activate(scenario("reuse"))) {
        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      }

      // Act
      final ChaosActivationHandle again = runtime.activate(scenario("reuse"));

      // Assert
      assertThat(again.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("is possible after stop(), replacing the stopped scenario")
    void afterStop() {
      // Arrange
      final ChaosActivationHandle first = runtime.activate(scenario("reuse"));
      first.stop();

      // Act
      final ChaosActivationHandle second = runtime.activate(scenario("reuse"));

      // Assert
      assertThat(second.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      assertThat(runtime.diagnostics().snapshot().scenarios())
          .as("the stopped scenario is replaced, not kept as a ghost entry")
          .filteredOn(report -> "reuse".equals(report.id()))
          .singleElement()
          .extracting(ChaosDiagnostics.ScenarioReport::state)
          .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("a replaced handle can no longer start the old scenario")
    void replacedHandleCannotRestart() {
      // Arrange
      final ChaosActivationHandle first = runtime.activate(scenario("reuse"));
      first.stop();
      runtime.activate(scenario("reuse"));

      // Act
      first.start();

      // Assert
      assertThat(first.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
    }

    @Test
    @DisplayName("is possible for a plan after the plan handle is closed")
    void planAfterClose() {
      // Arrange
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("plan", ""),
              null,
              List.of(scenario("plan-a"), scenario("plan-b")));
      runtime.activate(plan).close();

      // Act
      final ChaosActivationHandle again = runtime.activate(plan);

      // Assert
      assertThat(again.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }
  }

  @Nested
  @DisplayName("activating an id that is still active")
  class DuplicateActivation {

    @Test
    @DisplayName("throws ChaosActivationException as documented")
    void throwsActivationException() {
      runtime.activate(scenario("dup"));

      assertThatThrownBy(() -> runtime.activate(scenario("dup")))
          .isInstanceOf(ChaosActivationException.class)
          .hasMessageContaining("already active");
    }

    @Test
    @DisplayName("does not start the rejected scenario or publish events for the live id")
    void rejectedScenarioHasNoSideEffects() {
      // Arrange
      runtime.activate(scenario("dup"));
      final List<ChaosEvent> events = new CopyOnWriteArrayList<>();
      runtime.addEventListener(events::add);

      // Act
      assertThatThrownBy(() -> runtime.activate(scenario("dup")))
          .isInstanceOf(ChaosActivationException.class);

      // Assert
      assertThat(events)
          .as("no STARTED/STOPPED for the rejected duplicate; only its FAILED report")
          .extracting(ChaosEvent::type)
          .containsExactly(ChaosEvent.Type.FAILED);
      assertThat(runtime.diagnostics().scenario("dup"))
          .get()
          .extracting(ChaosDiagnostics.ScenarioReport::state)
          .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("is reported as an activation conflict in diagnostics")
    void recordedAsConflict() {
      runtime.activate(scenario("dup"));

      assertThatThrownBy(() -> runtime.activate(scenario("dup")))
          .isInstanceOf(ChaosActivationException.class);

      assertThat(runtime.diagnostics().snapshot().failures())
          .extracting(ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT);
    }
  }

  @Nested
  @DisplayName("a closed control plane")
  class ClosedControlPlane {

    @Test
    @DisplayName("rejects new activations instead of accepting scenarios nobody cleans up")
    void rejectsActivation() {
      runtime.close();

      assertThatThrownBy(() -> runtime.activate(scenario("late")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("rejects new sessions and listeners")
    void rejectsSessionsAndListeners() {
      runtime.close();

      assertThatThrownBy(() -> runtime.openSession("late"))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> runtime.addEventListener(event -> {}))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("an INACTIVE scenario is final")
  class InactiveIsFinal {

    @Test
    @DisplayName("start() after maxApplications is spent does not revive it")
    void spentScenarioIsNotRevived() {
      final List<ChaosEvent.Type> events = new CopyOnWriteArrayList<>();
      runtime.addEventListener(event -> events.add(event.type()));
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("spent")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.count(1))
                  .build());
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);

      handle.start();
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
      assertThat(handle.appliedCount()).isEqualTo(1L);
      assertThat(events).as("no second start").containsOnlyOnce(ChaosEvent.Type.STARTED);
    }

    @Test
    @DisplayName("start() after a stressor expired builds no second stressor")
    void expiredStressorIsNotRebuilt() {
      final AtomicInteger built = new AtomicInteger();
      final StressorFactory counting =
          new StressorFactory(Optional::empty) {
            @Override
            @Nullable ManagedStressor createIfNeeded(
                final ChaosEffect effect,
                final boolean allowDestructiveEffects,
                final java.util.function.Consumer<String> onEnd) {
              built.incrementAndGet();
              return super.createIfNeeded(effect, allowDestructiveEffects, onEnd);
            }
          };
      final ScenarioController controller =
          new ScenarioController(
              ChaosScenario.builder("keep-alive")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
                  .effect(
                      new ChaosEffect.KeepAliveEffect(
                          "final-keep-alive", true, Duration.ofMillis(10)))
                  .activationPolicy(
                      ActivationPolicy.builder().activeFor(Duration.ofMillis(50)).build())
                  .build(),
              "jvm",
              null,
              Clock.systemUTC(),
              new ObservabilityBus(ChaosMetricsSink.NOOP),
              counting);
      try {
        controller.start();
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> controller.state() == ChaosDiagnostics.ScenarioState.INACTIVE);

        controller.start();

        assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
        assertThat(built).hasValue(1);
      } finally {
        controller.stop();
      }
    }
  }

  @Nested
  @DisplayName("a stop while start() builds the stressor")
  class StopDuringStart {

    @Test
    @DisplayName("wins: the built stressor is closed, and nothing starts or is published")
    void stopWins() {
      final AtomicReference<ScenarioController> target = new AtomicReference<>();
      final AtomicInteger closed = new AtomicInteger();
      final StressorFactory stopping =
          new StressorFactory(Optional::empty) {
            @Override
            @Nullable ManagedStressor createIfNeeded(
                final ChaosEffect effect,
                final boolean allowDestructiveEffects,
                final java.util.function.Consumer<String> onEnd) {
              target.get().stop();
              return closed::incrementAndGet;
            }
          };
      final List<ChaosEvent.Type> events = new CopyOnWriteArrayList<>();
      final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
      bus.addListener(event -> events.add(event.type()));
      final ScenarioController controller =
          new ScenarioController(
              ChaosScenario.builder("raced")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
                  .effect(new ChaosEffect.KeepAliveEffect("raced", true, Duration.ofMillis(10)))
                  .build(),
              "jvm",
              null,
              Clock.systemUTC(),
              bus,
              stopping);
      target.set(controller);

      controller.start();

      assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
      assertThat(closed).hasValue(1);
      assertThat(events).containsExactly(ChaosEvent.Type.STOPPED);
    }
  }

  @Nested
  @DisplayName("a plan in a session")
  class SessionPlans {

    @Test
    @DisplayName("is refused by a closed session")
    void closedSessionRefusesPlan() {
      final ChaosSession session = runtime.openSession("closed");
      session.close();

      assertThatThrownBy(() -> session.activate(plan(sessionScenario("a"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed session");
    }

    @Test
    @DisplayName("that fails leaves none of its scenarios behind, and the session's own running")
    void failedPlanRollsBack() {
      try (ChaosSession session = runtime.openSession("rollback")) {
        final ChaosActivationHandle own = session.activate(sessionScenario("b"));

        assertThatThrownBy(() -> session.activate(plan(sessionScenario("a"), sessionScenario("b"))))
            .isInstanceOf(ChaosActivationException.class);

        assertThat(runtime.diagnostics().scenario("a")).isEmpty();
        assertThat(own.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      }
    }

    private ChaosScenario sessionScenario(final String id) {
      return ChaosScenario.builder(id)
          .scope(ChaosScenario.ScenarioScope.SESSION)
          .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
          .effect(ChaosEffect.delay(Duration.ofMillis(1)))
          .activationPolicy(ActivationPolicy.always())
          .build();
    }

    private ChaosPlan plan(final ChaosScenario... scenarios) {
      return new ChaosPlan(new ChaosPlan.Metadata("session-plan", ""), null, List.of(scenarios));
    }
  }

  private static ChaosScenario scenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}

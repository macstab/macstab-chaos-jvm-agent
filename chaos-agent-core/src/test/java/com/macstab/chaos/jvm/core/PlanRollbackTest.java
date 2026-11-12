package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosInstrumentationException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A plan is never partly active: when one of its scenarios fails to register after the others did,
 * the earlier ones are destroyed, the plan's observability settings are cleared and the failure is
 * rethrown, in the JVM scope and in a session.
 */
@DisplayName("A plan whose second scenario fails to register")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class PlanRollbackTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void close() {
    runtime.close();
  }

  /**
   * Weaving fails for the second scenario's own operations only: the plan weaves the union of its
   * operations first (which succeeds), then each registration weaves its own again.
   */
  private void failSecondRegistration() {
    runtime.setHookDemand(
        operations -> {
          if (operations.equals(Set.of(OperationType.QUEUE_PUT))) {
            throw new IllegalStateException("cannot weave the queue hooks");
          }
        });
  }

  private static ChaosScenario scenario(
      final String id, final ChaosScenario.ScenarioScope scope, final ChaosSelector selector) {
    return ChaosScenario.builder(id)
        .scope(scope)
        .selector(selector)
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static ChaosPlan plan(final ChaosScenario.ScenarioScope scope) {
    return new ChaosPlan(
        new ChaosPlan.Metadata("half", ""),
        new ChaosPlan.Observability(false, false, false),
        List.of(
            scenario("first", scope, ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT))),
            scenario("second", scope, ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)))));
  }

  @Test
  @DisplayName("in the JVM scope: the first is destroyed, the settings cleared, the failure thrown")
  void jvmScope() {
    failSecondRegistration();

    assertThatThrownBy(() -> runtime.activate(plan(ChaosScenario.ScenarioScope.JVM)))
        .isInstanceOf(ChaosInstrumentationException.class)
        .hasMessageContaining("cannot weave the queue hooks");

    assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
    assertThat(runtime.jmxVisible("jvm", "first")).as("jmxEnabled=false is cleared").isTrue();
    runtime.setHookDemand(HookDemand.NONE);
    try (ChaosActivationHandle again =
        runtime.activate(
            scenario(
                "first",
                ChaosScenario.ScenarioScope.JVM,
                ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT))))) {
      assertThat(again.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }
  }

  @Test
  @DisplayName("in a session: the first is destroyed and the session keeps no handle of either")
  void sessionScope() {
    final ChaosSession session = runtime.openSession("half");
    try {
      failSecondRegistration();

      assertThatThrownBy(() -> session.activate(plan(ChaosScenario.ScenarioScope.SESSION)))
          .isInstanceOf(ChaosInstrumentationException.class);

      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
      assertThat(((DefaultChaosSession) session).handleCount()).isZero();
      assertThat(runtime.jmxVisible("session:" + session.id(), "first")).isTrue();
    } finally {
      session.close();
    }
  }

  @Test
  @DisplayName("a rollback that fails is added to the rethrown failure as suppressed")
  void failingRollbackIsSuppressed() {
    final ChaosControlPlaneImpl plane =
        new ChaosControlPlaneImpl(Clock.systemUTC(), ChaosMetricsSink.NOOP);
    final AtomicInteger activations = new AtomicInteger();
    final IllegalStateException activationFailure = new IllegalStateException("second fails");
    final IllegalStateException rollbackFailure = new IllegalStateException("rollback fails");
    try {
      assertThatThrownBy(
              () ->
                  plane.activatePlan(
                      plan(ChaosScenario.ScenarioScope.JVM),
                      "jvm",
                      "plan:",
                      scenario -> {},
                      scenario -> {
                        if (activations.incrementAndGet() == 2) {
                          throw activationFailure;
                        }
                        return plane.activate(scenario);
                      },
                      handle -> {
                        handle.close();
                        throw rollbackFailure;
                      }))
          .isSameAs(activationFailure)
          .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(rollbackFailure));

      assertThat(plane.diagnostics().snapshot().scenarios()).isEmpty();
      assertThat(plane.jmxVisible("jvm", "first")).isTrue();
    } finally {
      plane.close();
    }
  }

  @Test
  @DisplayName("an Error from the second scenario's stressor rolls the first back too")
  void errorRollsBack() {
    final ChaosControlPlaneImpl plane =
        new ChaosControlPlaneImpl(
            Clock.systemUTC(),
            ChaosMetricsSink.NOOP,
            new StressorFactory(Optional::empty) {
              @Override
              ManagedStressor createIfNeeded(
                  final ChaosEffect effect,
                  final boolean allowDestructiveEffects,
                  final Consumer<String> onEnd) {
                if (effect instanceof ChaosEffect.KeepAliveEffect keepAlive
                    && keepAlive.threadName().equals("second")) {
                  throw new NoClassDefFoundError("java/lang/management/ManagementFactory");
                }
                return () -> {};
              }
            });
    final ChaosPlan plan =
        new ChaosPlan(
            new ChaosPlan.Metadata("linkage", ""),
            new ChaosPlan.Observability(false, false, false),
            List.of(keepAlive("first"), keepAlive("second")));
    try {
      assertThatThrownBy(() -> plane.activate(plan)).isInstanceOf(NoClassDefFoundError.class);

      assertThat(plane.diagnostics().snapshot().scenarios()).isEmpty();
      assertThat(plane.jmxVisible("jvm", "first")).as("jmxEnabled=false is cleared").isTrue();
      try (ChaosActivationHandle again = plane.activate(keepAlive("first"))) {
        assertThat(again.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      }
    } finally {
      plane.close();
    }
  }

  private static ChaosScenario keepAlive(final String id) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
        .effect(ChaosEffect.keepAlive(id, true, Duration.ofHours(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}

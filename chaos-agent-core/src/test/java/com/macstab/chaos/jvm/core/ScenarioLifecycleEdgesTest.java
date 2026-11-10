package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle edges of a scenario: who ends it when its budget is spent (L19), what a failed start
 * publishes (L21), a stressor whose close fails, the handles a long-lived session keeps, and what
 * closing the control plane leaves of its sessions.
 */
@DisplayName("Scenario lifecycle edges")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class ScenarioLifecycleEdgesTest {

  private static ChaosScenario keepAlive(final String id) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
        .effect(ChaosEffect.keepAlive(id + "-thread", true, Duration.ofHours(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  /** A control plane whose stressors come from {@code factory}. */
  private static ChaosControlPlaneImpl plane(final StressorFactory factory) {
    return new ChaosControlPlaneImpl(Clock.systemUTC(), ChaosMetricsSink.NOOP, factory);
  }

  /** The types of the events published for {@code id}, recorded by a listener on {@code plane}. */
  private static List<ChaosEvent.Type> recordEvents(
      final ChaosControlPlaneImpl plane, final String id) {
    final List<ChaosEvent.Type> types = new CopyOnWriteArrayList<>();
    plane.addEventListener(
        event -> {
          if (id.equals(event.scenarioId())) {
            types.add(event.type());
          }
        });
    return types;
  }

  @Nested
  @DisplayName("A stressor whose close fails")
  class FailingClose {

    @Test
    @DisplayName("still stops and unregisters its scenario, publishing STOPPED")
    void closeStillStops() {
      final ChaosControlPlaneImpl plane =
          plane(
              new StressorFactory(Optional::empty) {
                @Override
                ManagedStressor createIfNeeded(
                    final ChaosEffect effect,
                    final boolean allowDestructiveEffects,
                    final Consumer<String> onEnd) {
                  return () -> {
                    throw new IllegalStateException("cannot close");
                  };
                }
              });
      final List<ChaosEvent.Type> events = recordEvents(plane, "stubborn");
      try (LogCapture log = LogCapture.agent()) {
        final ChaosActivationHandle handle = plane.activate(keepAlive("stubborn"));

        assertThatCode(handle::close).doesNotThrowAnyException();

        assertThat(events).contains(ChaosEvent.Type.STOPPED);
        assertThat(plane.diagnostics().snapshot().scenarios()).isEmpty();
        assertThat(log.messages())
            .anyMatch(message -> message.contains("could not close the stressor of scenario"));
      } finally {
        plane.close();
      }
    }
  }
}

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
  @DisplayName("A spent maxApplications")
  class SpentBudget {

    @Test
    @DisplayName("is reported INACTIVE by the thread that spent it, after its APPLIED")
    void committingThreadEndsTheScenario() throws Exception {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final CountDownLatch inApplied = new CountDownLatch(1);
        final CountDownLatch leaveApplied = new CountDownLatch(1);
        final List<String> seen = new CopyOnWriteArrayList<>();
        runtime.addEventListener(
            event -> {
              if (!"cap".equals(event.scenarioId())
                  || (event.type() != ChaosEvent.Type.APPLIED
                      && event.type() != ChaosEvent.Type.INACTIVE)) {
                return;
              }
              seen.add(event.type() + "@" + Thread.currentThread().getName());
              if (event.type() == ChaosEvent.Type.APPLIED) {
                inApplied.countDown();
                try {
                  leaveApplied.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              }
            });
        runtime.activate(
            ChaosScenario.builder("cap")
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ZERO))
                .activationPolicy(ActivationPolicy.count(1))
                .build());
        final Object executor = new Object();
        final Runnable submit =
            () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", executor, () -> {});

        final Thread committer = new Thread(submit, "committer");
        committer.start();
        assertThat(inApplied.await(5, TimeUnit.SECONDS)).isTrue();
        final Thread late = new Thread(submit, "late");
        late.start();
        late.join(5_000L);
        leaveApplied.countDown();
        committer.join(5_000L);

        assertThat(seen).containsExactly("APPLIED@committer", "INACTIVE@committer");
      }
    }
  }

  @Nested
  @DisplayName("A stressor that cannot start")
  class FailedStart {

    private final AtomicBoolean failing = new AtomicBoolean();

    private final StressorFactory factory =
        new StressorFactory(Optional::empty) {
          @Override
          ManagedStressor createIfNeeded(
              final ChaosEffect effect,
              final boolean allowDestructiveEffects,
              final Consumer<String> onEnd) {
            if (failing.get()) {
              throw new OutOfMemoryError("Java heap space");
            }
            return super.createIfNeeded(effect, allowDestructiveEffects, onEnd);
          }
        };

    @Test
    @DisplayName("publishes FAILED and nothing else for its id")
    void onlyFailed() {
      final ChaosControlPlaneImpl plane = plane(factory);
      final List<ChaosEvent.Type> events = recordEvents(plane, "starving");
      failing.set(true);
      try {
        assertThatThrownBy(() -> plane.activate(keepAlive("starving")))
            .isInstanceOf(ChaosUnsupportedFeatureException.class);

        assertThat(events).containsExactly(ChaosEvent.Type.FAILED);
        assertThat(plane.diagnostics().snapshot().scenarios()).isEmpty();
      } finally {
        plane.close();
      }
    }

    @Test
    @DisplayName("leaves the finished scenario of its id as it was")
    void keepsTheFinishedScenario() {
      final ChaosControlPlaneImpl plane = plane(factory);
      try {
        plane.activate(keepAlive("again")).stop();
        final List<ChaosEvent.Type> events = recordEvents(plane, "again");
        failing.set(true);

        assertThatThrownBy(() -> plane.activate(keepAlive("again")))
            .isInstanceOf(ChaosUnsupportedFeatureException.class);

        assertThat(events).containsExactly(ChaosEvent.Type.FAILED);
        assertThat(plane.diagnostics().scenario("again"))
            .get()
            .extracting(ChaosDiagnostics.ScenarioReport::state)
            .isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
      } finally {
        plane.close();
      }
    }
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

  @Nested
  @DisplayName("A long-lived session")
  class LongLivedSession {

    @Test
    @DisplayName("keeps no handle of a scenario it activated and the caller closed")
    void closedHandlesAreDropped() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosSession session = runtime.openSession("long-lived");
        try {
          for (int i = 0; i < 1_000; i++) {
            session
                .activate(
                    ChaosScenario.builder("s" + i)
                        .scope(ChaosScenario.ScenarioScope.SESSION)
                        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                        .effect(ChaosEffect.delay(Duration.ZERO))
                        .activationPolicy(ActivationPolicy.always())
                        .build())
                .close();
          }

          assertThat(((DefaultChaosSession) session).handleCount()).isLessThanOrEqualTo(1);
        } finally {
          session.close();
        }
      }
    }
  }
}

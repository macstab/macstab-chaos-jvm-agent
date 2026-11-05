package com.macstab.chaos.jvm.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The meters read from fixed diagnostics and events delivered directly, for the values a live
 * runtime does not produce on demand: woven hook groups, a malformed counter, an application
 * without injected delay.
 */
@DisplayName("ChaosMeterBinder with fixed diagnostics")
class ChaosMeterBinderDetailsTest {

  private final FixedControlPlane plane = new FixedControlPlane();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  @DisplayName("counts woven hook groups, and reads a missing, blank or malformed detail as zero")
  void runtimeDetails() {
    new ChaosMeterBinder(plane).bindTo(registry);

    plane.details = Map.of("droppedEvents", "many", "wovenHookGroups", "jdk-io,jdk-net,jdbc");
    assertThat(registry.get("chaos.hooks.woven").gauge().value()).isEqualTo(3.0);
    assertThat(registry.get("chaos.events.dropped").functionCounter().count()).isZero();

    plane.details = Map.of("droppedEvents", "", "wovenHookGroups", " ");
    assertThat(registry.get("chaos.hooks.woven").gauge().value()).isZero();
    assertThat(registry.get("chaos.events.dropped").functionCounter().count()).isZero();

    plane.details = Map.of();
    assertThat(registry.get("chaos.hooks.woven").gauge().value()).isZero();
  }

  @Test
  @DisplayName("gauges count only the scenarios in their state")
  void scenariosByState() {
    new ChaosMeterBinder(plane).bindTo(registry);
    plane.scenarios =
        List.of(
            new ChaosDiagnostics.ScenarioReport(
                "running",
                "",
                "jvm",
                ChaosScenario.ScenarioScope.JVM,
                ChaosDiagnostics.ScenarioState.ACTIVE,
                0L,
                0L,
                ""));

    assertThat(registry.get("chaos.scenarios").tags("state", "active").gauge().value())
        .isEqualTo(1.0);
    assertThat(registry.get("chaos.scenarios").tags("state", "inactive").gauge().value()).isZero();
  }

  @Test
  @DisplayName("an application without injected delay is counted but records no delay")
  void applicationWithoutDelay() {
    new ChaosMeterBinder(plane).bindTo(registry);

    plane.deliver(
        new ChaosEvent(
            Instant.EPOCH,
            ChaosEvent.Type.APPLIED,
            "suppressing",
            "applied",
            Map.of("operation", "JDBC_STATEMENT_EXECUTE", "effectType", "SuppressEffect")));

    assertThat(
            registry
                .get("chaos.applications")
                .tags(
                    "scenario",
                    "suppressing",
                    "operation",
                    "JDBC_STATEMENT_EXECUTE",
                    "effect",
                    "SuppressEffect")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(registry.find("chaos.injected.delay").timer()).isNull();
  }

  /**
   * A control plane reporting the diagnostics set on it, which delivers events to its listeners on
   * the calling thread.
   */
  private static final class FixedControlPlane implements ChaosControlPlane {
    private final List<ChaosEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, String> details = Map.of();
    private volatile List<ChaosDiagnostics.ScenarioReport> scenarios = List.of();

    void deliver(final ChaosEvent event) {
      listeners.forEach(listener -> listener.onEvent(event));
    }

    @Override
    public ChaosDiagnostics diagnostics() {
      return new ChaosDiagnostics() {
        @Override
        public Snapshot snapshot() {
          return new Snapshot(Instant.EPOCH, scenarios, List.of(), details);
        }

        @Override
        public Optional<ScenarioReport> scenario(final String scenarioId) {
          return Optional.empty();
        }

        @Override
        public String debugDump() {
          return "";
        }
      };
    }

    @Override
    public void addEventListener(final ChaosEventListener listener) {
      listeners.add(listener);
    }

    @Override
    public boolean removeEventListener(final ChaosEventListener listener) {
      return listeners.remove(listener);
    }

    @Override
    public ChaosActivationHandle activate(final ChaosScenario scenario) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public ChaosActivationHandle activate(final ChaosPlan plan) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public ChaosSession openSession(final String displayName) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void close() {}

    @Override
    public ChaosSession.ScopeBinding bindSession(final String sessionId) {
      return () -> {};
    }
  }
}

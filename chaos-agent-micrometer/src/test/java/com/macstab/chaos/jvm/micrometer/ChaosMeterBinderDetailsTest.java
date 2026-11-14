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
    // A snapshot serves the meters for a second: each set of details is read by a fresh binder.
    SimpleMeterRegistry scraped =
        bound(Map.of("droppedEvents", "many", "wovenHookGroups", "a,b,c"));
    assertThat(scraped.get("chaos.hooks.woven").gauge().value()).isEqualTo(3.0);
    assertThat(scraped.get("chaos.listener.events.dropped").functionCounter().count()).isZero();

    scraped = bound(Map.of("droppedEvents", "", "wovenHookGroups", " "));
    assertThat(scraped.get("chaos.hooks.woven").gauge().value()).isZero();
    assertThat(scraped.get("chaos.listener.events.dropped").functionCounter().count()).isZero();

    scraped = bound(Map.of());
    assertThat(scraped.get("chaos.hooks.woven").gauge().value()).isZero();
  }

  private SimpleMeterRegistry bound(final Map<String, String> details) {
    final FixedControlPlane fixed = new FixedControlPlane();
    fixed.details = details;
    final SimpleMeterRegistry scraped = new SimpleMeterRegistry();
    new ChaosMeterBinder(fixed).bindTo(scraped);
    return scraped;
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
                    "jdbc_statement_execute",
                    "effect",
                    "suppress")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(registry.find("chaos.injected.delay").timer()).isNull();
  }

  @Test
  @DisplayName("a 300 µs delay is recorded from delayNanos")
  void subMillisecondDelayRecorded() {
    new ChaosMeterBinder(plane).bindTo(registry);

    plane.deliver(
        new ChaosEvent(
            Instant.EPOCH,
            ChaosEvent.Type.APPLIED,
            "short",
            "applied",
            Map.of(
                "operation", "THREAD_SLEEP", "effectType", "DelayEffect", "delayNanos", "300000")));

    final io.micrometer.core.instrument.Timer delay =
        registry.get("chaos.injected.delay").tags("scenario", "short").timer();
    assertThat(delay.count()).isEqualTo(1L);
    assertThat(delay.totalTime(java.util.concurrent.TimeUnit.MICROSECONDS)).isEqualTo(300.0);
  }

  @Test
  @DisplayName("one scrape of the 7 diagnostics meters takes one diagnostics snapshot")
  void oneSnapshotPerScrape() {
    new ChaosMeterBinder(plane).bindTo(registry);
    final long before = plane.snapshots.get();

    registry.getMeters().forEach(meter -> meter.measure().forEach(m -> m.getValue()));

    assertThat(registry.getMeters()).hasSize(7);
    assertThat(plane.snapshots.get() - before).isEqualTo(1L);
  }

  @Test
  @DisplayName("a scrape a second after the last snapshot takes a fresh one")
  void snapshotRefreshedAfterASecond() {
    new ChaosMeterBinder(plane).bindTo(registry);
    final io.micrometer.core.instrument.Gauge woven = registry.get("chaos.hooks.woven").gauge();
    woven.value();
    final long first = plane.snapshots.get();
    plane.details = Map.of("wovenHookGroups", "jdk-io");

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .until(() -> woven.value() == 1.0);

    assertThat(plane.snapshots.get()).isGreaterThan(first);
  }

  @Test
  @DisplayName("the effect tag is the plan JSON type: the class name without Effect, decapitalised")
  void effectNames() {
    assertThat(ChaosMeterBinder.effectName("DelayEffect")).isEqualTo("delay");
    assertThat(ChaosMeterBinder.effectName("ConnectionResetEffect")).isEqualTo("connectionReset");
    assertThat(ChaosMeterBinder.effectName("unknown")).isEqualTo("unknown");
    assertThat(ChaosMeterBinder.effectName("Effect")).isEqualTo("Effect");
  }

  /**
   * A control plane reporting the diagnostics set on it, which delivers events to its listeners on
   * the calling thread.
   */
  private static final class FixedControlPlane implements ChaosControlPlane {
    private final List<ChaosEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, String> details = Map.of();
    private volatile List<ChaosDiagnostics.ScenarioReport> scenarios = List.of();
    private final java.util.concurrent.atomic.AtomicLong snapshots =
        new java.util.concurrent.atomic.AtomicLong();

    void deliver(final ChaosEvent event) {
      listeners.forEach(listener -> listener.onEvent(event));
    }

    @Override
    public ChaosDiagnostics diagnostics() {
      return new ChaosDiagnostics() {
        @Override
        public Snapshot snapshot() {
          snapshots.incrementAndGet();
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

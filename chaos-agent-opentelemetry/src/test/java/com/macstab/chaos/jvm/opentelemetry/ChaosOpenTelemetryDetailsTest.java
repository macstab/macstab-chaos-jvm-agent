package com.macstab.chaos.jvm.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The instruments read from fixed diagnostics and events delivered directly, for the values a live
 * runtime does not produce on demand: woven hook groups, a missing or malformed counter, an
 * application without injected delay.
 */
@DisplayName("ChaosOpenTelemetry with fixed diagnostics")
class ChaosOpenTelemetryDetailsTest {

  private final FixedControlPlane plane = new FixedControlPlane();
  private final InMemoryMetricReader reader = InMemoryMetricReader.create();
  private final OpenTelemetrySdk sdk =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build();

  @AfterEach
  void tearDown() {
    sdk.close();
  }

  @Test
  @DisplayName("counts woven hook groups, and reads a missing or malformed counter as zero")
  void runtimeDetails() throws Exception {
    try (AutoCloseable metrics = ChaosOpenTelemetry.metrics(plane, sdk)) {
      plane.details = Map.of("droppedEvents", "many", "wovenHookGroups", "jdk-io,jdk-net");
      Collection<MetricData> collected = reader.collectAllMetrics();
      assertThat(woven(collected)).isEqualTo(2L);
      assertThat(dropped(collected)).isZero();

      plane.details = Map.of();
      collected = reader.collectAllMetrics();
      assertThat(woven(collected)).isZero();
      assertThat(dropped(collected)).isZero();
    }
  }

  @Test
  @DisplayName("an application without injected delay is counted but records no delay")
  void applicationWithoutDelay() throws Exception {
    try (AutoCloseable metrics = ChaosOpenTelemetry.metrics(plane, sdk)) {
      plane.deliver(
          new ChaosEvent(
              Instant.EPOCH,
              ChaosEvent.Type.APPLIED,
              "suppressing",
              "applied",
              Map.of("operation", "JDBC_STATEMENT_EXECUTE", "effectType", "SuppressEffect")));

      final Collection<MetricData> collected = reader.collectAllMetrics();
      assertThat(find(collected, "chaos.applications").orElseThrow().getLongSumData().getPoints())
          .singleElement()
          .satisfies(
              point -> {
                assertThat(point.getValue()).isEqualTo(1L);
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.EFFECT))
                    .isEqualTo("SuppressEffect");
              });
      assertThat(find(collected, "chaos.injected.delay")).isEmpty();
    }
  }

  private static long woven(final Collection<MetricData> collected) {
    return find(collected, "chaos.hooks.woven")
        .orElseThrow()
        .getLongGaugeData()
        .getPoints()
        .iterator()
        .next()
        .getValue();
  }

  private static long dropped(final Collection<MetricData> collected) {
    return find(collected, "chaos.events.dropped")
        .orElseThrow()
        .getLongSumData()
        .getPoints()
        .iterator()
        .next()
        .getValue();
  }

  private static Optional<MetricData> find(
      final Collection<MetricData> metrics, final String name) {
    return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst();
  }

  /**
   * A control plane reporting the diagnostics set on it, which delivers events to its listeners on
   * the calling thread.
   */
  private static final class FixedControlPlane implements ChaosControlPlane {
    private final List<ChaosEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, String> details = Map.of();

    void deliver(final ChaosEvent event) {
      listeners.forEach(listener -> listener.onEvent(event));
    }

    @Override
    public ChaosDiagnostics diagnostics() {
      return new ChaosDiagnostics() {
        @Override
        public Snapshot snapshot() {
          return new Snapshot(Instant.EPOCH, List.of(), List.of(), details);
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
  }
}

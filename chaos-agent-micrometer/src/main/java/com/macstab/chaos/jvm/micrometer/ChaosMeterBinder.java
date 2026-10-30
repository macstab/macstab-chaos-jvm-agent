package com.macstab.chaos.jvm.micrometer;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;

/**
 * Publishes chaos activity as Micrometer metrics:
 *
 * <ul>
 *   <li>{@code chaos.events} — a counter per event, tagged {@code type} ({@code applied}, {@code
 *       skipped}, {@code started}, ...) and {@code scenario}; {@code skipped} also carries {@code
 *       reason}, {@code failed} {@code category}
 *   <li>{@code chaos.scenarios} — a gauge of registered scenarios per {@code state}
 * </ul>
 *
 * <p>Events are counted on an agent-owned thread ({@link ChaosEventListener#async}), never on the
 * application thread that triggered them. Bridge to OpenTelemetry with Micrometer's OTLP registry.
 *
 * <pre>{@code
 * new ChaosMeterBinder(ChaosPlatform.current()).bindTo(meterRegistry);
 * }</pre>
 */
public final class ChaosMeterBinder implements MeterBinder {

  private final ChaosControlPlane controlPlane;

  /**
   * Creates a binder for {@code controlPlane}.
   *
   * @param controlPlane the control plane whose activity is measured
   */
  public ChaosMeterBinder(final ChaosControlPlane controlPlane) {
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
  }

  @Override
  public void bindTo(final MeterRegistry registry) {
    for (final ChaosDiagnostics.ScenarioState state : ChaosDiagnostics.ScenarioState.values()) {
      Gauge.builder("chaos.scenarios", controlPlane, plane -> countIn(plane, state))
          .description("Registered chaos scenarios by state")
          .tag("state", state.name().toLowerCase(java.util.Locale.ROOT))
          .register(registry);
    }
    controlPlane.addEventListener(ChaosEventListener.async(event -> count(registry, event)));
  }

  private static double countIn(
      final ChaosControlPlane plane, final ChaosDiagnostics.ScenarioState state) {
    return plane.diagnostics().snapshot().scenarios().stream()
        .filter(report -> report.state() == state)
        .count();
  }

  private static void count(final MeterRegistry registry, final ChaosEvent event) {
    final Counter.Builder counter =
        Counter.builder("chaos.events")
            .description("Chaos events by type and scenario")
            .tag("type", event.type().name().toLowerCase(java.util.Locale.ROOT))
            .tag("scenario", String.valueOf(event.scenarioId()));
    if (event.type() == ChaosEvent.Type.SKIPPED) {
      counter.tag("reason", event.attributes().getOrDefault("reason", "unknown"));
    }
    if (event.type() == ChaosEvent.Type.FAILED) {
      counter.tag("category", event.attributes().getOrDefault("category", "unknown"));
    }
    counter.register(registry).increment();
  }
}

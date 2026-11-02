package com.macstab.chaos.jvm.micrometer;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Publishes chaos activity as Micrometer metrics. Every meter name has one fixed set of tag keys,
 * as registries such as Prometheus require.
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><th>Meter</th><th>Type</th><th>Tags</th><th>Measures</th></tr>
 *   <tr><td>{@code chaos.events}</td><td>counter</td><td>{@code type}, {@code scenario}</td>
 *       <td>every chaos event ({@code applied}, {@code skipped}, {@code started}, ...)</td></tr>
 *   <tr><td>{@code chaos.applications}</td><td>counter</td>
 *       <td>{@code scenario}, {@code operation}, {@code effect}</td>
 *       <td>effects that took effect, by intercepted operation and effect type</td></tr>
 *   <tr><td>{@code chaos.injected.delay}</td><td>timer</td><td>{@code scenario}, {@code operation}</td>
 *       <td>latency the agent added to calls</td></tr>
 *   <tr><td>{@code chaos.skips}</td><td>counter</td><td>{@code scenario}, {@code reason}</td>
 *       <td>matches the policy kept from applying (published while a listener is registered)</td></tr>
 *   <tr><td>{@code chaos.activation.failures}</td><td>counter</td>
 *       <td>{@code scenario}, {@code category}</td><td>failed activations</td></tr>
 *   <tr><td>{@code chaos.scenarios}</td><td>gauge</td><td>{@code state}</td>
 *       <td>registered scenarios per state</td></tr>
 *   <tr><td>{@code chaos.events.dropped}</td><td>function counter</td><td>none</td>
 *       <td>events dropped by full asynchronous listener queues</td></tr>
 *   <tr><td>{@code chaos.hooks.woven}</td><td>gauge</td><td>none</td>
 *       <td>hook groups woven into JDK and library classes</td></tr>
 * </table>
 *
 * <p>Events are counted on an agent-owned thread ({@link ChaosEventListener#async}), never on the
 * application thread that triggered them. {@link #close()} stops counting. For OpenTelemetry
 * without Micrometer, use the {@code chaos-agent-opentelemetry} module.
 *
 * <pre>{@code
 * new ChaosMeterBinder(ChaosPlatform.current()).bindTo(meterRegistry);
 * }</pre>
 */
public final class ChaosMeterBinder implements MeterBinder, AutoCloseable {

  private final ChaosControlPlane controlPlane;
  private volatile ChaosEventListener listener;

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
          .tag("state", state.name().toLowerCase(Locale.ROOT))
          .register(registry);
    }
    FunctionCounter.builder(
            "chaos.events.dropped",
            controlPlane,
            plane -> detail(plane, "droppedEvents", Long::parseLong))
        .description("Chaos events dropped by full asynchronous listener queues")
        .register(registry);
    Gauge.builder(
            "chaos.hooks.woven",
            controlPlane,
            plane -> detail(plane, "wovenHookGroups", ChaosMeterBinder::countList))
        .description("Hook groups woven into JDK and library classes")
        .register(registry);
    final ChaosEventListener counting = ChaosEventListener.async(event -> count(registry, event));
    listener = counting;
    controlPlane.addEventListener(counting);
  }

  /** Stops counting events; meters already registered stay in their registry. */
  @Override
  public void close() {
    final ChaosEventListener registered = listener;
    if (registered != null) {
      controlPlane.removeEventListener(registered);
      listener = null;
    }
  }

  private static double countIn(
      final ChaosControlPlane plane, final ChaosDiagnostics.ScenarioState state) {
    return plane.diagnostics().snapshot().scenarios().stream()
        .filter(report -> report.state() == state)
        .count();
  }

  private static double detail(
      final ChaosControlPlane plane,
      final String key,
      final java.util.function.ToLongFunction<String> parse) {
    final String value = plane.diagnostics().snapshot().runtimeDetails().get(key);
    if (value == null || value.isEmpty()) {
      return 0.0;
    }
    try {
      return parse.applyAsLong(value);
    } catch (final NumberFormatException unexpected) {
      return 0.0;
    }
  }

  private static long countList(final String commaSeparated) {
    return commaSeparated.isBlank() ? 0 : commaSeparated.split(",").length;
  }

  private static void count(final MeterRegistry registry, final ChaosEvent event) {
    final String scenario = String.valueOf(event.scenarioId());
    Counter.builder("chaos.events")
        .description("Chaos events by type and scenario")
        .tag("type", event.type().name().toLowerCase(Locale.ROOT))
        .tag("scenario", scenario)
        .register(registry)
        .increment();
    final java.util.Map<String, String> attributes = event.attributes();
    switch (event.type()) {
      case APPLIED -> {
        final String operation = attributes.getOrDefault("operation", "unknown");
        Counter.builder("chaos.applications")
            .description("Chaos effects that took effect")
            .tag("scenario", scenario)
            .tag("operation", operation)
            .tag("effect", attributes.getOrDefault("effectType", "unknown"))
            .register(registry)
            .increment();
        final String delay = attributes.get("delayMillis");
        if (delay != null) {
          Timer.builder("chaos.injected.delay")
              .description("Latency the chaos agent added to calls")
              .tag("scenario", scenario)
              .tag("operation", operation)
              .register(registry)
              .record(Duration.ofMillis(Long.parseLong(delay)));
        }
      }
      case SKIPPED ->
          Counter.builder("chaos.skips")
              .description("Matches the activation policy kept from applying")
              .tag("scenario", scenario)
              .tag("reason", attributes.getOrDefault("reason", "unknown"))
              .register(registry)
              .increment();
      case FAILED ->
          Counter.builder("chaos.activation.failures")
              .description("Failed scenario activations")
              .tag("scenario", scenario)
              .tag("category", attributes.getOrDefault("category", "unknown"))
              .register(registry)
              .increment();
      default -> {}
    }
  }
}

package com.macstab.chaos.jvm.opentelemetry;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes chaos activity through the OpenTelemetry API: metrics, and span events that mark the
 * traced requests chaos hit.
 *
 * <pre>{@code
 * ChaosOpenTelemetry.Registration metrics =
 *     ChaosOpenTelemetry.metrics(ChaosPlatform.current(), openTelemetry);
 * ChaosOpenTelemetry.Registration spans = ChaosOpenTelemetry.spanEvents(ChaosPlatform.current());
 * }</pre>
 *
 * <p>Metrics (instrumentation scope {@value #SCOPE}); every instrument has one fixed attribute set:
 *
 * <ul>
 *   <li>{@code chaos.events} — counter of every chaos event: {@code chaos.event.type}
 *   <li>{@code chaos.applications} — counter of effects that took effect: {@code
 *       chaos.scenario.id}, {@code chaos.operation}, {@code chaos.effect}
 *   <li>{@code chaos.injected.delay} — histogram (unit {@code s}, double) of the latency the agent
 *       added, from the event's {@code delayNanos}: {@code chaos.scenario.id}, {@code
 *       chaos.operation}
 *   <li>{@code chaos.skips} — counter of matches the policy kept from applying: {@code
 *       chaos.scenario.id}, {@code chaos.skip.reason}
 *   <li>{@code chaos.activation.failures} — counter: {@code chaos.failure.category}
 *   <li>{@code chaos.scenarios} — gauge of registered scenarios: {@code chaos.scenario.state}
 *   <li>{@code chaos.listener.events.dropped} — counter of events dropped by full asynchronous
 *       listener queues
 *   <li>{@code chaos.hooks.woven} — gauge of hook groups woven into JDK and library classes
 * </ul>
 *
 * <p>Attribute values: {@code chaos.operation} is the {@code OperationType} name in lower case
 * ({@code thread_sleep}); {@code chaos.effect} is the effect's plan JSON type ({@code delay},
 * {@code connectionReset}), derived from the event's {@code effectType}, the effect class's simple
 * name: without its {@code Effect} suffix, first letter lower-cased.
 *
 * <p>{@code chaos.applications}, {@code chaos.injected.delay} and {@code chaos.skips} keep {@code
 * chaos.scenario.id}: the API cannot retire a series, so their cardinality is bounded only by the
 * SDK's cardinality limit; drop the attribute with a View where scenario ids are many.
 *
 * <p>Counters and the histogram are fed on an agent-owned thread ({@link
 * ChaosEventListener#async}); span events are added on the application thread, synchronously,
 * because the current span lives there.
 */
public final class ChaosOpenTelemetry {

  /** The instrumentation scope name of the metrics. */
  public static final String SCOPE = "com.macstab.chaos.jvm";

  /** Attribute: the scenario id. */
  public static final AttributeKey<String> SCENARIO_ID =
      AttributeKey.stringKey("chaos.scenario.id");

  /** Attribute: the intercepted operation, an {@code OperationType} name in lower case. */
  public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("chaos.operation");

  /** Attribute: the effect type, its plan JSON name, e.g. {@code delay}. */
  public static final AttributeKey<String> EFFECT = AttributeKey.stringKey("chaos.effect");

  /** Attribute: the event type, lower case. */
  public static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("chaos.event.type");

  /** Attribute: why a match did not apply. */
  public static final AttributeKey<String> SKIP_REASON =
      AttributeKey.stringKey("chaos.skip.reason");

  /** Attribute: the category of a failed activation. */
  public static final AttributeKey<String> FAILURE_CATEGORY =
      AttributeKey.stringKey("chaos.failure.category");

  /** Attribute: a scenario state, lower case. */
  public static final AttributeKey<String> SCENARIO_STATE =
      AttributeKey.stringKey("chaos.scenario.state");

  /** Span event attribute: the latency chaos added, in seconds. */
  public static final AttributeKey<Double> DELAY = AttributeKey.doubleKey("chaos.delay");

  /** The name of the span event added when chaos applies to a traced call. */
  public static final String SPAN_EVENT = "chaos.applied";

  private ChaosOpenTelemetry() {}

  /** What {@link #metrics} and {@link #spanEvents} started; closing it stops them. */
  public interface Registration extends AutoCloseable {

    /**
     * Stops the registration; a second call does nothing more. Never throws a checked exception.
     */
    @Override
    void close();
  }

  /**
   * Starts publishing chaos metrics through {@code openTelemetry}'s meter provider.
   *
   * @param controlPlane the control plane whose activity is measured
   * @param openTelemetry the OpenTelemetry instance
   * @return closes the instruments' callbacks and stops counting events
   */
  public static Registration metrics(
      final ChaosControlPlane controlPlane, final OpenTelemetry openTelemetry) {
    Objects.requireNonNull(controlPlane, "controlPlane");
    final Meter meter = openTelemetry.getMeter(SCOPE);
    final List<Runnable> closeables = new ArrayList<>();
    final io.opentelemetry.api.metrics.ObservableLongGauge scenarios =
        meter
            .gaugeBuilder("chaos.scenarios")
            .setDescription("Registered chaos scenarios by state")
            .ofLongs()
            .buildWithCallback(
                measurement -> {
                  final List<ChaosDiagnostics.ScenarioReport> reports =
                      controlPlane.diagnostics().snapshot().scenarios();
                  for (final ChaosDiagnostics.ScenarioState state :
                      ChaosDiagnostics.ScenarioState.values()) {
                    measurement.record(
                        reports.stream().filter(report -> report.state() == state).count(),
                        Attributes.of(SCENARIO_STATE, state.name().toLowerCase(Locale.ROOT)));
                  }
                });
    closeables.add(scenarios::close);
    final io.opentelemetry.api.metrics.ObservableLongCounter dropped =
        meter
            .counterBuilder("chaos.listener.events.dropped")
            .setDescription("Chaos events dropped by full asynchronous listener queues")
            .buildWithCallback(
                measurement -> measurement.record(longDetail(controlPlane, "droppedEvents")));
    closeables.add(dropped::close);
    final io.opentelemetry.api.metrics.ObservableLongGauge woven =
        meter
            .gaugeBuilder("chaos.hooks.woven")
            .setDescription("Hook groups woven into JDK and library classes")
            .ofLongs()
            .buildWithCallback(
                measurement -> {
                  final String groups =
                      controlPlane
                          .diagnostics()
                          .snapshot()
                          .runtimeDetails()
                          .getOrDefault("wovenHookGroups", "");
                  measurement.record(groups.isBlank() ? 0 : groups.split(",").length);
                });
    closeables.add(woven::close);

    final Instruments instruments = new Instruments(meter);
    final ChaosEventListener listener = ChaosEventListener.async(instruments::record);
    controlPlane.addEventListener(listener);
    return () -> {
      controlPlane.removeEventListener(listener);
      closeables.forEach(Runnable::run);
    };
  }

  /**
   * Adds a {@value #SPAN_EVENT} event to the current span whenever chaos applies to a call made
   * inside it, with the scenario id, the operation, the effect type and the injected delay.
   *
   * @param controlPlane the control plane whose applications are recorded
   * @return stops adding span events
   */
  public static Registration spanEvents(final ChaosControlPlane controlPlane) {
    Objects.requireNonNull(controlPlane, "controlPlane");
    final ChaosEventListener listener =
        event -> {
          if (event.type() != ChaosEvent.Type.APPLIED) {
            return;
          }
          final Span span = Span.current();
          if (!span.isRecording()) {
            return;
          }
          final Map<String, String> attributes = event.attributes();
          final AttributesBuilder builder =
              Attributes.builder()
                  .put(SCENARIO_ID, String.valueOf(event.scenarioId()))
                  .put(OPERATION, operation(attributes))
                  .put(EFFECT, effectName(attributes.getOrDefault("effectType", "unknown")));
          final String delay = attributes.get("delayNanos");
          if (delay != null) {
            builder.put(DELAY, seconds(delay));
          }
          span.addEvent(SPAN_EVENT, builder.build());
        };
    controlPlane.addEventListener(listener);
    return () -> controlPlane.removeEventListener(listener);
  }

  private static String operation(final Map<String, String> attributes) {
    return attributes.getOrDefault("operation", "unknown").toLowerCase(Locale.ROOT);
  }

  private static double seconds(final String nanos) {
    return Long.parseLong(nanos) / 1e9;
  }

  /**
   * The effect's plan JSON type from its class's simple name: {@code DelayEffect} is {@code delay},
   * {@code ConnectionResetEffect} is {@code connectionReset}.
   */
  static String effectName(final String effectType) {
    final String base =
        effectType.endsWith("Effect")
            ? effectType.substring(0, effectType.length() - "Effect".length())
            : effectType;
    return base.isEmpty() ? effectType : Character.toLowerCase(base.charAt(0)) + base.substring(1);
  }

  private static long longDetail(final ChaosControlPlane controlPlane, final String key) {
    final String value = controlPlane.diagnostics().snapshot().runtimeDetails().get(key);
    try {
      return value == null ? 0L : Long.parseLong(value);
    } catch (final NumberFormatException unexpected) {
      return 0L;
    }
  }

  /** The synchronous instruments fed from events. */
  private static final class Instruments {
    private final LongCounter events;
    private final LongCounter applications;
    private final DoubleHistogram delay;
    private final LongCounter skips;
    private final LongCounter failures;

    Instruments(final Meter meter) {
      events = meter.counterBuilder("chaos.events").setDescription("Chaos events by type").build();
      applications =
          meter
              .counterBuilder("chaos.applications")
              .setDescription("Chaos effects that took effect")
              .build();
      delay =
          meter
              .histogramBuilder("chaos.injected.delay")
              .setDescription("Latency the chaos agent added to calls")
              .setUnit("s")
              .build();
      skips =
          meter
              .counterBuilder("chaos.skips")
              .setDescription("Matches the activation policy kept from applying")
              .build();
      failures =
          meter
              .counterBuilder("chaos.activation.failures")
              .setDescription("Failed scenario activations")
              .build();
    }

    void record(final ChaosEvent event) {
      final String scenario = String.valueOf(event.scenarioId());
      final Map<String, String> attributes = event.attributes();
      // No scenario id on the event and failure counts: their series would grow with every
      // scenario ever seen, and the API cannot retire them.
      events.add(1, Attributes.of(EVENT_TYPE, event.type().name().toLowerCase(Locale.ROOT)));
      switch (event.type()) {
        case APPLIED -> {
          final String operation = operation(attributes);
          applications.add(
              1,
              Attributes.of(
                  SCENARIO_ID,
                  scenario,
                  OPERATION,
                  operation,
                  EFFECT,
                  effectName(attributes.getOrDefault("effectType", "unknown"))));
          final String delayNanos = attributes.get("delayNanos");
          if (delayNanos != null) {
            delay.record(
                seconds(delayNanos), Attributes.of(SCENARIO_ID, scenario, OPERATION, operation));
          }
        }
        case SKIPPED ->
            skips.add(
                1,
                Attributes.of(
                    SCENARIO_ID,
                    scenario,
                    SKIP_REASON,
                    attributes.getOrDefault("reason", "unknown")));
        case FAILED ->
            failures.add(
                1, Attributes.of(FAILURE_CATEGORY, attributes.getOrDefault("category", "unknown")));
        default -> {}
      }
    }
  }
}

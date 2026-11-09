package com.macstab.chaos.jvm.opentelemetry;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
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
 * AutoCloseable metrics = ChaosOpenTelemetry.metrics(ChaosPlatform.current(), openTelemetry);
 * AutoCloseable spans = ChaosOpenTelemetry.spanEvents(ChaosPlatform.current());
 * }</pre>
 *
 * <p>Metrics (instrumentation scope {@value #SCOPE}); every instrument has one fixed attribute set:
 *
 * <ul>
 *   <li>{@code chaos.events} — counter of every chaos event: {@code chaos.event.type}
 *   <li>{@code chaos.applications} — counter of effects that took effect: {@code
 *       chaos.scenario.id}, {@code chaos.operation}, {@code chaos.effect}
 *   <li>{@code chaos.injected.delay} — histogram (ms) of the latency the agent added: {@code
 *       chaos.scenario.id}, {@code chaos.operation}
 *   <li>{@code chaos.skips} — counter of matches the policy kept from applying: {@code
 *       chaos.scenario.id}, {@code chaos.skip.reason}
 *   <li>{@code chaos.activation.failures} — counter: {@code chaos.failure.category}
 *   <li>{@code chaos.scenarios} — gauge of registered scenarios: {@code chaos.scenario.state}
 *   <li>{@code chaos.events.dropped} — counter of events dropped by full asynchronous listener
 *       queues
 *   <li>{@code chaos.hooks.woven} — gauge of hook groups woven into JDK and library classes
 * </ul>
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

  /** Attribute: the intercepted operation, an {@code OperationType} name. */
  public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("chaos.operation");

  /** Attribute: the effect type, e.g. {@code DelayEffect}. */
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

  /** Span event attribute: the latency chaos added, in milliseconds. */
  public static final AttributeKey<Long> DELAY_MILLIS = AttributeKey.longKey("chaos.delay_ms");

  /** The name of the span event added when chaos applies to a traced call. */
  public static final String SPAN_EVENT = "chaos.applied";

  private ChaosOpenTelemetry() {}

  /**
   * Starts publishing chaos metrics through {@code openTelemetry}'s meter provider.
   *
   * @param controlPlane the control plane whose activity is measured
   * @param openTelemetry the OpenTelemetry instance
   * @return closes the instruments' callbacks and stops counting events
   */
  public static AutoCloseable metrics(
      final ChaosControlPlane controlPlane, final OpenTelemetry openTelemetry) {
    Objects.requireNonNull(controlPlane, "controlPlane");
    final Meter meter = openTelemetry.getMeter(SCOPE);
    final List<AutoCloseable> closeables = new ArrayList<>();
    closeables.add(
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
                }));
    closeables.add(
        meter
            .counterBuilder("chaos.events.dropped")
            .setDescription("Chaos events dropped by full asynchronous listener queues")
            .buildWithCallback(
                measurement -> measurement.record(longDetail(controlPlane, "droppedEvents"))));
    closeables.add(
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
                }));

    final Instruments instruments = new Instruments(meter);
    final ChaosEventListener listener = ChaosEventListener.async(instruments::record);
    controlPlane.addEventListener(listener);
    return () -> {
      controlPlane.removeEventListener(listener);
      for (final AutoCloseable closeable : closeables) {
        closeable.close();
      }
    };
  }

  /**
   * Adds a {@value #SPAN_EVENT} event to the current span whenever chaos applies to a call made
   * inside it, with the scenario id, the operation, the effect type and the injected delay.
   *
   * @param controlPlane the control plane whose applications are recorded
   * @return stops adding span events
   */
  public static AutoCloseable spanEvents(final ChaosControlPlane controlPlane) {
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
                  .put(OPERATION, attributes.getOrDefault("operation", "unknown"))
                  .put(EFFECT, attributes.getOrDefault("effectType", "unknown"));
          final String delay = attributes.get("delayMillis");
          if (delay != null) {
            builder.put(DELAY_MILLIS, Long.parseLong(delay));
          }
          span.addEvent(SPAN_EVENT, builder.build());
        };
    controlPlane.addEventListener(listener);
    return () -> controlPlane.removeEventListener(listener);
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
    private final LongHistogram delay;
    private final LongCounter skips;
    private final LongCounter failures;

    Instruments(final Meter meter) {
      events =
          meter
              .counterBuilder("chaos.events")
              .setDescription("Chaos events by type and scenario")
              .build();
      applications =
          meter
              .counterBuilder("chaos.applications")
              .setDescription("Chaos effects that took effect")
              .build();
      delay =
          meter
              .histogramBuilder("chaos.injected.delay")
              .setDescription("Latency the chaos agent added to calls")
              .setUnit("ms")
              .ofLongs()
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
          final String operation = attributes.getOrDefault("operation", "unknown");
          applications.add(
              1,
              Attributes.of(
                  SCENARIO_ID,
                  scenario,
                  OPERATION,
                  operation,
                  EFFECT,
                  attributes.getOrDefault("effectType", "unknown")));
          final String delayMillis = attributes.get("delayMillis");
          if (delayMillis != null) {
            delay.record(
                Long.parseLong(delayMillis),
                Attributes.of(SCENARIO_ID, scenario, OPERATION, operation));
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

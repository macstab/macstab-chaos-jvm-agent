package com.macstab.chaos.jvm.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosOpenTelemetry")
class ChaosOpenTelemetryTest {

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final InMemoryMetricReader reader = InMemoryMetricReader.create();
  private final InMemorySpanExporter spans = InMemorySpanExporter.create();
  private final OpenTelemetrySdk sdk =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build())
          .build();

  @AfterEach
  void tearDown() {
    runtime.close();
    sdk.close();
  }

  @Test
  @DisplayName("records applications, injected delay and scenario gauges")
  void metrics() throws Exception {
    try (AutoCloseable metrics = ChaosOpenTelemetry.metrics(runtime, sdk)) {
      runtime.activate(delayScenario("otel-delay"));
      submit();
      submit();

      // Both applications, not only the first: the listener records them one by one.
      final Collection<MetricData> collected =
          await()
              .atMost(Duration.ofSeconds(5))
              .until(reader::collectAllMetrics, all -> delayCount(all) >= 2L);

      final MetricData applications = find(collected, "chaos.applications").orElseThrow();
      assertThat(applications.getLongSumData().getPoints())
          .anySatisfy(
              point -> {
                assertThat(point.getValue()).isEqualTo(2L);
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.OPERATION))
                    .isEqualTo("executor_submit");
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.EFFECT)).isEqualTo("delay");
              });
      final MetricData delay = find(collected, "chaos.injected.delay").orElseThrow();
      assertThat(delay.getUnit()).isEqualTo("s");
      assertThat(delay.getHistogramData().getPoints())
          .singleElement()
          .satisfies(
              point -> {
                assertThat(point.getCount()).isEqualTo(2L);
                assertThat(point.getSum())
                    .isCloseTo(0.002, org.assertj.core.data.Offset.offset(1e-12));
              });
      assertThat(find(collected, "chaos.scenarios").orElseThrow().getLongGaugeData().getPoints())
          .anySatisfy(
              point -> {
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.SCENARIO_STATE))
                    .isEqualTo("active");
                assertThat(point.getValue()).isEqualTo(1L);
              });
      assertThat(find(collected, "chaos.listener.events.dropped")).isPresent();
      assertThat(find(collected, "chaos.events.dropped")).as("renamed").isEmpty();
      assertThat(find(collected, "chaos.hooks.woven")).isPresent();
    }
  }

  @Test
  @DisplayName("adds a chaos.applied event to the span the call ran in")
  void spanEvents() throws Exception {
    runtime.activate(delayScenario("otel-span"));
    try (AutoCloseable events = ChaosOpenTelemetry.spanEvents(runtime)) {
      final Span span = sdk.getTracer("test").spanBuilder("request").startSpan();
      try (Scope ignored = span.makeCurrent()) {
        submit();
      } finally {
        span.end();
      }
      submit(); // outside any span: nothing to record
    }

    final SpanData request = spans.getFinishedSpanItems().get(0);
    assertThat(request.getEvents())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getName()).isEqualTo(ChaosOpenTelemetry.SPAN_EVENT);
              assertThat(event.getAttributes().get(ChaosOpenTelemetry.SCENARIO_ID))
                  .isEqualTo("otel-span");
              assertThat(event.getAttributes().get(ChaosOpenTelemetry.DELAY)).isEqualTo(0.001);
            });
  }

  @Test
  @DisplayName("counts the matches a policy kept from applying, and failed activations, by reason")
  void skipsAndFailures() throws Exception {
    try (AutoCloseable metrics = ChaosOpenTelemetry.metrics(runtime, sdk)) {
      runtime.activate(
          ChaosScenario.builder("otel-warm-up")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(
                  com.macstab.chaos.jvm.api.ActivationPolicy.builder()
                      .activateAfterMatches(5)
                      .build())
              .build());
      submit();
      submit();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  runtime.activate(
                      ChaosScenario.builder("otel-invalid")
                          .scope(ChaosScenario.ScenarioScope.JVM)
                          .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                          .effect(ChaosEffect.tornWrite())
                          .build()))
          .isInstanceOf(com.macstab.chaos.jvm.api.ChaosValidationException.class)
          .hasMessageContaining("TornWriteEffect applies only to");

      final MetricData skips = metric("chaos.skips");
      assertThat(skips.getLongSumData().getPoints())
          .singleElement()
          .satisfies(
              point -> {
                assertThat(point.getValue()).isEqualTo(2L);
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.SKIP_REASON))
                    .isEqualTo("warmUp");
              });
      final MetricData failures = metric("chaos.activation.failures");
      assertThat(failures.getLongSumData().getPoints())
          .singleElement()
          .satisfies(
              point -> {
                assertThat(point.getValue()).isEqualTo(1L);
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.FAILURE_CATEGORY))
                    .isEqualTo("INVALID_CONFIGURATION");
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.SCENARIO_ID)).isNull();
              });
      assertThat(metric("chaos.events").getLongSumData().getPoints())
          .isNotEmpty()
          .allSatisfy(point -> assertThat(point.getAttributes().size()).isEqualTo(1));
      assertThat(metric("chaos.events").getDescription())
          .as("it carries the event type only, no scenario")
          .isEqualTo("Chaos events by type");
    }
  }

  @Test
  @DisplayName("span events are added only for applied effects, without a delay when there is none")
  void spanEventsOnlyForApplications() throws Exception {
    runtime.activate(
        ChaosScenario.builder("otel-suppress")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.suppress())
            .build());
    try (AutoCloseable events = ChaosOpenTelemetry.spanEvents(runtime)) {
      final Span span = sdk.getTracer("test").spanBuilder("request").startSpan();
      try (Scope ignored = span.makeCurrent()) {
        submit();
        // Lifecycle events reach the listener too (REGISTERED, STARTED, STOPPED); they add
        // nothing to the span.
        runtime.activate(delayScenario("otel-lifecycle-only")).close();
      } finally {
        span.end();
      }
    }
    assertThat(spans.getFinishedSpanItems().get(0).getEvents())
        .singleElement()
        .satisfies(
            event -> assertThat(event.getAttributes().get(ChaosOpenTelemetry.DELAY)).isNull());
  }

  /** The named metric, once the asynchronous listener has delivered what it records. */
  private MetricData metric(final String name) {
    return await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> find(reader.collectAllMetrics(), name), Optional::isPresent)
        .orElseThrow();
  }

  private static long delayCount(final Collection<MetricData> metrics) {
    return find(metrics, "chaos.injected.delay")
        .map(
            delay ->
                delay.getHistogramData().getPoints().stream()
                    .mapToLong(point -> point.getCount())
                    .sum())
        .orElse(0L);
  }

  private void submit() {
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
  }

  private static Optional<MetricData> find(
      final Collection<MetricData> metrics, final String name) {
    return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst();
  }

  private static ChaosScenario delayScenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
  }
}

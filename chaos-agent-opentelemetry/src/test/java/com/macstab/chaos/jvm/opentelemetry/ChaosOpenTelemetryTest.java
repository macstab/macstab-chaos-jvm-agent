package com.macstab.chaos.jvm.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

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

      final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      Collection<MetricData> collected = reader.collectAllMetrics();
      while (find(collected, "chaos.injected.delay").isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(10L);
        collected = reader.collectAllMetrics();
      }

      final MetricData applications = find(collected, "chaos.applications").orElseThrow();
      assertThat(applications.getLongSumData().getPoints())
          .anySatisfy(
              point -> {
                assertThat(point.getValue()).isEqualTo(2L);
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.OPERATION))
                    .isEqualTo("EXECUTOR_SUBMIT");
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.EFFECT))
                    .isEqualTo("DelayEffect");
              });
      final MetricData delay = find(collected, "chaos.injected.delay").orElseThrow();
      assertThat(delay.getUnit()).isEqualTo("ms");
      assertThat(delay.getHistogramData().getPoints())
          .singleElement()
          .satisfies(
              point -> {
                assertThat(point.getCount()).isEqualTo(2L);
                assertThat(point.getSum()).isEqualTo(2.0);
              });
      assertThat(find(collected, "chaos.scenarios").orElseThrow().getLongGaugeData().getPoints())
          .anySatisfy(
              point -> {
                assertThat(point.getAttributes().get(ChaosOpenTelemetry.SCENARIO_STATE))
                    .isEqualTo("active");
                assertThat(point.getValue()).isEqualTo(1L);
              });
      assertThat(find(collected, "chaos.events.dropped")).isPresent();
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
              assertThat(event.getAttributes().get(ChaosOpenTelemetry.DELAY_MILLIS)).isEqualTo(1L);
            });
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

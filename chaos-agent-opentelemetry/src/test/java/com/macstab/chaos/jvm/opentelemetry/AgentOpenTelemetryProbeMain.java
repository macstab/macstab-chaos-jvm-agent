package com.macstab.chaos.jvm.opentelemetry;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs in a JVM started with {@code -javaagent:}: starts {@link ChaosOpenTelemetry#metrics} and
 * {@link ChaosOpenTelemetry#spanEvents} on {@code ChaosPlatform.current()}, makes three {@code
 * ThreadPoolExecutor} submissions the agent delays inside a span, and prints what the SDK recorded.
 */
public final class AgentOpenTelemetryProbeMain {

  static final String SCENARIO_ID = "agent-otel-delay";
  static final int CALLS = 3;

  private AgentOpenTelemetryProbeMain() {}

  public static void main(final String[] args) throws Exception {
    final ChaosControlPlane controlPlane = ChaosPlatform.current();
    final InMemoryMetricReader reader = InMemoryMetricReader.create();
    final InMemorySpanExporter spans = InMemorySpanExporter.create();
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try (OpenTelemetrySdk sdk =
            OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
                .setTracerProvider(
                    SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans))
                        .build())
                .build();
        ChaosOpenTelemetry.Registration metrics = ChaosOpenTelemetry.metrics(controlPlane, sdk);
        ChaosOpenTelemetry.Registration events = ChaosOpenTelemetry.spanEvents(controlPlane);
        ChaosActivationHandle handle =
            controlPlane.activate(
                ChaosScenario.builder(SCENARIO_ID)
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(20)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      final Span span = sdk.getTracer("probe").spanBuilder("request").startSpan();
      try (Scope ignored = span.makeCurrent()) {
        for (int i = 0; i < CALLS; i++) {
          executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        }
      } finally {
        span.end();
      }
      System.out.println("handleApplied=" + handle.appliedCount());

      // The metrics are counted on an agent thread: wait until every application is in.
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      long applications = 0;
      while (applications < CALLS && System.nanoTime() < deadline) {
        applications = applications(reader.collectAllMetrics());
        if (applications < CALLS) {
          Thread.sleep(20);
        }
      }
      System.out.println("applications=" + applications);

      final java.util.List<EventData> spanEvents =
          spans.getFinishedSpanItems().get(0).getEvents().stream()
              .filter(event -> event.getName().equals(ChaosOpenTelemetry.SPAN_EVENT))
              .toList();
      System.out.println("spanEvents=" + spanEvents.size());
      System.out.println(
          "spanEventScenario="
              + spanEvents.get(0).getAttributes().get(ChaosOpenTelemetry.SCENARIO_ID));
      System.out.println(
          "spanEventOperation="
              + spanEvents.get(0).getAttributes().get(ChaosOpenTelemetry.OPERATION));
    } finally {
      executor.shutdownNow();
    }
  }

  private static long applications(final Collection<MetricData> metrics) {
    return metrics.stream()
        .filter(metric -> metric.getName().equals("chaos.applications"))
        .flatMap(metric -> metric.getLongSumData().getPoints().stream())
        .filter(
            point ->
                SCENARIO_ID.equals(point.getAttributes().get(ChaosOpenTelemetry.SCENARIO_ID))
                    && "executor_submit"
                        .equals(point.getAttributes().get(ChaosOpenTelemetry.OPERATION)))
        .mapToLong(point -> point.getValue())
        .sum();
  }
}

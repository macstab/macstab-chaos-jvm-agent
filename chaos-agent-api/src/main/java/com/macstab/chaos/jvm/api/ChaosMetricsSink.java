package com.macstab.chaos.jvm.api;

import java.time.Duration;
import java.util.Map;

/**
 * Bridge for emitting chaos activity as metrics to an external monitoring system.
 *
 * <p>Implement this interface to route chaos metrics to a metrics backend, and pass the
 * implementation to the {@code ChaosRuntime(Clock, ChaosMetricsSink)} constructor. The default is
 * {@link #NOOP}: all calls are discarded.
 *
 * <p><b>Metrics emitted by the runtime:</b>
 *
 * <ul>
 *   <li>{@code chaos.effect.applied} (counter) — incremented each time an effect fires. Tags:
 *       {@code scenarioId}, {@code operation}.
 * </ul>
 *
 * <p>{@link #recordDuration} is never called by the runtime.
 *
 * <p><b>Implementation contract:</b> all methods must be non-blocking and must not throw. They are
 * called on the thread performing the intercepted JVM operation.
 *
 * @deprecated a second metrics API beside the events: the same count is {@code chaos.applications}
 *     of {@code chaos-agent-micrometer}'s {@code ChaosMeterBinder} and of {@code
 *     chaos-agent-opentelemetry}, which a {@link ChaosEventListener} on {@code APPLIED} events can
 *     also produce for any other backend. This interface and {@code chaos.effect.applied} will be
 *     removed.
 */
@Deprecated(since = "2.0.0", forRemoval = true)
public interface ChaosMetricsSink {

  /**
   * A no-op sink that discards all metric calls. This is the default when no custom sink is
   * configured.
   */
  ChaosMetricsSink NOOP =
      new ChaosMetricsSink() {
        @Override
        public void increment(final String name, final Map<String, String> tags) {}

        @Override
        public void recordDuration(
            final String name, final Duration duration, final Map<String, String> tags) {}
      };

  /**
   * Increments a counter metric by one.
   *
   * @param name the metric name; e.g., {@code "chaos.effect.applied"}
   * @param tags additional dimensions; never null but may be empty
   */
  void increment(String name, Map<String, String> tags);

  /**
   * Records a timing/duration metric sample.
   *
   * @param name the metric name
   * @param duration the observed duration; never null
   * @param tags additional dimensions; never null but may be empty
   */
  void recordDuration(String name, Duration duration, Map<String, String> tags);
}

package io.macstab.chaos.api;

import java.time.Duration;
import java.util.Map;

/**
 * Bridge for emitting chaos activity as metrics to an external monitoring system.
 *
 * <p>Implement this interface to route chaos metrics to Micrometer, Prometheus, Dropwizard Metrics,
 * StatsD, or any other metrics backend. Pass the implementation to the {@code ChaosRuntime}
 * constructor on startup.
 *
 * <p>The default implementation is {@link #NOOP}: all calls are discarded. In production
 * environments, replace it to observe chaos activity in your dashboards alongside application
 * metrics.
 *
 * <p><b>Metrics emitted by the runtime:</b>
 *
 * <ul>
 *   <li>{@code chaos.effect.applied} (counter) — incremented each time an effect fires. Tags:
 *       {@code scenarioId}, {@code operation}.
 * </ul>
 *
 * <p><b>Implementation contract:</b> all methods must be non-blocking and must not throw. They are
 * called on the thread performing the intercepted JVM operation.
 */
public interface ChaosMetricsSink {

  /**
   * A no-op sink that discards all metric calls. This is the default when no custom sink is
   * configured.
   */
  ChaosMetricsSink NOOP =
      new ChaosMetricsSink() {
        @Override
        public void increment(String name, Map<String, String> tags) {}

        @Override
        public void recordDuration(String name, Duration duration, Map<String, String> tags) {}
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
   * @param name the metric name; e.g., {@code "chaos.gate.block.duration"}
   * @param duration the observed duration; never null
   * @param tags additional dimensions; never null but may be empty
   */
  void recordDuration(String name, Duration duration, Map<String, String> tags);
}

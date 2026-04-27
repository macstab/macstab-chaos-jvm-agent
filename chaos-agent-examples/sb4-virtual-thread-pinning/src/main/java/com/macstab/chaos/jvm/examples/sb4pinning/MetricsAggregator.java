package com.macstab.chaos.jvm.examples.sb4pinning;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Aggregates named metric counts in a thread-safe manner. */
@Component
public class MetricsAggregator {

  private final Map<String, Long> counts = new HashMap<>();
  private final Object lock = new Object();

  /** Creates a new MetricsAggregator. */
  public MetricsAggregator() {}

  /**
   * Records the given metric.
   *
   * @param metric the metric name to record
   */
  public void record(final String metric) {
    synchronized (lock) {
      counts.merge(metric, 1L, Long::sum);
      Thread.yield();
    }
  }

  /**
   * Returns a snapshot of all recorded metric counts.
   *
   * @return map of metric name to count
   */
  public Map<String, Long> snapshot() {
    synchronized (lock) {
      return new HashMap<>(counts);
    }
  }
}

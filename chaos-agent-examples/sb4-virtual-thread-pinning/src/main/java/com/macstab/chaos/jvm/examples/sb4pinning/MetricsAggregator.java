package com.macstab.chaos.jvm.examples.sb4pinning;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MetricsAggregator {

  private final Map<String, Long> counts = new HashMap<>();
  private final Object lock = new Object();

  public void record(final String metric) {
    synchronized (lock) {
      counts.merge(metric, 1L, Long::sum);
      Thread.yield();
    }
  }

  public Map<String, Long> snapshot() {
    synchronized (lock) {
      return new HashMap<>(counts);
    }
  }
}

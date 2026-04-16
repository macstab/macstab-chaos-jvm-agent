package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEvent;
import com.macstab.chaos.api.ChaosEventListener;
import com.macstab.chaos.api.ChaosMetricsSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

final class ObservabilityBus {
  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private final List<ChaosEventListener> listeners = new CopyOnWriteArrayList<>();
  private final ChaosMetricsSink metricsSink;

  ObservabilityBus(final ChaosMetricsSink metricsSink) {
    this.metricsSink = metricsSink;
    listeners.add(this::log);
  }

  void addListener(final ChaosEventListener listener) {
    listeners.add(listener);
  }

  void publish(
      final ChaosEvent.Type type,
      final String scenarioId,
      final String message,
      final Map<String, String> attributes) {
    final ChaosEvent event = new ChaosEvent(Instant.now(), type, scenarioId, message, attributes);
    for (ChaosEventListener listener : listeners) {
      listener.onEvent(event);
    }
  }

  void incrementMetric(final String name, final Map<String, String> tags) {
    metricsSink.increment(name, tags);
  }

  /**
   * Task 5: Route events to the appropriate log level.
   *
   * <ul>
   *   <li>{@link ChaosEvent.Type#STARTED} and {@link ChaosEvent.Type#STOPPED} are lifecycle
   *       milestones worth seeing in normal operation → INFO.
   *   <li>{@link ChaosEvent.Type#APPLIED} and {@link ChaosEvent.Type#RELEASED} are high-frequency
   *       per-invocation events → FINE to avoid flooding test output and production logs.
   * </ul>
   */
  private void log(final ChaosEvent event) {
    final String formatted =
        "type="
            + event.type()
            + " scenarioId="
            + event.scenarioId()
            + " message=\""
            + event.message()
            + "\" attributes="
            + event.attributes();
    switch (event.type()) {
      case STARTED, STOPPED -> LOGGER.info(formatted);
      case APPLIED, RELEASED -> LOGGER.fine(formatted);
    }
  }
}

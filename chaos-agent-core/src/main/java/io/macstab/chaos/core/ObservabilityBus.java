package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEvent;
import io.macstab.chaos.api.ChaosEventListener;
import io.macstab.chaos.api.ChaosMetricsSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

final class ObservabilityBus {
  private static final Logger LOGGER = Logger.getLogger("io.macstab.chaos");

  private final List<ChaosEventListener> listeners = new CopyOnWriteArrayList<>();
  private final ChaosMetricsSink metricsSink;

  ObservabilityBus(ChaosMetricsSink metricsSink) {
    this.metricsSink = metricsSink;
    listeners.add(this::log);
  }

  void addListener(ChaosEventListener listener) {
    listeners.add(listener);
  }

  void publish(
      ChaosEvent.Type type, String scenarioId, String message, Map<String, String> attributes) {
    ChaosEvent event = new ChaosEvent(Instant.now(), type, scenarioId, message, attributes);
    for (ChaosEventListener listener : listeners) {
      listener.onEvent(event);
    }
  }

  void incrementMetric(String name, Map<String, String> tags) {
    metricsSink.increment(name, tags);
  }

  private void log(ChaosEvent event) {
    LOGGER.info(
        () ->
            "type="
                + event.type()
                + " scenarioId="
                + event.scenarioId()
                + " message=\""
                + event.message()
                + "\" attributes="
                + event.attributes());
  }
}

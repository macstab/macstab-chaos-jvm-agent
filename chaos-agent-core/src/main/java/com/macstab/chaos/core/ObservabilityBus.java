package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEvent;
import com.macstab.chaos.api.ChaosEventListener;
import com.macstab.chaos.api.ChaosMetricsSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Synchronous event bus that dispatches {@link com.macstab.chaos.api.ChaosEvent}s to all registered
 * {@link com.macstab.chaos.api.ChaosEventListener}s.
 *
 * <p>Events are published in-band on the thread that triggers them (i.e., the application thread
 * that hit the instrumentation point). Listeners execute synchronously and must be fast.
 * Long-running listeners should schedule work onto a separate executor.
 *
 * <h2>Default listener</h2>
 *
 * <p>A built-in default listener is registered at construction time:
 *
 * <ul>
 *   <li>{@code STARTED} and {@code STOPPED} events are logged at {@code INFO}.
 *   <li>{@code APPLIED} and {@code RELEASED} events are logged at {@code FINE}.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Listener registration and removal use {@link java.util.concurrent.CopyOnWriteArrayList}, which
 * is safe for concurrent reads and infrequent writes. {@link #publish} iterates a snapshot of the
 * listener list at the time of the call; listeners added during a publish call will not receive
 * that event.
 */
final class ObservabilityBus {
  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private final List<ChaosEventListener> listeners = new CopyOnWriteArrayList<>();
  private final ChaosMetricsSink metricsSink;

  /**
   * Creates a new bus backed by the given metrics sink.
   *
   * <p>A default logging listener is registered immediately; it routes {@link
   * ChaosEvent.Type#STARTED}/{@link ChaosEvent.Type#STOPPED} to {@code INFO} and {@link
   * ChaosEvent.Type#APPLIED}/{@link ChaosEvent.Type#RELEASED} to {@code FINE}.
   *
   * @param metricsSink the sink to which metric increments are forwarded; must not be {@code null}
   */
  ObservabilityBus(final ChaosMetricsSink metricsSink) {
    this.metricsSink = metricsSink;
    listeners.add(this::log);
  }

  /**
   * Registers {@code listener} to receive all future events published on this bus.
   *
   * <p>The listener is appended to the end of the listener list. If the same instance is added more
   * than once it will receive duplicate notifications.
   *
   * @param listener the listener to register; must not be {@code null}
   */
  void addListener(final ChaosEventListener listener) {
    listeners.add(listener);
  }

  /**
   * Constructs a {@link ChaosEvent} and dispatches it synchronously to every registered listener.
   *
   * <p>The event timestamp is set to {@link java.time.Instant#now()} at the time of the call.
   * Listeners are notified in registration order. Any exception thrown by a listener propagates to
   * the caller; subsequent listeners in the list will not be notified if an earlier one throws.
   *
   * @param type the event type; must not be {@code null}
   * @param scenarioId the ID of the scenario that generated this event; must not be {@code null}
   * @param message a human-readable description of the event
   * @param attributes additional key/value metadata associated with the event; may be empty but
   *     must not be {@code null}
   */
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

  /**
   * Increments a named counter in the configured {@link ChaosMetricsSink}.
   *
   * <p>Delegates directly to {@link ChaosMetricsSink#increment(String, Map)}. Tag maps should be
   * kept small; allocating a new map per invocation on the hot path is discouraged.
   *
   * @param name the metric name to increment; must not be {@code null}
   * @param tags dimensional labels to attach to the counter; may be empty but must not be {@code
   *     null}
   */
  void incrementMetric(final String name, final Map<String, String> tags) {
    metricsSink.increment(name, tags);
  }

  /**
   * Default listener that routes events to the JUL logger at an appropriate level.
   *
   * <ul>
   *   <li>{@link ChaosEvent.Type#STARTED} and {@link ChaosEvent.Type#STOPPED} are lifecycle
   *       milestones worth seeing in normal operation and are emitted at {@code INFO}.
   *   <li>{@link ChaosEvent.Type#APPLIED} and {@link ChaosEvent.Type#RELEASED} are high-frequency
   *       per-invocation events and are emitted at {@code FINE} to avoid flooding production logs.
   * </ul>
   *
   * @param event the event to log; must not be {@code null}
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

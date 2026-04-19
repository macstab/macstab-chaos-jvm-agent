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
   * Iterates the registered listeners and closes any that implement {@link AutoCloseable}.
   *
   * <p>Called from {@link ChaosControlPlaneImpl#close()} so that listeners which install JVM-wide
   * side effects on construction (for example {@code JfrChaosEventSink}, which registers a {@link
   * jdk.jfr.FlightRecorder#addPeriodicEvent} hook) can unwind those effects deterministically when
   * the control plane is torn down. Previously the JFR sink's {@code close()} was never invoked, so
   * every test-suite that spun up a new {@code ChaosRuntime} accumulated a stale periodic hook on
   * the JFR singleton, each one pinning the dead runtime via its captured diagnostics reference.
   * Failures are swallowed and logged — a mis-behaving listener's close() must not mask a sibling's
   * cleanup or an application's shutdown sequence.
   */
  void close() {
    for (final ChaosEventListener listener : listeners) {
      if (listener instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (final Exception ex) {
          LOGGER.warning(
              () ->
                  "ChaosEventListener threw during close; continuing. listener="
                      + listener.getClass().getName()
                      + " error="
                      + ex);
        }
      }
    }
    listeners.clear();
  }

  /**
   * Constructs a {@link ChaosEvent} and dispatches it synchronously to every registered listener.
   *
   * <p>The event timestamp is captured as the first action of this method via {@link
   * Instant#now()}. When publish is invoked from inside the dispatcher's reentrancy guard, that
   * {@code Instant.now()} call is already shielded from chaos clock-skew adjustment (the adjustment
   * advice re-enters the dispatcher, which returns the real instant on reentrancy). When publish is
   * invoked from outside a dispatch path (e.g. lifecycle STARTED / STOPPED from {@code activate()}
   * / {@code stop()}), the adjustment would otherwise apply and event timestamps would silently
   * reflect the app-perceived (skewed) clock — desirable for the user's code, undesirable for the
   * operator reading chaos diagnostics. Capturing the timestamp first puts it as close as possible
   * to the true event moment and keeps it consistent across call sites.
   *
   * <p>Listeners are notified in registration order. A listener that throws is isolated: the
   * exception is logged at WARNING level and delivery continues to the remaining listeners. This
   * guarantee is critical in an agent context where publish runs on application threads inside
   * ByteBuddy advice — a buggy custom listener must never propagate into application code.
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
    // Capture the timestamp first, before any other work in this method, so that event ordering
    // reflects publish()-arrival order as closely as possible even under listener backpressure.
    final Instant timestamp = Instant.now();
    final ChaosEvent event = new ChaosEvent(timestamp, type, scenarioId, message, attributes);
    for (ChaosEventListener listener : listeners) {
      try {
        listener.onEvent(event);
      } catch (Throwable ex) {
        // Catch Throwable, not Exception. This runs on application threads inside ByteBuddy
        // advice — an Error escaping here would unwind straight into user code. StackOverflow
        // and OOM from a pathological listener would each trigger that path, as would every
        // ThreadDeath raised by JVM shutdown. Swallow, log, and keep going; never let a
        // listener pollute application semantics.
        LOGGER.warning(
            () ->
                "ChaosEventListener threw during publish; delivery continues."
                    + " event="
                    + type
                    + " scenarioId="
                    + scenarioId
                    + " listener="
                    + listener.getClass().getName()
                    + " error="
                    + ex);
      }
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
    try {
      metricsSink.increment(name, tags);
    } catch (final Throwable ex) {
      // Same rationale as publish() above: this runs on application threads inside ByteBuddy
      // advice. A custom sink (e.g. Micrometer) that loses its registry connection, is closed
      // during JVM shutdown, or trips an internal assertion would otherwise unwind straight
      // into user code at an opaque JDK call site like Thread.sleep or HttpClient.send. Swallow
      // everything, log once at WARNING, and keep the application semantics clean.
      LOGGER.warning(
          () ->
              "ChaosMetricsSink threw during incrementMetric; metric dropped."
                  + " name="
                  + name
                  + " sink="
                  + metricsSink.getClass().getName()
                  + " error="
                  + ex);
    }
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
      // FAILED is visibility-critical: this is how operators see that a scenario's effect
      // application itself errored. Log at WARNING so it surfaces above normal INFO traffic.
      case FAILED -> LOGGER.warning(formatted);
      // REGISTERED fires once per scenario at plan load; FINE keeps it visible to debug/audit
      // tooling without cluttering production logs. SKIPPED can be high frequency (policy rate
      // limiting, probability misses) so push it to FINER.
      case REGISTERED -> LOGGER.fine(formatted);
      case SKIPPED -> LOGGER.finer(formatted);
    }
  }
}

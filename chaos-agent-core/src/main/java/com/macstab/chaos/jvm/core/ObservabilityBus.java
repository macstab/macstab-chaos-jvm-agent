package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synchronous event bus that dispatches {@link com.macstab.chaos.jvm.api.ChaosEvent}s to all
 * registered {@link com.macstab.chaos.jvm.api.ChaosEventListener}s.
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
  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  /**
   * Scenarios whose plan set {@code structuredLoggingEnabled=false}: their events are not logged.
   */
  private final java.util.Set<String> loggingMuted =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  private final List<ChaosEventListener> listeners = new CopyOnWriteArrayList<>();

  /** What each registered listener was registered as: itself, or its asynchronous delivery. */
  private final List<Registration> registrations = new CopyOnWriteArrayList<>();

  /** Events dropped because an asynchronous listener's queue was full. */
  private final java.util.concurrent.atomic.AtomicLong droppedEvents =
      new java.util.concurrent.atomic.AtomicLong();

  private record Registration(ChaosEventListener registered, ChaosEventListener delivered) {}

  /**
   * Listeners that have thrown at least once: their first failure is logged at WARNING, later ones
   * at FINE, so a listener that throws on every event does not flood the log. By identity.
   */
  private final java.util.Set<ChaosEventListener> failedListeners =
      java.util.Collections.synchronizedSet(
          java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

  private final ChaosMetricsSink metricsSink;

  /**
   * Creates a new bus backed by the given metrics sink.
   *
   * <p>A default logging listener is registered immediately; it routes {@link
   * ChaosEvent.Type#STARTED}/{@link ChaosEvent.Type#STOPPED}/{@link ChaosEvent.Type#INACTIVE} to
   * {@code INFO} and {@link ChaosEvent.Type#APPLIED}/{@link ChaosEvent.Type#RELEASED} to {@code
   * FINE}.
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
    final ChaosEventListener delivered =
        listener instanceof com.macstab.chaos.jvm.api.AsyncChaosEventListener async
            ? new AsyncDelivery(async.delegate(), droppedEvents)
            : listener;
    registrations.add(new Registration(listener, delivered));
    listeners.add(delivered);
  }

  /**
   * Removes a listener registered with {@link #addListener}, stopping its delivery thread if it was
   * asynchronous.
   *
   * @param listener the listener as it was registered
   * @return {@code true} if it was registered
   */
  @SuppressWarnings("ReferenceEquality") // a listener is the instance that was registered
  boolean removeListener(final ChaosEventListener listener) {
    // Copy-on-write lists: removing while iterating is safe. Identity, not equals: a listener is
    // the instance that was registered.
    for (final Registration registration : List.copyOf(registrations)) {
      if (registration.registered() == listener && registrations.remove(registration)) {
        listeners.remove(registration.delivered());
        failedListeners.remove(registration.delivered());
        if (registration.delivered() instanceof AsyncDelivery delivery) {
          delivery.close();
        }
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a listener other than the built-in logging one is registered: events published only for
   * observers ({@code SKIPPED}) are not built otherwise.
   *
   * @return {@code true} when someone listens
   */
  boolean hasListeners() {
    return !registrations.isEmpty();
  }

  /**
   * Returns the number of events dropped because an asynchronous listener fell behind.
   *
   * @return the dropped events
   */
  long droppedEvents() {
    return droppedEvents.get();
  }

  /**
   * Delivers events to a listener on an agent-owned thread through a bounded queue. The thread runs
   * with the dispatch depth raised, so chaos never applies to the delivery itself.
   *
   * <p>{@link #close()} never interrupts the listener: the event in progress completes, the events
   * still queued are counted as dropped, and the delivery thread ends after it. The close waits for
   * it at most {@link #CLOSE_JOIN_MILLIS}, and not at all on the delivery thread itself (a listener
   * that removes itself, or closes the control plane, from {@code onEvent}).
   */
  private static final class AsyncDelivery implements ChaosEventListener, AutoCloseable {
    private static final int CAPACITY = 10_000;

    /** How long {@link #close()} waits for the event in progress. */
    static final long CLOSE_JOIN_MILLIS = 5_000L;

    /** Queued by {@link #close()} to end the delivery thread; compared by identity. */
    private static final ChaosEvent END =
        new ChaosEvent(Instant.EPOCH, ChaosEvent.Type.SKIPPED, "end", "end", Map.of());

    private final java.util.concurrent.BlockingQueue<ChaosEvent> queue =
        new java.util.concurrent.ArrayBlockingQueue<>(CAPACITY);
    private final ChaosEventListener delegate;
    private final java.util.concurrent.atomic.AtomicLong dropped;
    private final Thread thread;
    private volatile boolean closed;

    AsyncDelivery(
        final ChaosEventListener delegate, final java.util.concurrent.atomic.AtomicLong dropped) {
      this.delegate = delegate;
      this.dropped = dropped;
      this.thread = new Thread(this::deliver, "macstab-chaos-events");
      this.thread.setDaemon(true);
      ChaosDispatcher.incrementDispatchDepth();
      try {
        this.thread.start();
      } finally {
        ChaosDispatcher.decrementDispatchDepth();
      }
    }

    @Override
    public void onEvent(final ChaosEvent event) {
      if (closed || !queue.offer(event)) {
        dropped.incrementAndGet();
      }
    }

    @SuppressWarnings("ReferenceEquality") // END is the one sentinel instance
    private void deliver() {
      ChaosDispatcher.incrementDispatchDepth();
      boolean failedBefore = false;
      try {
        while (true) {
          final ChaosEvent event;
          try {
            event = queue.take();
          } catch (final InterruptedException interrupted) {
            return;
          }
          if (event == END) {
            // Offered by a racing publish after close() drained the queue.
            dropped.addAndGet(queue.drainTo(new java.util.ArrayList<>()));
            return;
          }
          try {
            delegate.onEvent(event);
          } catch (final Throwable listenerFailure) {
            LOGGER.log(
                failedBefore ? Level.FINE : Level.WARNING,
                () ->
                    "asynchronous ChaosEventListener threw; delivery continues. listener="
                        + delegate.getClass().getName()
                        + " error="
                        + listenerFailure);
            failedBefore = true;
          }
        }
      } finally {
        ChaosDispatcher.decrementDispatchDepth();
      }
    }

    @Override
    public void close() {
      closed = true;
      final java.util.List<ChaosEvent> discarded = new java.util.ArrayList<>();
      do {
        queue.drainTo(discarded);
      } while (!queue.offer(END));
      dropped.addAndGet(discarded.size());
      if (Thread.currentThread() != thread) {
        awaitDeliveryEnd();
      }
      if (delegate instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (final Exception ignored) {
          // closing a listener must not fail the control plane's shutdown
        }
      }
    }

    /** Waits for the event in progress, at most {@link #CLOSE_JOIN_MILLIS}; keeps an interrupt. */
    private void awaitDeliveryEnd() {
      ChaosDispatcher.incrementDispatchDepth();
      try {
        thread.join(CLOSE_JOIN_MILLIS);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        ChaosDispatcher.decrementDispatchDepth();
      }
      if (thread.isAlive()) {
        LOGGER.warning(
            () ->
                "chaos-agent: asynchronous ChaosEventListener "
                    + delegate.getClass().getName()
                    + " did not return within "
                    + CLOSE_JOIN_MILLIS
                    + " ms of its removal; its delivery thread ends when it does");
      }
    }
  }

  /**
   * Iterates the registered listeners and closes any that implement {@link AutoCloseable}.
   *
   * <p>Called from {@link ChaosControlPlaneImpl#close()} so that listeners which install JVM-wide
   * side effects on construction (for example {@code JfrChaosEventSink}, which registers a {@link
   * jdk.jfr.FlightRecorder#addPeriodicEvent} hook) can unwind those effects deterministically when
   * the control plane is torn down. Without it, every new {@code ChaosRuntime} a test suite spins
   * up would leave a stale periodic hook on the JFR singleton, each one pinning the dead runtime
   * via its captured diagnostics reference. Failures are swallowed and logged — a mis-behaving
   * listener's close() must not mask a sibling's cleanup or an application's shutdown sequence.
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
    registrations.clear();
  }

  /**
   * Turns the structured logging of one scenario's events off or back on.
   *
   * @param scenarioId the scenario id
   * @param muted {@code true} to stop logging its events
   */
  void muteLogging(final String scenarioId, final boolean muted) {
    if (muted) {
      loggingMuted.add(scenarioId);
    } else {
      loggingMuted.remove(scenarioId);
    }
  }

  /**
   * Constructs a {@link ChaosEvent} and dispatches it synchronously to every registered listener.
   *
   * <p>The event timestamp is the real time, captured first: read with the dispatch depth raised,
   * so a clock-skew scenario never applies to it, whether publish runs inside a dispatch (APPLIED)
   * or on a control-plane thread (REGISTERED, STARTED, STOPPED, FAILED).
   *
   * <p>Listeners are notified in registration order. A listener that throws is isolated: the
   * exception is logged (its first one at WARNING, later ones at FINE) and delivery continues to
   * the remaining listeners. This guarantee is critical in an agent context where publish runs on
   * application threads inside ByteBuddy advice — a buggy custom listener must never propagate into
   * application code.
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
    final Instant timestamp = ChaosDispatcher.unskewedInstant(java.time.Clock.systemUTC());
    final ChaosEvent event = new ChaosEvent(timestamp, type, scenarioId, message, attributes);
    for (final ChaosEventListener listener : listeners) {
      try {
        listener.onEvent(event);
      } catch (final Throwable ex) {
        // Catch Throwable, not Exception. This runs on application threads inside ByteBuddy
        // advice — an Error escaping here would unwind straight into user code. StackOverflow
        // and OOM from a pathological listener would each trigger that path, as would every
        // ThreadDeath raised by JVM shutdown. Swallow, log, and keep going; never let a
        // listener pollute application semantics. Only a listener's first failure is a WARNING.
        LOGGER.log(
            failedListeners.add(listener) ? Level.WARNING : Level.FINE,
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
   *   <li>{@link ChaosEvent.Type#STARTED}, {@link ChaosEvent.Type#STOPPED} and {@link
   *       ChaosEvent.Type#INACTIVE} are lifecycle milestones worth seeing in normal operation and
   *       are emitted at {@code INFO}.
   *   <li>{@link ChaosEvent.Type#APPLIED} and {@link ChaosEvent.Type#RELEASED} are high-frequency
   *       per-invocation events and are emitted at {@code FINE} to avoid flooding production logs.
   * </ul>
   *
   * <p>The line is {@code type=X scenarioId="…" message="…" attributes={k="v", …}}, the attributes
   * in the event's order and every id, message, key and value {@link #escape escaped}, so a value
   * cannot break the line or forge another.
   *
   * @param event the event to log; must not be {@code null}
   */
  private void log(final ChaosEvent event) {
    if (event.scenarioId() != null && loggingMuted.contains(event.scenarioId())) {
      return;
    }
    final StringBuilder line = new StringBuilder(128);
    line.append("type=").append(event.type()).append(" scenarioId=\"");
    escape(event.scenarioId(), line);
    line.append("\" message=\"");
    escape(event.message(), line);
    line.append("\" attributes={");
    String separator = "";
    for (final Map.Entry<String, String> attribute : event.attributes().entrySet()) {
      line.append(separator);
      escape(attribute.getKey(), line);
      line.append("=\"");
      escape(attribute.getValue(), line);
      line.append('"');
      separator = ", ";
    }
    final String formatted = line.append('}').toString();
    switch (event.type()) {
      case STARTED, STOPPED, INACTIVE -> LOGGER.info(formatted);
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

  /**
   * Appends {@code text} to {@code line} escaped for a quoted log field: {@code \\}, {@code \"},
   * {@code \r} and {@code \n}, and {@code \}{@code uXXXX} for the other C0 controls, DEL, the C1
   * controls and the line and paragraph separators (U+2028, U+2029).
   */
  private static void escape(final String text, final StringBuilder line) {
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      switch (c) {
        case '\\' -> line.append("\\\\");
        case '"' -> line.append("\\\"");
        case '\r' -> line.append("\\r");
        case '\n' -> line.append("\\n");
        default -> {
          if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == '\u2028' || c == '\u2029') {
            line.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
          } else {
            line.append(c);
          }
        }
      }
    }
  }
}

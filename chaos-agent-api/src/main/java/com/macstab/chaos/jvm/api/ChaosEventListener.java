package com.macstab.chaos.jvm.api;

/**
 * Observer for all chaos lifecycle and effect-application events.
 *
 * <p>Register instances via {@link ChaosControlPlane#addEventListener(ChaosEventListener)}.
 *
 * <p><b>Contract for implementations:</b>
 *
 * <ul>
 *   <li><b>Non-blocking</b> — listeners are invoked synchronously on the thread that triggers the
 *       chaos event. Any I/O, locking, or other blocking call inside {@code onEvent} adds latency
 *       to the instrumented JVM operation.
 *   <li><b>Exception-safe</b> — unchecked exceptions propagate to the triggering thread and may
 *       break application code. Never throw from {@code onEvent}.
 *   <li><b>Idempotent-safe</b> — the same event may be delivered to multiple listeners; each
 *       listener receives its own {@link ChaosEvent} reference (the object is shared, not copied).
 * </ul>
 *
 * <p>Built-in listeners registered automatically:
 *
 * <ul>
 *   <li>A {@code java.util.logging} logger at {@code INFO} level (always active)
 *   <li>A JFR event bridge (registered automatically by the bootstrap module) when {@code jdk.jfr}
 *       is available at runtime
 * </ul>
 */
@FunctionalInterface
public interface ChaosEventListener {

  /**
   * Called each time a chaos event occurs.
   *
   * @param event the immutable event; never null. Inspect {@link ChaosEvent#type()} to discriminate
   *     lifecycle events from application events.
   */
  void onEvent(ChaosEvent event);

  /**
   * Wraps {@code delegate} so that it receives events on an agent-owned thread instead of the
   * thread that triggered them: the control plane queues each event (a bounded queue; events beyond
   * its capacity are dropped and counted in the diagnostics' {@code droppedEvents}) and returns
   * immediately. Use it for listeners that log, export or block, so their latency never reaches an
   * intercepted application call. Events are delivered in order.
   *
   * @param delegate the listener to call off the triggering thread
   * @return a listener to register with {@link ChaosControlPlane#addEventListener}
   */
  static ChaosEventListener async(final ChaosEventListener delegate) {
    return new AsyncChaosEventListener(delegate);
  }
}

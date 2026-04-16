package com.macstab.chaos.api;

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
}

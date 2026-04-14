package io.macstab.chaos.api;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable event published to {@link ChaosEventListener} subscribers on every chaos lifecycle
 * transition and effect application.
 *
 * <p>Events are dispatched synchronously on the thread that triggers the transition. Listener
 * implementations must be non-blocking and must not throw.
 *
 * <p><b>Attributes by event type:</b>
 *
 * <table border="1">
 *   <caption>Context attributes present per event type</caption>
 *   <tr><th>Type</th><th>Always present</th><th>Conditionally present</th></tr>
 *   <tr><td>{@link Type#STARTED}, {@link Type#STOPPED}, {@link Type#RELEASED}</td>
 *       <td>{@code scope}</td><td>—</td></tr>
 *   <tr><td>{@link Type#APPLIED}</td>
 *       <td>{@code operation}, {@code scope}, {@code effectType}</td>
 *       <td>{@code sessionId} (session-scoped scenarios only)</td></tr>
 *   <tr><td>{@link Type#REGISTERED}</td><td>{@code scope}</td><td>—</td></tr>
 *   <tr><td>{@link Type#SKIPPED}, {@link Type#FAILED}</td><td>—</td><td>—</td></tr>
 * </table>
 *
 * @param timestamp  when the event occurred, in UTC
 * @param type       the lifecycle phase or application event
 * @param scenarioId the ID of the scenario that generated this event
 * @param message    human-readable description of the event
 * @param attributes context key-value pairs; see table above for type-specific keys
 */
public record ChaosEvent(
    Instant timestamp,
    Type type,
    String scenarioId,
    String message,
    Map<String, String> attributes) {

  /**
   * Discriminates the kind of chaos event delivered to {@link ChaosEventListener}.
   */
  public enum Type {

    /**
     * The scenario was registered with the control plane and is ready for activation. Fired once
     * per scenario, before {@link #STARTED}.
     */
    REGISTERED,

    /**
     * The scenario transitioned to {@link ChaosDiagnostics.ScenarioState#ACTIVE ACTIVE} and will
     * now match operations. For stressor effects, the background stressor task has been launched.
     */
    STARTED,

    /**
     * The scenario was stopped: no further effects will fire. Stressor resources have been cleaned
     * up.
     */
    STOPPED,

    /**
     * A {@link ChaosEffect.GateEffect} gate was opened via {@link ChaosActivationHandle#release()}.
     * All threads blocked on the gate are unblocked. The scenario remains active.
     */
    RELEASED,

    /**
     * A chaos effect was applied to a matched JVM operation. This is the highest-frequency event
     * type — one per intercepted operation. The {@code attributes} map contains {@code operation},
     * {@code scope}, {@code effectType}, and optionally {@code sessionId}.
     */
    APPLIED,

    /**
     * A potential match was evaluated but the effect was not applied, typically because
     * {@link ActivationPolicy} constraints (probability, rate limit, maxApplications) were not
     * satisfied.
     */
    SKIPPED,

    /**
     * An internal error occurred during effect application. The operation proceeded normally.
     * Check the message for diagnostic details.
     */
    FAILED,
  }
}

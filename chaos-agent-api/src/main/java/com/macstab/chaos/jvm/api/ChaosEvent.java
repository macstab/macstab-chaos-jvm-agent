package com.macstab.chaos.jvm.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
 * @param timestamp when the event occurred, in UTC
 * @param type the lifecycle phase or application event
 * @param scenarioId the ID of the scenario that generated this event
 * @param message human-readable description of the event
 * @param attributes context key-value pairs; see table above for type-specific keys
 */
public record ChaosEvent(
    Instant timestamp,
    Type type,
    String scenarioId,
    String message,
    Map<String, String> attributes) {

  /**
   * Canonical constructor — enforces null-safety on every required field and takes an immutable
   * snapshot of the attribute map. Without the copy, a listener could hold a reference to the
   * original {@code Map} and mutate it long after the event has been published, breaking the
   * published immutability contract. {@code Map.copyOf} additionally rejects null keys/values,
   * which keeps downstream serialisation (Jackson, JFR commit) from tripping on them later.
   */
  public ChaosEvent(
      final Instant timestamp,
      final Type type,
      final String scenarioId,
      final String message,
      final @Nullable Map<String, String> attributes) {
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(scenarioId, "scenarioId");
    Objects.requireNonNull(message, "message");
    this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    this.timestamp = timestamp;
    this.type = type;
    this.scenarioId = scenarioId;
    this.message = message;
  }

  /** Discriminates the kind of chaos event delivered to {@link ChaosEventListener}. */
  public enum Type {

    /**
     * The scenario was registered with the control plane and is ready for activation. Fired once
     * per scenario, before {@link #STARTED}.
     */
    REGISTERED,

    /**
     * The scenario transitioned to {@link ChaosDiagnostics.ScenarioState#ACTIVE ACTIVE} and will
     * now match operations. For stressor effects, the background stressor task has been launched.
     * Published before the scenario fires: a call made while this event is being delivered is
     * neither matched nor applied.
     */
    STARTED,

    /**
     * The scenario was stopped: no further effects will fire. Stressor resources have been cleaned
     * up. Published once, on the transition into {@link ChaosDiagnostics.ScenarioState#STOPPED
     * STOPPED}; stopping or closing a stopped scenario again publishes nothing.
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
     * The selector matched but the policy kept the effect from applying. The {@code reason}
     * attribute names the policy check: {@code notYetActive} (before {@code activeFrom}), {@code
     * warmUp} ({@code activateAfterMatches}), {@code probability}, {@code rateLimit} or {@code
     * maxApplications}. Published only while a listener other than the built-in logging one is
     * registered, so that the hot path stays free of the allocation otherwise.
     */
    SKIPPED,

    /**
     * A scenario could not be activated: invalid configuration, an unsupported runtime, an
     * activation conflict or a weaving failure. The {@code category} attribute is the {@link
     * ChaosDiagnostics.FailureCategory}; the failure is also recorded in the diagnostics.
     */
    FAILED,

    /**
     * The scenario ended on its own and transitioned from {@link
     * ChaosDiagnostics.ScenarioState#ACTIVE ACTIVE} to {@link
     * ChaosDiagnostics.ScenarioState#INACTIVE INACTIVE}: no further effects fire. The {@code
     * reason} attribute is {@code expired} ({@code activeFor} or {@code activeUntil} passed) or
     * {@code max applications reached}. Published once per transition; stopping the scenario
     * afterwards still publishes {@link #STOPPED}.
     */
    INACTIVE,
  }
}

package com.macstab.chaos.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;

/**
 * Controls when and how often a chaos effect fires when its selector matches an operation.
 *
 * <p>All constraints are conjunctive: a match must satisfy <em>every</em> active constraint before
 * the effect is applied. A {@code null} constraint means "no restriction on this axis".
 *
 * <p>Quick references:
 *
 * <ul>
 *   <li>{@link #always()} — effect fires on every match, starts immediately
 *   <li>{@link #manual()} — effect fires on every match, but only after {@link
 *       ChaosActivationHandle#start()} is called
 *   <li>{@link #withDestructiveEffects()} — same as {@link #always()} but with explicit opt-in for
 *       non-recoverable effects ({@code deadlock()}, {@code threadLeak()})
 * </ul>
 *
 * <p>Fine-grained control via the canonical constructor:
 *
 * <pre>{@code
 * // Fire with 30% probability, cap at 100 total applications, seeded for reproducibility.
 * new ActivationPolicy(
 *     StartMode.AUTOMATIC,
 *     0.30,           // probability
 *     0,              // activateAfterMatches (no warm-up)
 *     100L,           // maxApplications
 *     null,           // activeFor (no time bound)
 *     null,           // rateLimit
 *     42L,            // randomSeed
 *     false);         // allowDestructiveEffects
 * }</pre>
 *
 * <p><strong>Destructive effects</strong>: {@link com.macstab.chaos.api.ChaosEffect.DeadlockEffect}
 * and {@link com.macstab.chaos.api.ChaosEffect.ThreadLeakEffect} create non-recoverable JVM state —
 * deadlocked or permanently-parked threads that cannot be interrupted or terminated within the
 * running JVM process. Activation of these effects requires {@code allowDestructiveEffects(true)}
 * in the policy; any attempt without it throws {@link ChaosActivationException} at registration
 * time, not at effect application time. Use only in short-lived test processes or controlled
 * environments where JVM restart is acceptable.
 *
 * @param startMode when the scenario begins accepting matches
 * @param probability fraction of matches that result in effect application; in {@code (0.0, 1.0]}
 * @param activateAfterMatches number of initial matches to skip before the effect becomes eligible
 * @param maxApplications hard cap on total effect applications; {@code null} means unlimited
 * @param activeFor time window during which the scenario remains active; {@code null} means none
 * @param rateLimit sliding-window rate cap on applications; {@code null} means unlimited
 * @param randomSeed fixed PRNG seed for reproducible probability sampling; {@code null} uses 0L
 * @param allowDestructiveEffects whether non-recoverable effects may be activated
 */
public record ActivationPolicy(
    StartMode startMode,
    double probability,
    long activateAfterMatches,
    Long maxApplications,
    Duration activeFor,
    RateLimit rateLimit,
    Long randomSeed,
    boolean allowDestructiveEffects) {

  /**
   * Compact canonical constructor — normalises defaults and validates all field invariants.
   *
   * <p>A {@code null} {@code startMode} defaults to {@link StartMode#AUTOMATIC}. An explicit {@code
   * probability} of {@code 0.0} is rejected: a user writing {@code probability: 0.0} in a config
   * file is almost certainly trying to disable the effect, and silently rewriting it to {@code 1.0}
   * turns an "off" switch into an "always fire" switch. The error message redirects to omitting the
   * scenario activation entirely, which actually expresses "never fire".
   *
   * @throws IllegalArgumentException if {@code probability} is outside {@code (0.0, 1.0]}, or if
   *     {@code activateAfterMatches} is negative, or if {@code maxApplications} or {@code
   *     activeFor} violate their constraints
   */
  public ActivationPolicy {
    if (startMode == null) startMode = StartMode.AUTOMATIC;
    if (Double.isNaN(probability) || probability <= 0.0d || probability > 1.0d) {
      // Both `<= 0.0` and `> 1.0` evaluate to false for NaN, so without the explicit isNaN check
      // a misconfigured probability (failed parse, computed from a divide-by-zero, etc.) passes
      // the guard and the downstream `nextDouble() <= NaN` comparison in passesProbability is
      // always false — the scenario registers as ACTIVE but silently never fires. Reject NaN
      // at the boundary so the operator sees the misconfiguration immediately.
      throw new IllegalArgumentException(
          "probability must be in (0.0, 1.0], got "
              + probability
              + "; to disable an effect omit the scenario activation entirely");
    }
    if (activateAfterMatches < 0) {
      throw new IllegalArgumentException("activateAfterMatches must be >= 0");
    }
    if (maxApplications != null && maxApplications <= 0) {
      throw new IllegalArgumentException("maxApplications must be > 0 when set");
    }
    if (activeFor != null && (activeFor.isZero() || activeFor.isNegative())) {
      throw new IllegalArgumentException("activeFor must be positive when set");
    }
  }

  /**
   * Jackson deserialization factory.
   *
   * <p>{@code probability} accepts a boxed {@link Double} so that an absent JSON field (which
   * Jackson maps to {@code null}) is distinguished from an explicit {@code 0.0}. A {@code null}
   * value defaults to {@code 1.0} (always fire). An explicit {@code 0.0} fails validation — omit
   * the scenario activation entirely to disable an effect.
   *
   * @param startMode when the scenario begins accepting matches; defaults to {@link
   *     StartMode#AUTOMATIC}
   * @param probability fraction of matches that result in effect application; omit or pass {@code
   *     null} to default to {@code 1.0}; must be in {@code (0.0, 1.0]} when present
   * @param activateAfterMatches number of initial matches to skip before the effect becomes
   *     eligible; {@code 0} means no warm-up
   * @param maxApplications hard cap on total effect applications; {@code null} means unlimited
   * @param activeFor time window from first start during which the scenario remains active; {@code
   *     null} means no time bound
   * @param rateLimit sliding-window rate cap on applications; {@code null} means unlimited
   * @param randomSeed fixed PRNG seed for reproducible probability sampling; {@code null} uses
   *     {@code 0L}
   * @param allowDestructiveEffects whether non-recoverable effects may be activated
   * @return a new {@link ActivationPolicy} built from the parsed JSON values
   */
  @JsonCreator
  public static ActivationPolicy fromJson(
      @JsonProperty("startMode") final StartMode startMode,
      @JsonProperty("probability") final Double probability,
      @JsonProperty("activateAfterMatches") final long activateAfterMatches,
      @JsonProperty("maxApplications") final Long maxApplications,
      @JsonProperty("activeFor") final Duration activeFor,
      @JsonProperty("rateLimit") final RateLimit rateLimit,
      @JsonProperty("randomSeed") final Long randomSeed,
      @JsonProperty("allowDestructiveEffects") final boolean allowDestructiveEffects) {
    return new ActivationPolicy(
        startMode,
        probability == null ? 1.0d : probability,
        activateAfterMatches,
        maxApplications,
        activeFor,
        rateLimit,
        randomSeed,
        allowDestructiveEffects);
  }

  /**
   * Returns a policy that fires on every match and starts immediately on activation.
   *
   * <p>Equivalent to: {@code probability=1.0, startMode=AUTOMATIC, no other constraints}.
   *
   * @return an always-fire activation policy
   */
  public static ActivationPolicy always() {
    return new ActivationPolicy(StartMode.AUTOMATIC, 1.0d, 0, null, null, null, 0L, false);
  }

  /**
   * Returns a policy that fires on every match, starts immediately, and explicitly permits
   * non-recoverable destructive effects ({@link com.macstab.chaos.api.ChaosEffect.DeadlockEffect},
   * {@link com.macstab.chaos.api.ChaosEffect.ThreadLeakEffect}).
   *
   * <p><strong>Warning</strong>: activating a destructive effect creates JVM state that cannot be
   * recovered without process restart. Use only in short-lived test processes.
   *
   * @return an always-fire activation policy that permits destructive effects
   */
  public static ActivationPolicy withDestructiveEffects() {
    return new ActivationPolicy(StartMode.AUTOMATIC, 1.0d, 0, null, null, null, 0L, true);
  }

  /**
   * Returns a policy that fires on every match but starts in the {@link
   * ChaosDiagnostics.ScenarioState#INACTIVE INACTIVE} state. The effect does not fire until {@link
   * ChaosActivationHandle#start()} is called.
   *
   * <p>Use for synchronised test phases where chaos must be enabled at a precise moment.
   *
   * @return an activation policy that fires on every match but starts in INACTIVE state
   */
  public static ActivationPolicy manual() {
    return new ActivationPolicy(StartMode.MANUAL, 1.0d, 0, null, null, null, 0L, false);
  }

  /**
   * Controls whether a scenario activates automatically on registration or waits for an explicit
   * {@link ChaosActivationHandle#start()} call.
   */
  public enum StartMode {

    /**
     * The scenario transitions to {@link ChaosDiagnostics.ScenarioState#ACTIVE ACTIVE} immediately
     * upon registration. The effect fires on the first qualifying match.
     */
    AUTOMATIC,

    /**
     * The scenario starts in {@link ChaosDiagnostics.ScenarioState#INACTIVE INACTIVE} state. No
     * effect fires until {@link ChaosActivationHandle#start()} is called explicitly.
     */
    MANUAL,
  }

  /**
   * Sliding-window rate limit on effect applications.
   *
   * <p>The rate limit is applied after probability sampling. At most {@link #permits} effects will
   * fire within any rolling window of {@link #window} duration. Excess matches in the window are
   * silently skipped.
   *
   * <p>Example: {@code new RateLimit(10, Duration.ofSeconds(1))} allows at most 10 effect
   * applications per second regardless of match frequency.
   *
   * @param permits maximum effect applications allowed within one {@code window}; must be positive
   * @param window rolling window duration; must be positive
   */
  public record RateLimit(long permits, Duration window) {

    /**
     * @param permits maximum effect applications allowed within one {@code window}; must be {@code
     *     > 0}
     * @param window rolling window duration; must be positive
     * @throws IllegalArgumentException if {@code permits <= 0} or {@code window} is null, zero, or
     *     negative
     */
    public RateLimit {
      if (permits <= 0) {
        throw new IllegalArgumentException("permits must be > 0");
      }
      if (window == null || window.isZero() || window.isNegative()) {
        throw new IllegalArgumentException("window must be positive");
      }
    }
  }
}

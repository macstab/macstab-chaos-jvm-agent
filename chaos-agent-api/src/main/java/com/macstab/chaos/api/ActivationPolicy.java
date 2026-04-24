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
 *     42L);           // randomSeed
 * }</pre>
 *
 * @param startMode when the scenario begins accepting matches; defaults to {@link
 *     StartMode#AUTOMATIC}
 * @param probability fraction of matches that result in effect application; must be in {@code (0.0,
 *     1.0]}
 * @param activateAfterMatches number of initial matches to skip before the effect becomes eligible;
 *     {@code 0} means no warm-up
 * @param maxApplications hard cap on total effect applications; {@code null} means unlimited
 * @param activeFor time window from first start during which the scenario remains active; {@code
 *     null} means no time bound
 * @param rateLimit sliding-window rate cap on applications; {@code null} means unlimited
 * @param randomSeed fixed PRNG seed for reproducible probability sampling; {@code null} uses {@code
 *     0L}
 */
public record ActivationPolicy(
    StartMode startMode,
    double probability,
    long activateAfterMatches,
    Long maxApplications,
    Duration activeFor,
    RateLimit rateLimit,
    Long randomSeed) {

  /**
   * Canonical constructor with full Jackson deserialization support.
   *
   * @param startMode when the scenario begins accepting matches; defaults to {@link
   *     StartMode#AUTOMATIC}
   * @param probability fraction of matches that result in effect application; {@code 0.0} is
   *     treated as {@code 1.0} (deserialization default); must be in {@code (0.0, 1.0]}
   * @param activateAfterMatches number of initial matches to skip before the effect becomes
   *     eligible; {@code 0} means no warm-up
   * @param maxApplications hard cap on total effect applications; {@code null} means unlimited
   * @param activeFor time window from first start during which the scenario remains active; {@code
   *     null} means no time bound
   * @param rateLimit sliding-window rate cap on applications; {@code null} means unlimited
   * @param randomSeed fixed PRNG seed for reproducible probability sampling; {@code null} uses
   *     {@code 0L}
   * @throws IllegalArgumentException if {@code probability} is outside {@code (0.0, 1.0]}, or if
   *     {@code activateAfterMatches} is negative, or if {@code maxApplications} or {@code
   *     activeFor} violate their constraints
   */
  @JsonCreator
  public ActivationPolicy(
      @JsonProperty("startMode") final StartMode startMode,
      @JsonProperty("probability") final double probability,
      @JsonProperty("activateAfterMatches") final long activateAfterMatches,
      @JsonProperty("maxApplications") final Long maxApplications,
      @JsonProperty("activeFor") final Duration activeFor,
      @JsonProperty("rateLimit") final RateLimit rateLimit,
      @JsonProperty("randomSeed") final Long randomSeed) {
    this.startMode = startMode == null ? StartMode.AUTOMATIC : startMode;
    this.probability = probability == 0.0d ? 1.0d : probability;
    this.activateAfterMatches = activateAfterMatches;
    this.maxApplications = maxApplications;
    this.activeFor = activeFor;
    this.rateLimit = rateLimit;
    this.randomSeed = randomSeed;
    validate();
  }

  /**
   * Returns a policy that fires on every match and starts immediately on activation.
   *
   * <p>Equivalent to: {@code probability=1.0, startMode=AUTOMATIC, no other constraints}.
   */
  public static ActivationPolicy always() {
    return new ActivationPolicy(StartMode.AUTOMATIC, 1.0d, 0, null, null, null, 0L);
  }

  /**
   * Returns a policy that fires on every match but starts in the {@link
   * ChaosDiagnostics.ScenarioState#INACTIVE INACTIVE} state. The effect does not fire until {@link
   * ChaosActivationHandle#start()} is called.
   *
   * <p>Use for synchronised test phases where chaos must be enabled at a precise moment.
   */
  public static ActivationPolicy manual() {
    return new ActivationPolicy(StartMode.MANUAL, 1.0d, 0, null, null, null, 0L);
  }

  private void validate() {
    if (probability <= 0.0d || probability > 1.0d) {
      throw new IllegalArgumentException("probability must be in the range (0.0, 1.0]");
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
   * @param permits maximum effect applications allowed within one {@code window}; must be {@code >
   *     0}
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

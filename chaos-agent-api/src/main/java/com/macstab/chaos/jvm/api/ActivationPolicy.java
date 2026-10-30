package com.macstab.chaos.jvm.api;

import java.time.Duration;
import java.time.Instant;

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
 * <p><strong>Destructive effects</strong>: {@link
 * com.macstab.chaos.jvm.api.ChaosEffect.DeadlockEffect} and {@link
 * com.macstab.chaos.jvm.api.ChaosEffect.ThreadLeakEffect} create non-recoverable JVM state —
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
 * @param rateLimit fixed-window rate cap on applications; {@code null} means unlimited
 * @param randomSeed fixed PRNG seed for reproducible probability sampling; {@code null} draws a
 *     random seed from {@link java.util.concurrent.ThreadLocalRandom} on first activation —
 *     sampling is non-deterministic across JVM restarts; set an explicit value for reproducible
 *     sequences
 * @param allowDestructiveEffects whether non-recoverable effects may be activated
 * @param activeFrom wall-clock instant before which the scenario does not fire; {@code null} means
 *     no lower bound. Read from the real system clock, so a clock skew the agent injects cannot
 *     move it
 * @param activeUntil wall-clock instant from which on the scenario no longer fires and becomes
 *     {@code INACTIVE}; {@code null} means no upper bound. Read from the real system clock
 */
public record ActivationPolicy(
    StartMode startMode,
    double probability,
    long activateAfterMatches,
    Long maxApplications,
    Duration activeFor,
    RateLimit rateLimit,
    Long randomSeed,
    boolean allowDestructiveEffects,
    Instant activeFrom,
    Instant activeUntil) {

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
    if (activeFrom != null && activeUntil != null && !activeFrom.isBefore(activeUntil)) {
      throw new IllegalArgumentException(
          "activeFrom must be before activeUntil, got " + activeFrom + " and " + activeUntil);
    }
  }

  /**
   * Creates a policy without an absolute wall-clock window ({@code activeFrom} and {@code
   * activeUntil} are {@code null}). Kept so callers of the 1.x constructor keep compiling; prefer
   * {@link #builder()}.
   *
   * @param startMode when the scenario begins accepting matches
   * @param probability fraction of matches that result in effect application
   * @param activateAfterMatches number of initial matches to skip
   * @param maxApplications hard cap on total effect applications; {@code null} means unlimited
   * @param activeFor time window from start; {@code null} means none
   * @param rateLimit fixed-window rate cap; {@code null} means unlimited
   * @param randomSeed fixed PRNG seed; {@code null} draws a random seed
   * @param allowDestructiveEffects whether non-recoverable effects may be activated
   */
  public ActivationPolicy(
      final StartMode startMode,
      final double probability,
      final long activateAfterMatches,
      final Long maxApplications,
      final Duration activeFor,
      final RateLimit rateLimit,
      final Long randomSeed,
      final boolean allowDestructiveEffects) {
    this(
        startMode,
        probability,
        activateAfterMatches,
        maxApplications,
        activeFor,
        rateLimit,
        randomSeed,
        allowDestructiveEffects,
        null,
        null);
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
   * @param rateLimit fixed-window rate cap on applications; {@code null} means unlimited
   * @param randomSeed fixed PRNG seed for reproducible probability sampling; {@code null} draws a
   *     random seed from {@link java.util.concurrent.ThreadLocalRandom} — non-deterministic across
   *     JVM restarts
   * @param allowDestructiveEffects whether non-recoverable effects may be activated
   * @param activeFrom wall-clock instant before which the scenario does not fire; may be {@code
   *     null}
   * @param activeUntil wall-clock instant from which on the scenario no longer fires; may be {@code
   *     null}
   * @return a new {@link ActivationPolicy} built from the parsed JSON values
   */
  public static ActivationPolicy fromJson(
      final StartMode startMode,
      final Double probability,
      final long activateAfterMatches,
      final Long maxApplications,
      final Duration activeFor,
      final RateLimit rateLimit,
      final Long randomSeed,
      final boolean allowDestructiveEffects,
      final Instant activeFrom,
      final Instant activeUntil) {
    return new ActivationPolicy(
        startMode,
        probability == null ? 1.0d : probability,
        activateAfterMatches,
        maxApplications,
        activeFor,
        rateLimit,
        randomSeed,
        allowDestructiveEffects,
        activeFrom,
        activeUntil);
  }

  /**
   * Returns a policy that fires on every match and starts immediately on activation.
   *
   * <p>Equivalent to: {@code probability=1.0, startMode=AUTOMATIC, no other constraints}. Uses the
   * fixed seed {@code 0}, so sampled delays of a delay range are reproducible across runs; pass
   * {@code randomSeed = null} to the constructor (or omit it in JSON) for a random seed.
   *
   * @return an always-fire activation policy
   */
  public static ActivationPolicy always() {
    return new ActivationPolicy(StartMode.AUTOMATIC, 1.0d, 0, null, null, null, 0L, false);
  }

  /**
   * Returns a policy that fires on every match, starts immediately, and explicitly permits
   * non-recoverable destructive effects ({@link
   * com.macstab.chaos.jvm.api.ChaosEffect.DeadlockEffect}, {@link
   * com.macstab.chaos.jvm.api.ChaosEffect.ThreadLeakEffect}).
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
   * ChaosDiagnostics.ScenarioState#REGISTERED REGISTERED} state. The effect does not fire until
   * {@link ChaosActivationHandle#start()} is called. Uses the fixed seed {@code 0}, like {@link
   * #always()}.
   *
   * <p>Use for synchronised test phases where chaos must be enabled at a precise moment.
   *
   * @return an activation policy that fires on every match but starts in REGISTERED state
   */
  public static ActivationPolicy manual() {
    return new ActivationPolicy(StartMode.MANUAL, 1.0d, 0, null, null, null, 0L, false);
  }

  /**
   * Returns a policy that fires on the first {@code applications} matches and then becomes {@code
   * INACTIVE}: {@link #always()} with {@code maxApplications = applications}. The equivalent of the
   * native library's {@code @first=N} qualifier.
   *
   * @param applications the number of applications; {@code > 0}
   * @return the policy
   * @throws IllegalArgumentException if {@code applications} is not positive
   */
  public static ActivationPolicy count(final long applications) {
    return builder().maxApplications(applications).build();
  }

  /**
   * Returns a policy that fires on every match between two wall-clock instants: {@link #always()}
   * with {@code activeFrom = from} and {@code activeUntil = until}. The equivalent of the native
   * library's {@code @from}/{@code @until} qualifiers. Either bound may be {@code null}.
   *
   * @param from the first instant the scenario fires at; {@code null} for no lower bound
   * @param until the instant from which on the scenario no longer fires; {@code null} for no upper
   *     bound
   * @return the policy
   * @throws IllegalArgumentException if {@code from} is not before {@code until}
   */
  public static ActivationPolicy window(final Instant from, final Instant until) {
    return builder().window(from, until).build();
  }

  /**
   * Returns a builder whose defaults equal {@link #always()}: automatic start, probability {@code
   * 1.0}, no warm-up, cap, window or rate limit, seed {@code 0}, no destructive effects.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for {@link ActivationPolicy}. Unlike the canonical constructor it does not break when
   * the record gains a field, so it is the recommended way to construct a policy in code.
   */
  public static final class Builder {
    private StartMode startMode = StartMode.AUTOMATIC;
    private double probability = 1.0d;
    private long activateAfterMatches;
    private Long maxApplications;
    private Duration activeFor;
    private RateLimit rateLimit;
    private Long randomSeed = 0L;
    private boolean allowDestructiveEffects;
    private Instant activeFrom;
    private Instant activeUntil;

    private Builder() {}

    /**
     * Sets when the scenario starts accepting matches.
     *
     * @param value the start mode
     * @return this builder
     */
    public Builder startMode(final StartMode value) {
      this.startMode = value;
      return this;
    }

    /**
     * Sets the fraction of matches that apply the effect, in {@code (0.0, 1.0]}.
     *
     * @param value the probability
     * @return this builder
     */
    public Builder probability(final double value) {
      this.probability = value;
      return this;
    }

    /**
     * Sets how many initial matches are skipped before the effect applies.
     *
     * @param value the number of matches to skip; {@code >= 0}
     * @return this builder
     */
    public Builder activateAfterMatches(final long value) {
      this.activateAfterMatches = value;
      return this;
    }

    /**
     * Sets the maximum number of applications.
     *
     * @param value the cap; {@code null} for unlimited
     * @return this builder
     */
    public Builder maxApplications(final Long value) {
      this.maxApplications = value;
      return this;
    }

    /**
     * Sets how long after start the scenario stays active.
     *
     * @param value the window; {@code null} for none
     * @return this builder
     */
    public Builder activeFor(final Duration value) {
      this.activeFor = value;
      return this;
    }

    /**
     * Limits applications to {@code permits} per fixed window of {@code window}.
     *
     * @param permits applications per window; {@code > 0}
     * @param window the window length; positive
     * @return this builder
     */
    public Builder rateLimit(final long permits, final Duration window) {
      this.rateLimit = new RateLimit(permits, window);
      return this;
    }

    /**
     * Sets the rate limit.
     *
     * @param value the rate limit; {@code null} for none
     * @return this builder
     */
    public Builder rateLimit(final RateLimit value) {
      this.rateLimit = value;
      return this;
    }

    /**
     * Sets the PRNG seed; {@code null} draws a random seed on activation.
     *
     * @param value the seed, or {@code null}
     * @return this builder
     */
    public Builder randomSeed(final Long value) {
      this.randomSeed = value;
      return this;
    }

    /**
     * Permits non-recoverable effects ({@link ChaosEffect.DeadlockEffect}, {@link
     * ChaosEffect.ThreadLeakEffect}).
     *
     * @param value {@code true} to permit them
     * @return this builder
     */
    public Builder allowDestructiveEffects(final boolean value) {
      this.allowDestructiveEffects = value;
      return this;
    }

    /**
     * Sets the wall-clock instant before which the scenario does not fire.
     *
     * @param value the instant; {@code null} for no lower bound
     * @return this builder
     */
    public Builder activeFrom(final Instant value) {
      this.activeFrom = value;
      return this;
    }

    /**
     * Sets the wall-clock instant from which on the scenario no longer fires.
     *
     * @param value the instant; {@code null} for no upper bound
     * @return this builder
     */
    public Builder activeUntil(final Instant value) {
      this.activeUntil = value;
      return this;
    }

    /**
     * Sets both wall-clock bounds.
     *
     * @param from the first instant the scenario fires at; {@code null} for no lower bound
     * @param until the instant from which on the scenario no longer fires; {@code null} for no
     *     upper bound
     * @return this builder
     */
    public Builder window(final Instant from, final Instant until) {
      this.activeFrom = from;
      this.activeUntil = until;
      return this;
    }

    /**
     * Builds the policy.
     *
     * @return the policy
     * @throws IllegalArgumentException if a value is out of range
     */
    public ActivationPolicy build() {
      return new ActivationPolicy(
          startMode,
          probability,
          activateAfterMatches,
          maxApplications,
          activeFor,
          rateLimit,
          randomSeed,
          allowDestructiveEffects,
          activeFrom,
          activeUntil);
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
   * Fixed-window rate limit on effect applications.
   *
   * <p>The rate limit is applied after probability sampling. Time is divided into consecutive
   * windows of {@link #window} duration, the first starting when the scenario starts; at most
   * {@link #permits} effects fire per window and excess matches are skipped. Because the windows
   * are fixed, up to {@code 2 × permits} effects can fire within one window length that straddles a
   * window boundary.
   *
   * <p>Example: {@code new RateLimit(10, Duration.ofSeconds(1))} allows at most 10 effect
   * applications per second regardless of match frequency.
   *
   * @param permits maximum effect applications allowed within one {@code window}; must be positive
   * @param window window duration; must be positive
   */
  public record RateLimit(long permits, Duration window) {

    /**
     * Validates the rate-limit parameters.
     *
     * @param permits maximum effect applications allowed within one {@code window}; must be {@code
     *     > 0}
     * @param window window duration; must be positive
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
      // Probe toNanos() eagerly: the rate-limiter arithmetic downstream multiplies window
      // nanos by fractional permits (long math). A Duration constructed from Duration.ofDays(
      // Long.MAX_VALUE / 86_400) silently overflows on toNanos() with ArithmeticException,
      // which would otherwise surface deep inside the scenario hot path on the first match —
      // turning an operator configuration mistake into a runtime exception far from the
      // source. Catching it here points the error at the plan parser / YAML author.
      try {
        window.toNanos();
      } catch (final ArithmeticException overflow) {
        throw new IllegalArgumentException(
            "window is too large to represent in nanoseconds: " + window, overflow);
      }
    }
  }
}

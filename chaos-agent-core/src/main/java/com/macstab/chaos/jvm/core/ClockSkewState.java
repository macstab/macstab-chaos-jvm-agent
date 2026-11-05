package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-scenario state for {@link ChaosEffect.ClockSkewEffect}.
 *
 * <p>Tracks the accumulated drift (for {@link ChaosEffect.ClockSkewMode#DRIFT}) and the frozen
 * timestamp captured at scenario activation (for {@link ChaosEffect.ClockSkewMode#FREEZE}). All
 * state mutations are thread-safe via {@link AtomicLong}.
 *
 * <p>One instance is created per active {@link ChaosScenario} whose effect is {@link
 * ChaosEffect.ClockSkewEffect}. The instance is created in {@link ScenarioController#start()} and
 * discarded when the scenario stops.
 */
final class ClockSkewState {

  private final long skewMillis;
  private final long skewNanos;
  private final long frozenMillis;
  private final long frozenNanos;

  /** REGRESS: no read yet. */
  private static final int UNARMED = 0;

  /** REGRESS: a real read has been returned; the next read jumps. */
  private static final int ARMED = 1;

  /** REGRESS: the clock has stepped back and stays there. */
  private static final int JUMPED = 2;

  /** REGRESS progress, shared by every clock the scenario skews so they step back together. */
  private final java.util.concurrent.atomic.AtomicInteger regressState =
      new java.util.concurrent.atomic.AtomicInteger(UNARMED);

  /** Accumulated offset in milliseconds added to each call in DRIFT mode. */
  private final AtomicLong accumulatedDriftMillis = new AtomicLong(0L);

  /** Accumulated offset in nanoseconds added to each call in DRIFT mode. */
  private final AtomicLong accumulatedDriftNanos = new AtomicLong(0L);

  /**
   * Creates a new state snapshot for the given effect, capturing the real clock values at the
   * moment of activation so that {@link ChaosEffect.ClockSkewMode#FREEZE} can return a stable
   * timestamp.
   *
   * @param effect the clock skew configuration; must not be null
   * @param capturedMillis {@link System#currentTimeMillis()} at activation time
   * @param capturedNanos {@link System#nanoTime()} at activation time
   */
  ClockSkewState(
      final ChaosEffect.ClockSkewEffect effect,
      final long capturedMillis,
      final long capturedNanos) {
    this.skewMillis = effect.skewAmount().toMillis();
    // Duration.toNanos() throws ArithmeticException for durations that exceed Long.MAX_VALUE
    // nanoseconds (~292 years). Clamp to Long.MAX_VALUE / Long.MIN_VALUE so the constructor
    // does not propagate an unchecked exception and leave the controller half-initialised.
    long skewNanosRaw;
    try {
      skewNanosRaw = effect.skewAmount().toNanos();
    } catch (final ArithmeticException overflow) {
      skewNanosRaw = effect.skewAmount().isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    this.skewNanos = skewNanosRaw;
    // Saturate FREEZE-mode captures so a large positive skewAmount applied to a near-MAX
    // capturedMillis does not wrap to a negative epoch timestamp, which would read as a date
    // ~292M years in the past and break every downstream timestamp comparison (JWT exp, cache
    // TTLs, log ordering). Same reasoning for nanos; monotonicity is already documented as
    // intentionally violated, but wraparound is never what the operator asked for.
    this.frozenMillis = saturatingAdd(capturedMillis, skewMillis);
    this.frozenNanos = saturatingAdd(capturedNanos, skewNanos);
  }

  /**
   * Applies the skew to a millisecond clock value.
   *
   * @param mode the skew evolution mode
   * @param realMillis the real {@link System#currentTimeMillis()} value
   * @return the skewed millisecond timestamp
   */
  long applyMillis(final ChaosEffect.ClockSkewMode mode, final long realMillis) {
    return switch (mode) {
      case FIXED -> saturatingAdd(realMillis, skewMillis);
      case DRIFT -> saturatingAdd(realMillis, addSaturating(accumulatedDriftMillis, skewMillis));
      case FREEZE -> frozenMillis;
      case REGRESS -> regressed() ? saturatingAdd(realMillis, -skewMillis) : realMillis;
    };
  }

  /**
   * Applies the skew to a nanosecond clock value.
   *
   * @param mode the skew evolution mode
   * @param realNanos the real {@link System#nanoTime()} value
   * @return the skewed nanosecond timestamp; may violate nanoTime monotonicity by design
   */
  long applyNanos(final ChaosEffect.ClockSkewMode mode, final long realNanos) {
    return switch (mode) {
      case FIXED -> saturatingAdd(realNanos, skewNanos);
      case DRIFT -> saturatingAdd(realNanos, addSaturating(accumulatedDriftNanos, skewNanos));
      case FREEZE -> frozenNanos;
      case REGRESS -> regressed() ? saturatingAdd(realNanos, -skewNanos) : realNanos;
    };
  }

  /**
   * Advances the REGRESS state machine for one read and returns whether this read is shifted: the
   * first read arms and is real, the next one jumps, and every later one stays shifted. Exactly one
   * read performs the jump, whichever thread wins the compare-and-set.
   */
  private boolean regressed() {
    while (true) {
      final int state = regressState.get();
      if (state == JUMPED) {
        return true;
      }
      final int next = state == UNARMED ? ARMED : JUMPED;
      if (regressState.compareAndSet(state, next)) {
        return next == JUMPED;
      }
    }
  }

  private static long saturatingAdd(final long a, final long b) {
    // Plain `a + b` wraps from Long.MAX_VALUE → Long.MIN_VALUE on overflow, flipping a forward
    // clock into the deep past. Saturation preserves the direction of the skew even at extremes.
    try {
      return Math.addExact(a, b);
    } catch (final ArithmeticException overflow) {
      return (b >= 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }

  private static long addSaturating(final AtomicLong accumulator, final long increment) {
    // A plain `accumulator.addAndGet(increment)` wraps unboundedly; after roughly
    // Long.MAX_VALUE / increment calls the counter would flip sign and the observed clock would
    // jump ~292 years into the past mid-scenario. Saturate via CAS so a forward drift stays
    // forward even after astronomically many matches.
    while (true) {
      final long current = accumulator.get();
      final long next;
      try {
        next = Math.addExact(current, increment);
      } catch (final ArithmeticException overflow) {
        final long clamped = (increment >= 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
        if (current == clamped || accumulator.compareAndSet(current, clamped)) {
          return clamped;
        }
        continue;
      }
      if (accumulator.compareAndSet(current, next)) {
        return next;
      }
    }
  }
}

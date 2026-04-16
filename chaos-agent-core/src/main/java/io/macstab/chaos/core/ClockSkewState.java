package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-scenario state for {@link ChaosEffect.ClockSkewEffect}.
 *
 * <p>Tracks the accumulated drift (for {@link ChaosEffect.ClockSkewMode#DRIFT}) and the frozen
 * timestamp captured at scenario activation (for {@link ChaosEffect.ClockSkewMode#FREEZE}). All
 * state mutations are thread-safe via {@link AtomicLong}.
 *
 * <p>One instance is created per active {@link io.macstab.chaos.api.ChaosScenario} whose effect is
 * {@link ChaosEffect.ClockSkewEffect}. The instance is created in {@link
 * ScenarioController#start()} and discarded when the scenario stops.
 */
final class ClockSkewState {

  private final long skewMillis;
  private final long skewNanos;
  private final long frozenMillis;
  private final long frozenNanos;

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
    this.skewNanos = effect.skewAmount().toNanos();
    this.frozenMillis = capturedMillis + skewMillis;
    this.frozenNanos = capturedNanos + skewNanos;
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
      case FIXED -> realMillis + skewMillis;
      case DRIFT -> realMillis + accumulatedDriftMillis.addAndGet(skewMillis);
      case FREEZE -> frozenMillis;
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
      case FIXED -> realNanos + skewNanos;
      case DRIFT -> realNanos + accumulatedDriftNanos.addAndGet(skewNanos);
      case FREEZE -> frozenNanos;
    };
  }
}

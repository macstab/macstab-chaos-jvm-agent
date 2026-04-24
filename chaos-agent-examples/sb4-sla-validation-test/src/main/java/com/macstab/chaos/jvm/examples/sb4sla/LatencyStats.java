package com.macstab.chaos.jvm.examples.sb4sla;

public final class LatencyStats {

  private LatencyStats() {}

  /**
   * Computes the {@code pct}-th percentile of the given pre-sorted array of nanosecond values.
   *
   * @param sortedNanos nanosecond samples, sorted ascending
   * @param pct percentile in the range [0.0, 100.0]
   * @return interpolated percentile value in nanoseconds
   */
  public static long percentile(final long[] sortedNanos, final double pct) {
    if (sortedNanos == null || sortedNanos.length == 0) {
      throw new IllegalArgumentException("sortedNanos must be non-empty");
    }
    if (pct < 0.0 || pct > 100.0) {
      throw new IllegalArgumentException("pct must be in [0.0, 100.0]");
    }
    if (sortedNanos.length == 1) {
      return sortedNanos[0];
    }
    final double rank = (pct / 100.0) * (sortedNanos.length - 1);
    final int lower = (int) Math.floor(rank);
    final int upper = (int) Math.ceil(rank);
    if (lower == upper) {
      return sortedNanos[lower];
    }
    final double fraction = rank - lower;
    return Math.round(sortedNanos[lower] + fraction * (sortedNanos[upper] - sortedNanos[lower]));
  }
}

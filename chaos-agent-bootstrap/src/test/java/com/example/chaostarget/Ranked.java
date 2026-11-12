package com.example.chaostarget;

import java.util.List;
import java.util.function.Supplier;

/**
 * Application class targeted by method-weaving probes: its {@code compareTo(Ranked)} has a bridge
 * {@code compareTo(Object)}, and {@link #label()} a lambda, both compiler-generated methods.
 */
public final class Ranked implements Comparable<Ranked> {

  private final int rank;

  /**
   * Creates a ranked value.
   *
   * @param rank the rank
   */
  public Ranked(final int rank) {
    this.rank = rank;
  }

  @Override
  public int compareTo(final Ranked other) {
    return Integer.compare(rank, other.rank);
  }

  /**
   * Returns the rank as text, through a lambda.
   *
   * @return the label
   */
  public String label() {
    final Supplier<String> text = () -> "rank " + rank;
    return text.get();
  }

  /**
   * Sorts ranked values.
   *
   * @param values the values
   * @return the values in order
   */
  public static List<Ranked> sorted(final List<Ranked> values) {
    return values.stream().sorted().toList();
  }
}

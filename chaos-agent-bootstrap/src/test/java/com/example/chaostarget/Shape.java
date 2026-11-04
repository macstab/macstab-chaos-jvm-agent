package com.example.chaostarget;

import java.util.function.Supplier;

/**
 * Declares the method kinds a method scenario must not weave: an abstract method, a native method
 * (never called, so never linked), the synthetic body of a lambda and, through {@link Comparable},
 * a bridge method.
 */
public abstract class Shape implements Comparable<Shape> {

  /** Creates a shape. */
  protected Shape() {}

  /**
   * Returns the area.
   *
   * @return the area
   */
  public abstract int area();

  /** Declared, never called. */
  public static native void unlinked();

  /**
   * Returns a supplier of this shape's label, as a lambda.
   *
   * @return the supplier
   */
  public Supplier<String> label() {
    return () -> "area " + area();
  }

  @Override
  public int compareTo(final Shape other) {
    return Integer.compare(area(), other.area());
  }
}

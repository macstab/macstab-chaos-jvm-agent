package com.example.chaostarget;

/** A {@link Shape} with an area of its own. */
public final class Square extends Shape {

  private final int side;

  /**
   * Creates a square.
   *
   * @param side the side length
   */
  public Square(final int side) {
    this.side = side;
  }

  @Override
  public int area() {
    return side * side;
  }
}

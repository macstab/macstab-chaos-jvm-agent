package com.example.chaostarget;

import java.util.List;
import java.util.Optional;

/** Application class targeted by return-value corruption probes: one method per return type. */
public final class Returns {

  /** Creates the fixture. */
  public Returns() {}

  /**
   * A long.
   *
   * @return 7
   */
  public long big() {
    return 7L;
  }

  /**
   * A boolean.
   *
   * @return true
   */
  public boolean flag() {
    return true;
  }

  /**
   * A double.
   *
   * @return 1.5
   */
  public double ratio() {
    return 1.5d;
  }

  /**
   * A string.
   *
   * @return "text"
   */
  public String text() {
    return "text";
  }

  /**
   * A list.
   *
   * @return one element
   */
  public List<String> list() {
    return List.of("a");
  }

  /**
   * An optional.
   *
   * @return a present value
   */
  public Optional<String> maybe() {
    return Optional.of("a");
  }

  /**
   * An array.
   *
   * @return two elements
   */
  public int[] array() {
    return new int[] {1, 2};
  }

  /**
   * A boxed int.
   *
   * @return 5
   */
  public Integer boxed() {
    return 5;
  }
}

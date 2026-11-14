package com.example.chaostarget;

/** Interface targeted by method-weaving probes: a default and a static method. */
public interface Pricing {

  /**
   * The base price.
   *
   * @return 10
   */
  static int base() {
    return 10;
  }

  /**
   * The price.
   *
   * @return the base price plus 5
   */
  default int price() {
    return base() + 5;
  }
}

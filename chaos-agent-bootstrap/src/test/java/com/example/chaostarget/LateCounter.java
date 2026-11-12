package com.example.chaostarget;

/** Loaded only after a method scenario is active, to check classes loaded later are woven. */
public final class LateCounter {

  /** Creates a counter. */
  public LateCounter() {}

  /**
   * Returns a constant.
   *
   * @return 7
   */
  public int count() {
    return 7;
  }
}

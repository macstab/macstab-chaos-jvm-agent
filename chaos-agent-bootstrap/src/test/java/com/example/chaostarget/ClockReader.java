package com.example.chaostarget;

/** Reads the intrinsic clocks directly, as application code does. */
public final class ClockReader {

  private ClockReader() {}

  /**
   * Returns {@link System#currentTimeMillis()}.
   *
   * @return the wall-clock time
   */
  public static long millis() {
    return System.currentTimeMillis();
  }

  /**
   * Returns {@link System#nanoTime()}.
   *
   * @return the monotonic time
   */
  public static long nanos() {
    return System.nanoTime();
  }
}

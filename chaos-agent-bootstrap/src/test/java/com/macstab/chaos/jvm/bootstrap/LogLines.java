package com.macstab.chaos.jvm.bootstrap;

import java.util.List;

/**
 * Reads what the agent logged, in tests: the lines of the captured output (stderr of this JVM or a
 * child JVM's output) that carry a marker.
 */
final class LogLines {

  private LogLines() {}

  /**
   * The lines of {@code output} that contain {@code marker}, in order.
   *
   * @param output the captured output
   * @param marker the text a line must contain
   * @return the matching lines, without their line terminators
   */
  static List<String> containing(final String output, final String marker) {
    return output.lines().filter(line -> line.contains(marker)).toList();
  }
}

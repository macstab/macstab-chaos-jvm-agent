package com.macstab.chaos.jvm.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures what an action prints to {@code System.err}, where the agent writes its {@code
 * [chaos-agent]} lines. A test class that uses it holds {@code @ResourceLock("System.err")}.
 */
final class StderrCapture {

  private StderrCapture() {}

  /** An action that may throw. */
  interface Action {
    void run() throws Exception;
  }

  /**
   * Runs {@code action} with {@code System.err} redirected, and returns what it printed there.
   *
   * @param action the action
   * @return the captured text
   */
  static String of(final Action action) throws Exception {
    final PrintStream original = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }
}

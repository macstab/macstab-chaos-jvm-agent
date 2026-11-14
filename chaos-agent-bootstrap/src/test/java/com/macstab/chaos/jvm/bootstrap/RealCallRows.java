package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import org.jspecify.annotations.Nullable;

/**
 * Helpers of the probe mains that make one real call under one scenario and print what the caller
 * saw as {@code key=value} lines.
 */
final class RealCallRows {

  private RealCallRows() {}

  /** A real call that may throw anything. */
  @FunctionalInterface
  interface Call {
    void run() throws Exception;
  }

  /**
   * Runs {@code call} while a scenario of {@code selector} and {@code effect} is active, and
   * returns what it threw.
   *
   * @return the call's failure, or {@code null} when it returned
   */
  static @Nullable Throwable during(
      final String id, final ChaosSelector selector, final ChaosEffect effect, final Call call) {
    try (ChaosActivationHandle handle = activate(id, selector, effect)) {
      try {
        call.run();
        return null;
      } catch (final Throwable thrown) {
        return thrown;
      }
    }
  }

  /** Activates an always-on scenario. */
  static ChaosActivationHandle activate(
      final String id, final ChaosSelector selector, final ChaosEffect effect) {
    return ChaosPlatform.current()
        .activate(
            ChaosScenario.builder(id)
                .selector(selector)
                .effect(effect)
                .activationPolicy(ActivationPolicy.always())
                .build());
  }

  /**
   * As {@link #during}, printing {@code <row>.type} (the failure's class, or {@code none}), {@code
   * <row>.top} (its top frame as {@code class.method}) and, when it has one, {@code <row>.cause}.
   */
  static void row(
      final String row, final ChaosSelector selector, final ChaosEffect effect, final Call call) {
    print(row, during(row, selector, effect, call));
  }

  /** Prints a failure as {@link #row} does. */
  static void print(final String row, final @Nullable Throwable failure) {
    if (failure == null) {
      System.out.println(row + ".type=none");
      return;
    }
    System.out.println(row + ".type=" + failure.getClass().getName());
    final StackTraceElement[] stack = failure.getStackTrace();
    System.out.println(
        row
            + ".top="
            + (stack.length == 0
                ? "none"
                : stack[0].getClassName() + "." + stack[0].getMethodName()));
    if (failure.getCause() != null) {
      System.out.println(row + ".cause=" + failure.getCause().getClass().getName());
    }
  }

  /** The class name of what {@code call} threw, or {@code ok}. */
  static String outcome(final Call call) {
    try {
      call.run();
      return "ok";
    } catch (final Throwable thrown) {
      return thrown.getClass().getName();
    }
  }
}

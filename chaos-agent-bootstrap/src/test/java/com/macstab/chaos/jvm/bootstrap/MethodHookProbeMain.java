package com.macstab.chaos.jvm.bootstrap;

import com.example.chaostarget.Greeter;
import com.example.chaostarget.PricedItem;
import com.example.chaostarget.Pricing;
import com.example.chaostarget.Ranked;
import com.example.chaostarget.Returns;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Probe main for method hooks ({@code MethodSelector}) on application classes. Each mode prints
 * {@code key=value} lines.
 */
public final class MethodHookProbeMain {

  private MethodHookProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "bridge" -> bridge();
      case "returns" -> returns();
      case "exceptions" -> exceptions();
      case "delays" -> delays();
      case "interface" -> interfaceMethods();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  private static ChaosActivationHandle activate(
      final String id,
      final OperationType operation,
      final String type,
      final NamePattern method,
      final @Nullable NamePattern signature,
      final ChaosEffect effect) {
    return ChaosPlatform.current()
        .activate(
            ChaosScenario.builder(id)
                .selector(
                    new ChaosSelector.MethodSelector(
                        Set.of(operation), NamePattern.exact(type), method, signature))
                .effect(effect)
                .activationPolicy(ActivationPolicy.always())
                .build());
  }

  /**
   * A METHOD_ENTER delay on every method of {@link Ranked}, whose {@code compareTo} has a bridge
   * and whose {@code label} has a lambda: one call through {@code Comparable} (the bridge) and one
   * {@code label()} are two evaluations, not four. Prints {@code compare}, {@code label}, {@code
   * matched} and {@code applied}.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void bridge() {
    final Ranked one = new Ranked(1);
    final Comparable raw = one;
    try (ChaosActivationHandle delay =
        activate(
            "ranked-any",
            OperationType.METHOD_ENTER,
            "com.example.chaostarget.Ranked",
            NamePattern.any(),
            null,
            ChaosEffect.delay(Duration.ofMillis(1)))) {
      System.out.println("compare=" + Integer.signum(raw.compareTo(new Ranked(2))));
      System.out.println("label=" + one.label());
      System.out.println("matched=" + delay.matchedCount());
      System.out.println("applied=" + delay.appliedCount());
    }
  }

  /**
   * One METHOD_EXIT corruption per return type and strategy on {@link Returns}. Prints each
   * method's result and the applied counts.
   */
  private static void returns() {
    final Returns returns = new Returns();
    final String type = "com.example.chaostarget.Returns";
    try (ChaosActivationHandle big =
            corrupt(type, "big", ChaosEffect.ReturnValueStrategy.BOUNDARY_MAX);
        ChaosActivationHandle flag = corrupt(type, "flag", ChaosEffect.ReturnValueStrategy.ZERO);
        ChaosActivationHandle ratio =
            corrupt(type, "ratio", ChaosEffect.ReturnValueStrategy.BOUNDARY_MIN);
        ChaosActivationHandle text = corrupt(type, "text", ChaosEffect.ReturnValueStrategy.NULL);
        ChaosActivationHandle list = corrupt(type, "list", ChaosEffect.ReturnValueStrategy.EMPTY);
        ChaosActivationHandle maybe =
            corrupt(type, "maybe", ChaosEffect.ReturnValueStrategy.EMPTY);
        ChaosActivationHandle array =
            corrupt(type, "array", ChaosEffect.ReturnValueStrategy.EMPTY);
        ChaosActivationHandle boxed =
            corrupt(type, "boxed", ChaosEffect.ReturnValueStrategy.ZERO)) {
      System.out.println("big=" + returns.big());
      System.out.println("flag=" + returns.flag());
      System.out.println("ratio=" + returns.ratio());
      System.out.println("text=" + returns.text());
      System.out.println("list=" + returns.list());
      System.out.println("maybe=" + returns.maybe());
      System.out.println("array=" + Arrays.toString(returns.array()));
      System.out.println("boxed=" + returns.boxed());
      long applied = 0;
      for (final ChaosActivationHandle handle :
          List.of(big, flag, ratio, text, list, maybe, array, boxed)) {
        applied += handle.appliedCount();
      }
      System.out.println("applied=" + applied);
    }
  }

  private static ChaosActivationHandle corrupt(
      final String type, final String method, final ChaosEffect.ReturnValueStrategy strategy) {
    return activate(
        "corrupt-" + method,
        OperationType.METHOD_EXIT,
        type,
        NamePattern.exact(method),
        null,
        ChaosEffect.corruptReturnValue(strategy));
  }

  /**
   * Exception injection of checked JDK exceptions the method does not declare, with and without a
   * stack trace. Prints, per row, the class, message and stack depth of what the call threw.
   */
  private static void exceptions() {
    final Greeter greeter = new Greeter();
    try (ChaosActivationHandle io =
        activate(
            "io",
            OperationType.METHOD_ENTER,
            "com.example.chaostarget.Greeter",
            NamePattern.exact("greet"),
            NamePattern.prefix("(Ljava/lang/String;)"),
            new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "disk gone", false))) {
      thrown("io", () -> greeter.greet("a"));
      System.out.println("io.applied=" + io.appliedCount());
    }
    try (ChaosActivationHandle timeout =
        activate(
            "timeout",
            OperationType.METHOD_ENTER,
            "com.example.chaostarget.Greeter",
            NamePattern.exact("count"),
            null,
            new ChaosEffect.ExceptionInjectionEffect(
                "java.util.concurrent.TimeoutException", "too slow", true))) {
      thrown("timeout", greeter::count);
      System.out.println("timeout.applied=" + timeout.appliedCount());
    }
  }

  private static void thrown(final String row, final RealCallRows.Call call) {
    try {
      call.run();
      System.out.println(row + ".type=none");
    } catch (final Throwable failure) {
      System.out.println(row + ".type=" + failure.getClass().getName());
      System.out.println(row + ".message=" + failure.getMessage());
      System.out.println(row + ".stackDepth=" + failure.getStackTrace().length);
    }
  }

  /**
   * A 200 ms METHOD_ENTER delay on {@code greet} and a 200 ms METHOD_EXIT delay on {@code count}.
   * Prints how long each call took in milliseconds and the applied counts.
   */
  private static void delays() {
    final Greeter greeter = new Greeter();
    try (ChaosActivationHandle enter =
            activate(
                "enter-delay",
                OperationType.METHOD_ENTER,
                "com.example.chaostarget.Greeter",
                NamePattern.exact("greet"),
                NamePattern.prefix("(Ljava/lang/String;)"),
                ChaosEffect.delay(Duration.ofMillis(200)));
        ChaosActivationHandle exit =
            activate(
                "exit-delay",
                OperationType.METHOD_EXIT,
                "com.example.chaostarget.Greeter",
                NamePattern.exact("count"),
                null,
                ChaosEffect.delay(Duration.ofMillis(200)))) {
      long start = System.nanoTime();
      final String greeting = greeter.greet("a");
      System.out.println(
          "enter.millis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      start = System.nanoTime();
      final int count = greeter.count();
      System.out.println("exit.millis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      System.out.println("results=" + greeting + "|" + count);
      System.out.println("enter.applied=" + enter.appliedCount());
      System.out.println("exit.applied=" + exit.appliedCount());
    }
  }

  /**
   * A METHOD_EXIT {@code ZERO} on every method of the interface {@link Pricing}: its default
   * method, called on an implementation that inherits it, and its static method. Prints both
   * results and the matched and applied counts (three: {@code price()} calls {@code base()}).
   */
  private static void interfaceMethods() {
    try (ChaosActivationHandle zero =
        activate(
            "pricing-zero",
            OperationType.METHOD_EXIT,
            "com.example.chaostarget.Pricing",
            NamePattern.any(),
            null,
            ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.ZERO))) {
      System.out.println("static=" + Pricing.base());
      System.out.println("default=" + new PricedItem().price());
      System.out.println("matched=" + zero.matchedCount());
      System.out.println("applied=" + zero.appliedCount());
    }
  }
}

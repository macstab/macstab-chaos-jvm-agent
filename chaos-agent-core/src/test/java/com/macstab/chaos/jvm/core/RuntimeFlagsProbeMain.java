package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import java.time.Duration;

/**
 * Child-JVM probe for {@link CompatibilityValidatorTest}, run under the JVM flags a stressor
 * depends on: activates a safepoint storm without and with class retransforms and a finalizer
 * backlog, and prints each outcome ({@code <id>=activated} or {@code <id>=refused}).
 */
final class RuntimeFlagsProbeMain {

  private RuntimeFlagsProbeMain() {}

  public static void main(final String[] args) {
    final ChaosRuntime runtime = new ChaosRuntime();
    try {
      activate(
          runtime,
          "storm-no-retransform",
          ChaosSelector.StressTarget.SAFEPOINT_STORM,
          new ChaosEffect.SafepointStormEffect(Duration.ofSeconds(1), 0));
      activate(
          runtime,
          "storm-retransform",
          ChaosSelector.StressTarget.SAFEPOINT_STORM,
          new ChaosEffect.SafepointStormEffect(Duration.ofSeconds(1), 1));
      activate(
          runtime,
          "finalizer-backlog",
          ChaosSelector.StressTarget.FINALIZER_BACKLOG,
          new ChaosEffect.FinalizerBacklogEffect(10, Duration.ofMillis(1)));
    } finally {
      runtime.close();
    }
  }

  private static void activate(
      final ChaosRuntime runtime,
      final String id,
      final ChaosSelector.StressTarget target,
      final ChaosEffect effect) {
    try {
      runtime.activate(
          ChaosScenario.builder(id)
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.stress(target))
              .effect(effect)
              .build());
      System.out.println(id + "=activated");
    } catch (final ChaosUnsupportedFeatureException refused) {
      System.out.println(id + "=refused");
    }
  }
}

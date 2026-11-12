package com.macstab.chaos.jvm.bootstrap;

import com.example.chaostarget.ClockReader;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;

/**
 * Probe main for an agent started with {@code instrumentation=eager}. Mode {@code idle} prints the
 * hook groups woven at start and activates nothing. Mode {@code clock} prints them too, and how far
 * a clock read in {@code com.example.chaostarget} is skewed, then activates an hour-long {@code
 * SYSTEM_CLOCK_MILLIS} skew and prints the woven groups and the skew again.
 */
public final class EagerStartProbeMain {

  private EagerStartProbeMain() {}

  public static void main(final String[] args) {
    System.out.println("wovenAtStart=" + wovenGroups());
    switch (args[0]) {
      case "idle" -> {}
      case "clock" -> {
        System.out.println("skewBeforeSeconds=" + callSiteSkewSeconds());
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("eager-clock")
                    .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
                    .effect(
                        ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED))
                    .activationPolicy(ActivationPolicy.always())
                    .build());
        System.out.println("wovenAfterActivation=" + wovenGroups());
        System.out.println("skewAfterSeconds=" + callSiteSkewSeconds());
      }
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    System.out.println("done=true");
  }

  private static String wovenGroups() {
    return ChaosPlatform.current().diagnostics().snapshot().runtimeDetails().get("wovenHookGroups");
  }

  /** How far {@code System.currentTimeMillis()} read in a call-site package is ahead, in s. */
  private static long callSiteSkewSeconds() {
    return Math.round((ClockReader.millis() - System.currentTimeMillis()) / 1000.0);
  }
}

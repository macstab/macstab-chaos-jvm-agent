package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import java.time.Duration;

/**
 * Child-JVM probe for {@link CarrierPinningSchedulerTest}, run with a real virtual-thread scheduler
 * parallelism: activates carrier pinning without and with {@code allowDestructiveEffects} and
 * prints each outcome ({@code <id>=activated} or {@code <id>=refused}).
 */
final class CarrierPinningProbeMain {

  private CarrierPinningProbeMain() {}

  public static void main(final String[] args) {
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      activate(runtime, "carrier-pinning", ActivationPolicy.always());
      activate(runtime, "carrier-pinning-destructive", ActivationPolicy.withDestructiveEffects());
    }
  }

  private static void activate(
      final ChaosRuntime runtime, final String id, final ActivationPolicy policy) {
    try {
      runtime
          .activate(
              ChaosScenario.builder(id)
                  .selector(
                      ChaosSelector.stress(
                          ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
                  .effect(
                      new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMillis(10)))
                  .activationPolicy(policy)
                  .build())
          .close();
      System.out.println(id + "=activated");
    } catch (final ChaosUnsupportedFeatureException refused) {
      System.out.println(id + "=refused");
    }
  }
}

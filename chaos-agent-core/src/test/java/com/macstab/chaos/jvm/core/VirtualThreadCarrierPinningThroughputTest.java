package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the carrier-pinning stressor actually takes virtual-thread carriers out of service:
 * while it holds every carrier, a newly started virtual thread cannot run; once it stops, the
 * virtual thread runs. The 1.0.0 stressor used platform threads and passed its old tests (which
 * only counted threads) while having no effect on the scheduler at all.
 */
@DisplayName("VirtualThreadCarrierPinning holds virtual-thread carriers")
class VirtualThreadCarrierPinningThroughputTest {

  /** Carriers of the default virtual-thread scheduler. */
  private static final int CARRIERS =
      Integer.getInteger(
          "jdk.virtualThreadScheduler.parallelism", Runtime.getRuntime().availableProcessors());

  private static final ChaosEffect.VirtualThreadCarrierPinningEffect HOLD_ALL_CARRIERS =
      new ChaosEffect.VirtualThreadCarrierPinningEffect(CARRIERS, Duration.ofMillis(200));

  @Nested
  @DisplayName("activated through ChaosRuntime (strategy of the running JDK)")
  class ThroughRuntime {

    @Test
    @DisplayName("a virtual thread cannot run while all carriers are held, and runs after stop")
    void starvesVirtualThreadsUntilStopped() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("carrier-pin")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      ChaosSelector.stress(
                          ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
                  .effect(HOLD_ALL_CARRIERS)
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        assertStarvedUntil(handle::stop);
      } finally {
        handle.close();
        runtime.close();
      }
    }
  }

  @Nested
  @DisplayName("each holding strategy")
  class Strategies {

    @Test
    @DisplayName("spin-wait holds carriers on every JDK")
    void spinStrategy() throws Exception {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(HOLD_ALL_CARRIERS, false);
      try {
        assertStarvedUntil(stressor::close);
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("monitor pinning holds carriers on JDK 21-23")
    void monitorStrategy() throws Exception {
      assumeTrue(Runtime.version().feature() < 24, "synchronized no longer pins on JDK 24+");
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(HOLD_ALL_CARRIERS, true);
      try {
        assertStarvedUntil(stressor::close);
      } finally {
        stressor.close();
      }
    }
  }

  private static void assertStarvedUntil(final Runnable stop) throws InterruptedException {
    // Give the stressor's virtual threads time to mount on every carrier.
    Thread.sleep(300L);
    final CountDownLatch ran = new CountDownLatch(1);
    Thread.ofVirtual().start(ran::countDown);

    assertThat(ran.await(500L, TimeUnit.MILLISECONDS))
        .as("no carrier is free while the stressor holds all %d", CARRIERS)
        .isFalse();

    stop.run();

    assertThat(ran.await(5L, TimeUnit.SECONDS))
        .as("the waiting virtual thread runs once the carriers are released")
        .isTrue();
  }
}

package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the {@link ChaosEffect.VirtualThreadCarrierPinningEffect} activation
 * pipeline through {@link ChaosRuntime}.
 *
 * <p>The {@link VirtualThreadCarrierPinningStressor} spawns platform daemon threads that stay
 * inside a {@code synchronized} block for a configurable duration. These tests verify that
 * activating the effect via {@link ChaosRuntime#activate} properly creates and starts the stressor,
 * that the expected number of pinning threads are alive during activation, and that {@link
 * ChaosActivationHandle#stop()} cleanly terminates all stressor threads.
 */
@DisplayName("VirtualThreadCarrierPinning activation integration")
class VirtualThreadCarrierPinningThroughputTest {

  @Nested
  @DisplayName("Activation pipeline")
  class ActivationPipeline {

    @Test
    @DisplayName("activating effect via ChaosRuntime starts the expected pinning threads")
    void activatingEffectStartsPinningThreads() {
      final int pinnedCount = 3;
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("carrier-pin-start")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      ChaosSelector.stress(
                          ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
                  .effect(
                      new ChaosEffect.VirtualThreadCarrierPinningEffect(
                          pinnedCount, Duration.ofSeconds(30)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      try {
        await()
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(
                () ->
                    assertThat(aliveCarrierPinThreads())
                        .as("stressor should start %d pinning threads", pinnedCount)
                        .isEqualTo(pinnedCount));
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("stopping the handle terminates all pinning threads")
    void stoppingHandleTerminatesPinningThreads() {
      final int pinnedCount = 2;
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("carrier-pin-stop")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      ChaosSelector.stress(
                          ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
                  .effect(
                      new ChaosEffect.VirtualThreadCarrierPinningEffect(
                          pinnedCount, Duration.ofSeconds(30)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      await().atMost(3, TimeUnit.SECONDS).until(() -> aliveCarrierPinThreads() == pinnedCount);

      handle.stop();

      await()
          .atMost(3, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(aliveCarrierPinThreads())
                      .as("all pinning threads should be stopped after handle.stop()")
                      .isZero());
    }

    @Test
    @DisplayName("stop is idempotent across multiple ChaosRuntime instances")
    void stopIsIdempotentAcrossRuntimes() {
      final ChaosRuntime r1 = new ChaosRuntime();
      final ChaosRuntime r2 = new ChaosRuntime();

      final ChaosActivationHandle h1 =
          r1.activate(
              ChaosScenario.builder("carrier-pin-r1")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      ChaosSelector.stress(
                          ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
                  .effect(
                      new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofSeconds(30)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      final ChaosActivationHandle h2 =
          r2.activate(
              ChaosScenario.builder("carrier-pin-r2")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      ChaosSelector.stress(
                          ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
                  .effect(
                      new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofSeconds(30)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      await().atMost(3, TimeUnit.SECONDS).until(() -> aliveCarrierPinThreads() == 2);

      h1.stop();
      h2.stop();

      await()
          .atMost(3, TimeUnit.SECONDS)
          .untilAsserted(() -> assertThat(aliveCarrierPinThreads()).isZero());
    }
  }

  private static long aliveCarrierPinThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.getName().startsWith("chaos-carrier-pin-"))
        .filter(Thread::isAlive)
        .count();
  }
}

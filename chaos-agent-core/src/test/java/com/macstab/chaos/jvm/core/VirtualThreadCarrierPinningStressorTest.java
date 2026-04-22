package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VirtualThreadCarrierPinningStressor}.
 *
 * <p>Verifies that the stressor spawns the expected number of carrier-pinning platform threads,
 * that those threads remain alive during the pin cycle, and that {@link
 * VirtualThreadCarrierPinningStressor#close()} stops them promptly.
 */
@DisplayName("VirtualThreadCarrierPinningStressor")
class VirtualThreadCarrierPinningStressorTest {

  @Nested
  @DisplayName("Thread lifecycle")
  class ThreadLifecycle {

    @Test
    @DisplayName("spawns requested number of pinning threads")
    void spawnsRequestedNumberOfPinningThreads() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(3, Duration.ofSeconds(10)));
      try {
        await()
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(stressor.aliveCount()).isEqualTo(3));
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("threads are alive after construction")
    void threadsAreAliveAfterConstruction() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(2, Duration.ofSeconds(10)));
      try {
        await()
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(stressor.aliveCount()).isGreaterThan(0));
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("close() stops all pinning threads")
    void closeStopsAllPinningThreads() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(2, Duration.ofSeconds(10)));
      await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 2);
      stressor.close();
      await()
          .atMost(3, TimeUnit.SECONDS)
          .untilAsserted(() -> assertThat(stressor.aliveCount()).isZero());
    }

    @Test
    @DisplayName("close() does not throw")
    void closeDoesNotThrow() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofSeconds(10)));
      assertThatCode(stressor::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("close() is idempotent")
    void closeIsIdempotent() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofSeconds(10)));
      assertThatCode(
              () -> {
                stressor.close();
                stressor.close();
              })
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("Short pin cycle")
  class ShortPinCycle {

    @Test
    @DisplayName("threads survive multiple pin-release cycles")
    void threadsSurviveMultiplePinReleaseCycles() throws Exception {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(2, Duration.ofMillis(20)));
      try {
        // Allow several pin-release cycles; threads must stay alive throughout.
        Thread.sleep(150);
        assertThat(stressor.aliveCount()).isEqualTo(2);
      } finally {
        stressor.close();
      }
    }
  }
}

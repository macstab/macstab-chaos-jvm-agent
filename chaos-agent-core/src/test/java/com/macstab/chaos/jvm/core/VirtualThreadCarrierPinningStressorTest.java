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
    @DisplayName("close() is idempotent")
    void closeIsIdempotent() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofSeconds(10)));
      await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 1);
      stressor.close();

      assertThatCode(stressor::close).doesNotThrowAnyException();
      assertThat(stressor.aliveCount()).as("the first close stopped the thread").isZero();
    }
  }

  @Nested
  @DisplayName("Short pin cycle")
  class ShortPinCycle {

    @Test
    @DisplayName("with a short pin duration the pinning threads stay alive while active")
    void threadsStayAliveWithShortPins() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(2, Duration.ofMillis(20)));
      try {
        await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 2);
        await()
            .during(Duration.ofMillis(150))
            .atMost(Duration.ofSeconds(2))
            .until(() -> stressor.aliveCount() == 2);
      } finally {
        stressor.close();
      }
    }
  }

  @Nested
  @DisplayName("Spin-wait holding (JDK 24+)")
  class SpinWait {

    @Test
    @DisplayName("a spin thread ends on interrupt alone, as after the rollback of a partial start")
    void spinEndsOnInterrupt() throws InterruptedException {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofSeconds(10)), false);
      // The rollback of a failed start interrupts the threads it started without setting the stop
      // flag: a spin that ignores the interrupt keeps its carrier and a CPU until the JVM exits.
      final Thread spinner =
          new Thread(() -> stressor.occupyWithSpin(Duration.ofSeconds(10).toNanos()));
      try {
        spinner.start();
        spinner.interrupt();
        spinner.join(TimeUnit.SECONDS.toMillis(3));
        assertThat(spinner.isAlive()).isFalse();
      } finally {
        stressor.close();
        spinner.join(TimeUnit.SECONDS.toMillis(3));
      }
    }
  }
}

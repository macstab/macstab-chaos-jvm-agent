package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.api.ChaosEffect;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the eight stressor implementations introduced in Task 10. Each test verifies basic
 * lifecycle behaviour (construction does not throw, retained-count accessors return expected
 * values, close() does not throw and makes the stressor stop or release resources).
 */
@DisplayName("Stressor lifecycle")
class StressorLifecycleTest {

  @Nested
  @DisplayName("MetaspacePressureStressor")
  class MetaspacePressureTests {

    @Test
    @DisplayName("retain=true holds references after construction")
    void retainTrueHoldsReferences() {
      final MetaspacePressureStressor stressor =
          new MetaspacePressureStressor(new ChaosEffect.MetaspacePressureEffect(5, 2, true));
      assertThat(stressor.retainedClassCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("retain=false retains nothing")
    void retainFalseRetainsNothing() {
      final MetaspacePressureStressor stressor =
          new MetaspacePressureStressor(new ChaosEffect.MetaspacePressureEffect(5, 0, false));
      assertThat(stressor.retainedClassCount()).isZero();
    }

    @Test
    @DisplayName("close() nulls retained class list")
    void closeNullsRetainedClassList() {
      final MetaspacePressureStressor stressor =
          new MetaspacePressureStressor(new ChaosEffect.MetaspacePressureEffect(3, 0, true));
      stressor.close();
      assertThat(stressor.retainedClassCount()).isZero();
    }
  }

  @Nested
  @DisplayName("DirectBufferPressureStressor")
  class DirectBufferPressureTests {

    @Test
    @DisplayName("registerCleaner=false retains buffers")
    void retainsModeRetainsBuffers() {
      final DirectBufferPressureStressor stressor =
          new DirectBufferPressureStressor(
              new ChaosEffect.DirectBufferPressureEffect(4096L, 1024, false));
      assertThat(stressor.retainedBufferCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("registerCleaner=true retains no strong references")
    void cleanerModeRetainsNoStrongRefs() {
      final DirectBufferPressureStressor stressor =
          new DirectBufferPressureStressor(
              new ChaosEffect.DirectBufferPressureEffect(4096L, 1024, true));
      assertThat(stressor.retainedBufferCount()).isZero();
    }

    @Test
    @DisplayName("close() does not throw")
    void closeDoesNotThrow() {
      final DirectBufferPressureStressor stressor =
          new DirectBufferPressureStressor(
              new ChaosEffect.DirectBufferPressureEffect(2048L, 1024, false));
      assertThatCode(stressor::close).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("GcPressureStressor")
  class GcPressureTests {

    @Test
    @DisplayName("allocation thread starts and is running immediately")
    void allocationThreadIsRunning() throws Exception {
      final GcPressureStressor stressor =
          new GcPressureStressor(
              new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ofSeconds(10)));
      try {
        assertThat(stressor.isRunning()).isTrue();
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("close() stops allocation thread")
    void closeStopsAllocationThread() throws Exception {
      final GcPressureStressor stressor =
          new GcPressureStressor(
              new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ofSeconds(10)));
      stressor.close();
      // Give the thread a moment to notice the interrupt.
      Thread.sleep(100);
      assertThat(stressor.isRunning()).isFalse();
    }
  }

  @Nested
  @DisplayName("FinalizerBacklogStressor")
  class FinalizerBacklogTests {

    @Test
    @DisplayName("createdCount matches objectCount")
    void createdCountMatchesObjectCount() {
      final FinalizerBacklogStressor stressor =
          new FinalizerBacklogStressor(new ChaosEffect.FinalizerBacklogEffect(20, Duration.ZERO));
      assertThat(stressor.createdCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("close() does not throw")
    void closeDoesNotThrow() {
      final FinalizerBacklogStressor stressor =
          new FinalizerBacklogStressor(new ChaosEffect.FinalizerBacklogEffect(5, Duration.ZERO));
      assertThatCode(stressor::close).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("ThreadLeakStressor")
  class ThreadLeakTests {

    @Test
    @DisplayName("spawns expected number of threads")
    void spawnsExpectedNumberOfThreads() throws Exception {
      final ThreadLeakStressor stressor =
          new ThreadLeakStressor(new ChaosEffect.ThreadLeakEffect(3, "leak-test-", true, null));
      try {
        // Give threads time to start.
        Thread.sleep(100);
        assertThat(stressor.aliveCount()).isEqualTo(3);
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("close() terminates leaked threads")
    void closeTerminatesLeakedThreads() throws Exception {
      final ThreadLeakStressor stressor =
          new ThreadLeakStressor(new ChaosEffect.ThreadLeakEffect(2, "leak-close-", true, null));
      stressor.close();
      // Allow threads to respond to the interrupt.
      Thread.sleep(200);
      assertThat(stressor.aliveCount()).isZero();
    }

    @Test
    @DisplayName("lifespan terminates threads after the configured duration")
    void lifespanTerminatesThreadsAfterDuration() throws Exception {
      final ThreadLeakStressor stressor =
          new ThreadLeakStressor(
              new ChaosEffect.ThreadLeakEffect(2, "lifespan-", true, Duration.ofMillis(100)));
      Thread.sleep(400);
      assertThat(stressor.aliveCount()).isZero();
    }
  }

  @Nested
  @DisplayName("DeadlockStressor")
  class DeadlockTests {

    @Test
    @DisplayName("participant threads start and are alive")
    void participantThreadsStart() throws Exception {
      final DeadlockStressor stressor =
          new DeadlockStressor(new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(50)));
      try {
        // Wait for threads to acquire their first locks.
        Thread.sleep(200);
        assertThat(stressor.aliveCount()).isEqualTo(2);
      } finally {
        stressor.close();
        Thread.sleep(200);
      }
    }

    @Test
    @DisplayName("close() interrupts participant threads")
    void closeInterruptsParticipants() throws Exception {
      final DeadlockStressor stressor =
          new DeadlockStressor(new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(10)));
      // Allow deadlock to form.
      Thread.sleep(200);
      stressor.close();
      Thread.sleep(300);
      assertThat(stressor.aliveCount()).isZero();
    }
  }

  @Nested
  @DisplayName("ThreadLocalLeakStressor")
  class ThreadLocalLeakTests {

    @Test
    @DisplayName("plantedCount equals parallelism * entriesPerThread")
    void plantedCountMatchesExpected() {
      final int parallelism = java.util.concurrent.ForkJoinPool.commonPool().getParallelism();
      final int entriesPerThread = 3;
      final ThreadLocalLeakStressor stressor =
          new ThreadLocalLeakStressor(new ChaosEffect.ThreadLocalLeakEffect(entriesPerThread, 64));
      assertThat(stressor.plantedCount()).isEqualTo(parallelism * entriesPerThread);
    }

    @Test
    @DisplayName("close() does not throw")
    void closeDoesNotThrow() {
      final ThreadLocalLeakStressor stressor =
          new ThreadLocalLeakStressor(new ChaosEffect.ThreadLocalLeakEffect(1, 32));
      assertThatCode(stressor::close).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("MonitorContentionStressor")
  class MonitorContentionTests {

    @Test
    @DisplayName("contending threads start and are alive")
    void contendingThreadsStart() throws Exception {
      final MonitorContentionStressor stressor =
          new MonitorContentionStressor(
              new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 3, false));
      try {
        Thread.sleep(100);
        assertThat(stressor.aliveCount()).isEqualTo(3);
      } finally {
        stressor.close();
        Thread.sleep(200);
      }
    }

    @Test
    @DisplayName("close() stops all contending threads")
    void closeStopsContendingThreads() throws Exception {
      final MonitorContentionStressor stressor =
          new MonitorContentionStressor(
              new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(5), 2, true));
      Thread.sleep(100);
      stressor.close();
      Thread.sleep(300);
      assertThat(stressor.aliveCount()).isZero();
    }
  }
}

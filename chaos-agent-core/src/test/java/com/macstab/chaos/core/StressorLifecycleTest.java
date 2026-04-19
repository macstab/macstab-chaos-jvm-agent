package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.api.ChaosEffect;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
    @DisplayName("registerCleaner=true still retains buffers so pressure is actually produced")
    void cleanerModeAlsoRetainsBuffers() {
      // Previously the cleaner mode used a throwaway referent that became unreachable
      // immediately, causing the cleaner to fire before any sustained pressure could build.
      // The stressor now retains strong references in both modes — the cleaner is attached
      // to the stressor itself, so cleanup happens on stressor phantom-reachability rather
      // than on per-buffer GC.
      final DirectBufferPressureStressor stressor =
          new DirectBufferPressureStressor(
              new ChaosEffect.DirectBufferPressureEffect(4096L, 1024, true));
      assertThat(stressor.retainedBufferCount()).isEqualTo(4);
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
    void closeStopsAllocationThread() {
      final GcPressureStressor stressor =
          new GcPressureStressor(
              new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ofSeconds(10)));
      stressor.close();
      // Poll rather than fixed sleep. An interrupt-wake round-trip varies from microseconds
      // on an unloaded machine to hundreds of milliseconds on an over-committed CI runner, so
      // a fixed 100 ms sleep either races or wastes time. Awaitility fails fast on condition
      // change and has an explicit upper bound, which is what a reliable assertion needs.
      await().atMost(2, TimeUnit.SECONDS).until(() -> !stressor.isRunning());
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
    void spawnsExpectedNumberOfThreads() {
      final ThreadLeakStressor stressor =
          new ThreadLeakStressor(new ChaosEffect.ThreadLeakEffect(3, "leak-test-", true, null));
      try {
        await().atMost(2, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 3);
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("close() terminates leaked threads")
    void closeTerminatesLeakedThreads() {
      final ThreadLeakStressor stressor =
          new ThreadLeakStressor(new ChaosEffect.ThreadLeakEffect(2, "leak-close-", true, null));
      stressor.close();
      await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 0);
    }

    @Test
    @DisplayName("lifespan terminates threads after the configured duration")
    void lifespanTerminatesThreadsAfterDuration() {
      final ThreadLeakStressor stressor =
          new ThreadLeakStressor(
              new ChaosEffect.ThreadLeakEffect(2, "lifespan-", true, Duration.ofMillis(100)));
      await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 0);
    }
  }

  @Nested
  @DisplayName("DeadlockStressor")
  class DeadlockTests {

    @Test
    @DisplayName("participant threads start and are alive")
    void participantThreadsStart() {
      final DeadlockStressor stressor =
          new DeadlockStressor(new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(50)));
      try {
        await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 2);
      } finally {
        stressor.close();
        await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 0);
      }
    }

    @Test
    @DisplayName("close() interrupts participant threads")
    void closeInterruptsParticipants() {
      final DeadlockStressor stressor =
          new DeadlockStressor(new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(10)));
      await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 2);
      stressor.close();
      await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 0);
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
    void contendingThreadsStart() {
      final MonitorContentionStressor stressor =
          new MonitorContentionStressor(
              new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 3, false));
      try {
        await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 3);
      } finally {
        stressor.close();
        await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 0);
      }
    }

    @Test
    @DisplayName("close() stops all contending threads")
    void closeStopsContendingThreads() {
      final MonitorContentionStressor stressor =
          new MonitorContentionStressor(
              new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(5), 2, true));
      await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 2);
      stressor.close();
      await().atMost(3, TimeUnit.SECONDS).until(() -> stressor.aliveCount() == 0);
    }
  }
}

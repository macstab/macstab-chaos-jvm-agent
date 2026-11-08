package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the lifecycle of eight stressors: what they retain or start when built, and that
 * close() stops them or releases what they hold.
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
      // The stressor retains strong references in both modes — the cleaner is attached to the
      // stressor itself, so cleanup happens on stressor phantom-reachability rather than on
      // per-buffer GC, and sustained pressure builds before any cleaner fires.
      final DirectBufferPressureStressor stressor =
          new DirectBufferPressureStressor(
              new ChaosEffect.DirectBufferPressureEffect(4096L, 1024, true));
      assertThat(stressor.retainedBufferCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("close() releases the retained buffers")
    void closeReleasesBuffers() {
      final DirectBufferPressureStressor stressor =
          new DirectBufferPressureStressor(
              new ChaosEffect.DirectBufferPressureEffect(2048L, 1024, false));
      stressor.close();
      assertThat(stressor.retainedBufferCount()).isZero();
    }

    @Test
    @DisplayName("running out of direct memory frees what it allocated and rethrows")
    void outOfDirectMemoryFreesWhatItAllocated() throws Exception {
      final Process process =
          new ProcessBuilder(
                  java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-XX:MaxDirectMemorySize=8m",
                  // The packages the agent jar's manifest opens for the eager free.
                  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                  "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
                  "-cp",
                  System.getProperty("java.class.path"),
                  DirectBufferOomProbeMain.class.getName())
              .redirectErrorStream(true)
              .start();
      final String output;
      try {
        output =
            new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
      } finally {
        process.destroyForcibly();
      }

      assertThat(output).contains("thrown=OutOfMemoryError");
      final long used =
          output
              .lines()
              .filter(line -> line.startsWith("directUsedAfter="))
              .mapToLong(line -> Long.parseLong(line.substring("directUsedAfter=".length())))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no directUsedAfter= line in:\n" + output));
      assertThat(used).as(output).isLessThan(1024L * 1024L);
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
    @DisplayName("close() releases every planted value, even while every pool worker is busy")
    void closeReleasesValuesOnBusyPool() throws Exception {
      final ThreadLocalLeakStressor stressor =
          new ThreadLocalLeakStressor(new ChaosEffect.ThreadLocalLeakEffect(1, 1024));
      assertThat(stressor.retainedBytes()).isPositive();
      final java.util.concurrent.ForkJoinPool pool = java.util.concurrent.ForkJoinPool.commonPool();
      final java.util.concurrent.CountDownLatch release =
          new java.util.concurrent.CountDownLatch(1);
      final java.util.concurrent.CountDownLatch busy =
          new java.util.concurrent.CountDownLatch(pool.getParallelism());
      try {
        for (int i = 0; i < pool.getParallelism(); i++) {
          pool.execute(
              () -> {
                busy.countDown();
                try {
                  release.await();
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              });
        }
        assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();

        assertTimeoutPreemptively(Duration.ofSeconds(5), stressor::close);
        assertThat(stressor.retainedBytes()).isZero();
      } finally {
        release.countDown();
      }
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

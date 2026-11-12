package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Monitor contention produces real contention: its threads queue for the one shared lock. */
@DisplayName("Monitor contention load")
class MonitorContentionLoadTest {

  @Test
  @DisplayName("contenders wait for the lock another contender holds")
  void contendersWaitForTheHeldLock() {
    final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    final MonitorContentionStressor stressor =
        new MonitorContentionStressor(
            new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(20), 3, false));
    try {
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                final ThreadInfo[] contenders =
                    Arrays.stream(threads.dumpAllThreads(true, true))
                        .filter(info -> info.getThreadName().startsWith("chaos-contention-"))
                        .toArray(ThreadInfo[]::new);
                assertThat(contenders).hasSize(3);
                // One holds the lock (an owned synchronizer); the others are parked on it.
                assertThat(contenders)
                    .filteredOn(
                        info ->
                            info.getThreadState() == Thread.State.WAITING
                                && info.getLockInfo() != null
                                && info.getLockInfo().getClassName().contains("ReentrantLock")
                                && info.getLockOwnerName() != null
                                && info.getLockOwnerName().startsWith("chaos-contention-"))
                    .hasSizeGreaterThanOrEqualTo(1);
              });
    } finally {
      stressor.close();
    }
  }
}

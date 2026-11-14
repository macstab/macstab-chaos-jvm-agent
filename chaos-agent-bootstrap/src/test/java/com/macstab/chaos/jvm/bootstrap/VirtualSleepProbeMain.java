package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Probe main for {@code Thread.sleep} on a virtual thread under a delay and under a suppress. */
public final class VirtualSleepProbeMain {

  private VirtualSleepProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "sleep" -> sleep();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * A virtual thread sleeps 50 ms under a 300 ms delay, then 5 s under a suppress. Prints how long
   * each sleep took in milliseconds and the applied counts.
   */
  private static void sleep() throws Exception {
    final ChaosSelector virtualSleeps =
        ChaosSelector.thread(Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.VIRTUAL);
    try (ChaosActivationHandle delay =
        RealCallRows.activate(
            "sleep-delay", virtualSleeps, ChaosEffect.delay(Duration.ofMillis(300)))) {
      System.out.println("delay.millis=" + sleepOnVirtualThread(50));
      System.out.println("delay.applied=" + delay.appliedCount());
    }
    try (ChaosActivationHandle suppress =
        RealCallRows.activate("sleep-suppress", virtualSleeps, ChaosEffect.suppress())) {
      System.out.println("suppress.millis=" + sleepOnVirtualThread(5_000));
      System.out.println("suppress.applied=" + suppress.appliedCount());
    }
    System.out.println("platform.millis=" + sleepOnPlatformThread());
  }

  private static long sleepOnVirtualThread(final long millis) throws Exception {
    final AtomicLong elapsed = new AtomicLong(-1);
    Thread.ofVirtual()
        .start(
            () -> {
              final long start = System.nanoTime();
              try {
                Thread.sleep(millis);
              } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              elapsed.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            })
        .join();
    return elapsed.get();
  }

  /** A platform thread's sleep is not selected by a VIRTUAL thread selector. */
  private static long sleepOnPlatformThread() throws Exception {
    try (ChaosActivationHandle suppress =
        RealCallRows.activate(
            "sleep-suppress-virtual-only",
            ChaosSelector.thread(
                Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.VIRTUAL),
            ChaosEffect.suppress())) {
      final long start = System.nanoTime();
      Thread.sleep(100);
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
  }
}

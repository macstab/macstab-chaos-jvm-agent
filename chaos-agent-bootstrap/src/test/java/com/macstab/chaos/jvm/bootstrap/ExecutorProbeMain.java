package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Probe main for executor hooks on real pools: worker runs of pools that override the JDK's
 * template methods. Each mode prints {@code key=value} lines.
 */
public final class ExecutorProbeMain {

  private static final long DELAY_MILLIS = 300L;

  private ExecutorProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "worker-run-overrides" -> workerRunOverrides();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * One task on a plain pool, one on a pool overriding {@code beforeExecute} without {@code super}
   * and one on a pool calling {@code super}, under an {@code EXECUTOR_WORKER_RUN} delay. Prints
   * {@code <pool>.millis} (how long the task took to run) and {@code <pool>.applied}.
   */
  private static void workerRunOverrides() throws Exception {
    final ThreadPoolExecutor[] pools = {
      new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()),
      new com.example.chaostarget.WorkerPools.Overriding(),
      new com.example.chaostarget.WorkerPools.Delegating()
    };
    final String[] names = {"plain", "overriding", "delegating"};
    for (int i = 0; i < pools.length; i++) {
      // Start the worker first: its first task's run is the one measured.
      pools[i].prestartAllCoreThreads();
      try (ChaosActivationHandle delay =
          RealCallRows.activate(
              names[i],
              ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MILLIS)))) {
        final long start = System.nanoTime();
        pools[i].submit(() -> {}).get(10, TimeUnit.SECONDS);
        System.out.println(names[i] + ".millis=" + (System.nanoTime() - start) / 1_000_000L);
        System.out.println(names[i] + ".applied=" + delay.appliedCount());
      }
      pools[i].shutdownNow();
    }
  }
}

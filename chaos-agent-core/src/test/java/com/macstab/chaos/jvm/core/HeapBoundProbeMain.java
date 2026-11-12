package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Duration;

/**
 * Child-JVM probe for {@link StressorHeapBoundTest}, run with a small heap: activates a
 * thread-local leak and a reference-queue flood each larger than the heap, and prints each outcome
 * ({@code <id>=activated} or {@code <id>=refused}), the refusal's message, and the share of the
 * maximum heap the activation used at its peak. With the argument {@code small-values} it instead
 * activates a thread-local leak of many 1-byte values, whose entries rather than values exceed the
 * heap, and runs one cycle of a reference-queue flood sized at three quarters of its bound,
 * printing {@code flood-at-bound=cycled} or {@code flood-at-bound=failed}.
 */
final class HeapBoundProbeMain {

  private HeapBoundProbeMain() {}

  public static void main(final String[] args) throws InterruptedException {
    final ChaosRuntime runtime = new ChaosRuntime();
    try {
      if (args.length > 0 && args[0].equals("small-values")) {
        activate(
            runtime,
            "small-thread-local-leak",
            ChaosSelector.StressTarget.THREAD_LOCAL_LEAK,
            new ChaosEffect.ThreadLocalLeakEffect(500_000, 1));
        floodAtBound(runtime);
        return;
      }
      activate(
          runtime,
          "thread-local-leak",
          ChaosSelector.StressTarget.THREAD_LOCAL_LEAK,
          new ChaosEffect.ThreadLocalLeakEffect(1_000, 1_000_000));
      activate(
          runtime,
          "reference-queue-flood",
          ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD,
          new ChaosEffect.ReferenceQueueFloodEffect(100_000_000, Duration.ofSeconds(1)));
    } finally {
      runtime.close();
    }
  }

  /**
   * Validates a flood whose estimate is three quarters of the free heap, then runs it until one
   * cycle completes: the estimate per reference is at least what a cycle really keeps reachable.
   */
  private static void floodAtBound(final ChaosRuntime runtime) throws InterruptedException {
    final Runtime jvm = Runtime.getRuntime();
    final long free = jvm.maxMemory() - (jvm.totalMemory() - jvm.freeMemory());
    final ChaosEffect.ReferenceQueueFloodEffect effect =
        new ChaosEffect.ReferenceQueueFloodEffect(
            (int) (free * 3 / 4 / CompatibilityValidator.REFERENCE_BYTES), Duration.ofHours(1));
    runtime.validate(
        ChaosScenario.builder("flood-at-bound")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD))
            .effect(effect)
            .build());
    final java.util.concurrent.CountDownLatch failed = new java.util.concurrent.CountDownLatch(1);
    final ReferenceQueueFloodStressor flood =
        new ReferenceQueueFloodStressor(effect, reason -> failed.countDown());
    try {
      while (flood.floodCycleCount() == 0L
          && !failed.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        // one cycle, or the flood thread's end
      }
      System.out.println("flood-at-bound=" + (flood.floodCycleCount() > 0L ? "cycled" : "failed"));
    } finally {
      flood.close();
    }
  }

  private static void activate(
      final ChaosRuntime runtime,
      final String id,
      final ChaosSelector.StressTarget target,
      final ChaosEffect effect) {
    for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      pool.resetPeakUsage();
    }
    try {
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder(id)
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.stress(target))
                  .effect(effect)
                  .build());
      System.out.println(id + "=activated");
      handle.close();
    } catch (final ChaosUnsupportedFeatureException refused) {
      System.out.println(id + "=refused");
      System.out.println(id + ".message=" + refused.getMessage());
    }
    long peak = 0L;
    for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        peak += pool.getPeakUsage().getUsed();
      }
    }
    System.out.println(id + ".peakPercent=" + peak * 100L / Runtime.getRuntime().maxMemory());
  }
}

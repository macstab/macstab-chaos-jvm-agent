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
 * maximum heap the activation used at its peak.
 */
final class HeapBoundProbeMain {

  private HeapBoundProbeMain() {}

  public static void main(final String[] args) {
    final ChaosRuntime runtime = new ChaosRuntime();
    try {
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

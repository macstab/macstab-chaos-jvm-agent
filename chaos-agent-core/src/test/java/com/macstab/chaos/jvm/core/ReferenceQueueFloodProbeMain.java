package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.time.Duration;

/**
 * Child-JVM probe for {@link NewStressorLifecycleTest}: runs a {@link ReferenceQueueFloodStressor}
 * with 10,000 references every millisecond for 30 seconds and prints its flood cycle count after 25
 * seconds ({@code early=}) and after 30 seconds ({@code late=}).
 */
final class ReferenceQueueFloodProbeMain {

  private ReferenceQueueFloodProbeMain() {}

  public static void main(final String[] args) throws Exception {
    final ReferenceQueueFloodStressor stressor =
        new ReferenceQueueFloodStressor(
            new ChaosEffect.ReferenceQueueFloodEffect(10_000, Duration.ofMillis(1)));
    Thread.sleep(25_000L);
    final long early = stressor.floodCycleCount();
    Thread.sleep(5_000L);
    final long late = stressor.floodCycleCount();
    System.out.println("early=" + early);
    System.out.println("late=" + late);
    stressor.close();
  }
}

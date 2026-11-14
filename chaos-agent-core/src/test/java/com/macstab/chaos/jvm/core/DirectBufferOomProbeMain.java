package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;

/**
 * Child-JVM probe for {@link StressorLifecycleTest}, run with {@code -XX:MaxDirectMemorySize=8m}:
 * asks a {@link DirectBufferPressureStressor} for 16 MiB and prints what it threw ({@code thrown=})
 * and the direct memory still in use right after ({@code directUsedAfter=}), before any collection
 * could free it.
 */
final class DirectBufferOomProbeMain {

  private DirectBufferOomProbeMain() {}

  public static void main(final String[] args) {
    try {
      new DirectBufferPressureStressor(
          new ChaosEffect.DirectBufferPressureEffect(16L * 1024L * 1024L, 1024 * 1024, false));
      System.out.println("thrown=none");
    } catch (final OutOfMemoryError exhausted) {
      System.out.println("thrown=" + exhausted.getClass().getSimpleName());
    }
    long used = 0;
    for (final BufferPoolMXBean pool :
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
      if (pool.getName().equals("direct")) {
        used = pool.getMemoryUsed();
      }
    }
    System.out.println("directUsedAfter=" + used);
  }
}

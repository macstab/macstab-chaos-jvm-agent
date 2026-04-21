package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.lang.instrument.Instrumentation;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Creates stop-the-world safepoint pauses by repeatedly calling {@link System#gc()} at a
 * configurable rate, simulating conditions where the JVM is frequently paused and remote callers
 * experience connection timeouts.
 *
 * <p>If an {@link Instrumentation} instance is provided and {@code retransformClassCount > 0}, the
 * stressor also retransforms a sample of loaded classes each cycle. Class retransformation forces
 * the JVM to enumerate all threads at a safepoint, extending pause durations beyond a simple GC
 * stop.
 *
 * <p>The stressor runs on a daemon thread so it does not prevent JVM shutdown. {@link #close()}
 * signals the thread to stop and waits up to 5 seconds for it to exit.
 */
final class SafepointStormStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger(SafepointStormStressor.class.getName());

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong gcCycleCount = new AtomicLong();
  private final Thread stormThread;

  SafepointStormStressor(
      final ChaosEffect.SafepointStormEffect effect,
      final Optional<Instrumentation> instrumentation) {
    final long intervalMillis = effect.gcInterval().toMillis();
    final int retransformCount = effect.retransformClassCount();

    stormThread =
        new Thread(
            () -> {
              while (running.get()) {
                System.gc();
                gcCycleCount.incrementAndGet();
                if (retransformCount > 0) {
                  retransformSample(instrumentation, retransformCount);
                }
                if (!running.get()) {
                  break;
                }
                try {
                  Thread.sleep(intervalMillis);
                } catch (final InterruptedException interruptedException) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            },
            "macstab-chaos-safepoint-storm");
    stormThread.setDaemon(true);
    stormThread.start();
    LOGGER.fine(
        "SafepointStormStressor started; gcInterval="
            + intervalMillis
            + "ms, retransformClassCount="
            + retransformCount);
  }

  @Override
  public void close() {
    running.set(false);
    stormThread.interrupt();
    try {
      stormThread.join(5_000L);
    } catch (final InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    }
    LOGGER.fine("SafepointStormStressor stopped after " + gcCycleCount.get() + " GC cycles");
  }

  /** Returns the total number of GC cycles triggered so far. */
  long gcCycleCount() {
    return gcCycleCount.get();
  }

  /** Returns the cumulative GC count across all collectors, useful for test assertions. */
  static long totalGcCount() {
    long total = 0L;
    final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    for (final GarbageCollectorMXBean bean : gcBeans) {
      final long count = bean.getCollectionCount();
      if (count > 0L) {
        total += count;
      }
    }
    return total;
  }

  private static void retransformSample(
      final Optional<Instrumentation> optInstrumentation, final int count) {
    if (optInstrumentation.isEmpty()) {
      return;
    }
    final Instrumentation inst = optInstrumentation.get();
    if (!inst.isRetransformClassesSupported()) {
      return;
    }
    final Class<?>[] allClasses = inst.getAllLoadedClasses();
    final int step = allClasses.length > count ? allClasses.length / count : 1;
    int retransformed = 0;
    for (int i = 0; i < allClasses.length && retransformed < count; i += step) {
      final Class<?> cls = allClasses[i];
      if (!isSafeToRetransform(cls) || !inst.isModifiableClass(cls)) {
        continue;
      }
      try {
        inst.retransformClasses(cls);
        retransformed++;
      } catch (final Exception exception) {
        // Some system classes cannot be retransformed; skip silently.
      }
    }
  }

  /**
   * Rejects classes we must never retransform. Retransforming the agent's own classes — in
   * particular anything under {@code com.macstab.chaos} — risks re-applying advice visitors whose
   * bytecode state assumes the unwoven form, which would NoClassDefFoundError the live scenario out
   * from under itself. Similarly, ByteBuddy's own classes sit in the transformation pipeline and
   * reentering them during a retransform can deadlock AgentBuilder's internal locks. Array and
   * hidden classes are not legal retransformation targets and would waste the sample slot.
   */
  private static boolean isSafeToRetransform(final Class<?> cls) {
    if (cls.isArray() || cls.isHidden() || cls.isPrimitive()) {
      return false;
    }
    final String name = cls.getName();
    if (name.startsWith("com.macstab.chaos.")
        || name.startsWith("net.bytebuddy.")
        || name.startsWith("jdk.internal.")) {
      return false;
    }
    return true;
  }
}

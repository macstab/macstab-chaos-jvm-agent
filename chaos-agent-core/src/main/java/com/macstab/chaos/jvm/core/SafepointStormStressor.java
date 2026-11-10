package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.instrument.Instrumentation;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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

  private static final AgentLog LOGGER = AgentLog.named(SafepointStormStressor.class.getName());

  /**
   * ByteBuddy's package, whose classes the agent never touches. Built at run time: the agent jar
   * relocates ByteBuddy, and with it a {@code "net.bytebuddy."} literal, which would then name only
   * the agent's own relocated copy and no longer an application's ByteBuddy.
   */
  private static final String BYTE_BUDDY_PACKAGE_PREFIX = String.join(".", "net", "bytebuddy", "");

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong gcCycleCount = new AtomicLong();
  private final Thread stormThread;

  SafepointStormStressor(
      final ChaosEffect.SafepointStormEffect effect,
      final Optional<Instrumentation> instrumentation) {
    this(effect, instrumentation, NO_END);
  }

  /**
   * Creates and starts the storm thread, which ends the scenario through {@code onEnd} if it fails.
   *
   * @param effect the effect parameters
   * @param instrumentation the agent's instrumentation, for the retransforms
   * @param onEnd the scenario's end callback, told when a stressor thread fails
   */
  SafepointStormStressor(
      final ChaosEffect.SafepointStormEffect effect,
      final Optional<Instrumentation> instrumentation,
      final Consumer<String> onEnd) {
    // Slept as a Duration: one too large for milliseconds lasts until close().
    final java.time.Duration interval = effect.gcInterval();
    final int retransformCount = effect.retransformClassCount();

    stormThread =
        AgentThreads.start(
            "macstab-chaos-safepoint-storm",
            true,
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
                  Thread.sleep(interval);
                } catch (final InterruptedException interruptedException) {
                  Thread.currentThread().interrupt();
                  ManagedStressor.interrupted(onEnd, "SafepointStorm", !running.get());
                  break;
                }
              }
            },
            ManagedStressor.failure(onEnd, "SafepointStorm"));
    LOGGER.fine(
        () ->
            "[chaos-agent] SafepointStormStressor started; gcInterval="
                + interval
                + ", retransformClassCount="
                + retransformCount);
  }

  @Override
  public void close() {
    running.set(false);
    AgentThreads.stopAll(java.util.List.of(stormThread), 5_000L);
    LOGGER.fine(
        () ->
            "[chaos-agent] SafepointStormStressor stopped after "
                + gcCycleCount.get()
                + " GC cycles");
  }

  /** Returns the total number of GC cycles triggered so far. */
  long gcCycleCount() {
    return gcCycleCount.get();
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
        // Some system classes cannot be retransformed: skip the class, with a trace.
        LOGGER.fine(
            () ->
                "[chaos-agent] SafepointStormStressor skipped " + cls.getName() + ": " + exception);
      }
    }
  }

  /**
   * Rejects classes we must never retransform. Retransforming the agent's own classes — in
   * particular anything under {@code com.macstab.chaos.jvm} — risks re-applying advice visitors
   * whose bytecode state assumes the unwoven form, which would NoClassDefFoundError the live
   * scenario out from under itself. Similarly, ByteBuddy's own classes sit in the transformation
   * pipeline and reentering them during a retransform can deadlock AgentBuilder's internal locks.
   * Array and hidden classes are not legal retransformation targets and would waste the sample
   * slot.
   */
  private static boolean isSafeToRetransform(final Class<?> cls) {
    if (cls.isArray() || cls.isHidden() || cls.isPrimitive()) {
      return false;
    }
    final String className = cls.getName();
    return !className.startsWith("com.macstab.chaos.jvm.")
        && !className.startsWith(BYTE_BUDDY_PACKAGE_PREFIX)
        && !className.startsWith("jdk.internal.");
  }
}

package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Child-JVM probe for {@link FinalizerBacklogStopTest}: activates a finalizer backlog of one object
 * with an hour-long delay, waits until the JVM's Finalizer thread sleeps in that object's {@code
 * finalize()}, stops the scenario, then drops an object of its own with a finalizer. Prints {@code
 * sleeping=<bool>}, {@code finalizedAfterStop=<bool>} (within 10 s) and {@code millis=<n>}, the
 * time from the stop to that finalizer.
 */
final class FinalizerStopProbeMain {

  private FinalizerStopProbeMain() {}

  public static void main(final String[] args) throws InterruptedException {
    final ChaosRuntime runtime = new ChaosRuntime();
    try {
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("finalizer-backlog")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.stress(ChaosSelector.StressTarget.FINALIZER_BACKLOG))
                  .effect(new ChaosEffect.FinalizerBacklogEffect(1, Duration.ofHours(1)))
                  .build());
      final long sleepingBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!finalizerSleepsInStressor() && sleepingBy - System.nanoTime() > 0) {
        System.gc();
        Thread.sleep(20);
      }
      System.out.println("sleeping=" + finalizerSleepsInStressor());

      final long stopped = System.nanoTime();
      handle.stop();
      final CountDownLatch finalized = new CountDownLatch(1);
      dropFinalizable(finalized);
      final long finalizedBy = stopped + TimeUnit.SECONDS.toNanos(10);
      while (!finalized.await(20, TimeUnit.MILLISECONDS) && finalizedBy - System.nanoTime() > 0) {
        System.gc();
      }
      System.out.println("finalizedAfterStop=" + (finalized.getCount() == 0L));
      System.out.println("millis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopped));
    } finally {
      runtime.close();
    }
  }

  private static void dropFinalizable(final CountDownLatch finalized) {
    new Finalizable(finalized);
  }

  /** Whether the JVM's Finalizer thread is inside the stressor's {@code finalize()}. */
  private static boolean finalizerSleepsInStressor() {
    for (final StackTraceElement[] frames : Thread.getAllStackTraces().values()) {
      for (final StackTraceElement frame : frames) {
        if (frame.getClassName().endsWith("FinalizerBacklogStressor$FinalizableObject")
            && frame.getMethodName().equals("finalize")) {
          return true;
        }
      }
    }
    return false;
  }

  /** An application object whose finalizer reports that it ran. */
  @SuppressWarnings({"deprecation", "removal"})
  private static final class Finalizable {
    private final CountDownLatch finalized;

    Finalizable(final CountDownLatch finalized) {
      this.finalized = finalized;
    }

    // The application finalizer that must not wait behind a stopped stressor.
    @SuppressWarnings({"Finalize", "removal"})
    @Override
    protected void finalize() {
      finalized.countDown();
    }
  }
}

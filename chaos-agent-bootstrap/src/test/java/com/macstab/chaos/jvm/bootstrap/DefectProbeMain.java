package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Probe main for {@link DefectRegressionIntegrationTest}. Each mode exercises one instrumented call
 * path inside a JVM started with {@code -javaagent:} and prints {@code key=value} lines that the
 * test asserts on. Every mode ends with {@link #printCounts()}, which prints each scenario's
 * matched and applied counters as {@code matched.<id>} / {@code applied.<id>}.
 */
public final class DefectProbeMain {

  private DefectProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "schedule-sub-millisecond-delay" -> scheduleSubMillisecondDelay();
      case "schedule-sub-millisecond-period" -> scheduleSubMillisecondPeriod();
      case "schedule-submit-delay" -> scheduleSubmitDelay();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    printCounts();
  }

  /** Prints the remaining delay of a 1900 µs schedule right after submission. */
  private static void scheduleSubMillisecondDelay() throws Exception {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    try {
      // Warm up the schedule path (class loading, dispatch) so the measured call is not dominated
      // by first-call overhead.
      executor.schedule(() -> {}, 0L, TimeUnit.MILLISECONDS).get(5L, TimeUnit.SECONDS);
      final ScheduledFuture<?> future = executor.schedule(() -> {}, 1900L, TimeUnit.MICROSECONDS);
      System.out.println("remainingNanos=" + future.getDelay(TimeUnit.NANOSECONDS));
      future.get(5L, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  /** Counts the ticks of a 300 µs fixed-rate schedule over 300 ms. */
  private static void scheduleSubMillisecondPeriod() throws Exception {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final AtomicLong ticks = new AtomicLong();
    try {
      executor.scheduleAtFixedRate(ticks::incrementAndGet, 0L, 300L, TimeUnit.MICROSECONDS);
      Thread.sleep(300L);
      System.out.println("ticks=" + ticks.get());
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Schedules one task with no delay under a SCHEDULE_SUBMIT delay scenario and prints how long the
   * submitting thread was blocked and how long the task took to start.
   */
  private static void scheduleSubmitDelay() throws Exception {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final CountDownLatch ran = new CountDownLatch(1);
    final AtomicLong startedAtNanos = new AtomicLong();
    try {
      final long submitStart = System.nanoTime();
      executor.schedule(
          () -> {
            startedAtNanos.set(System.nanoTime());
            ran.countDown();
          },
          0L,
          TimeUnit.MILLISECONDS);
      final long submitReturned = System.nanoTime();
      if (!ran.await(10L, TimeUnit.SECONDS)) {
        throw new IllegalStateException("scheduled task never ran");
      }
      System.out.println(
          "submitBlockedMillis=" + TimeUnit.NANOSECONDS.toMillis(submitReturned - submitStart));
      System.out.println(
          "taskStartMillis=" + TimeUnit.NANOSECONDS.toMillis(startedAtNanos.get() - submitStart));
    } finally {
      executor.shutdownNow();
    }
  }

  private static void printCounts() {
    final ChaosControlPlane controlPlane = ChaosPlatform.current();
    for (final ChaosDiagnostics.ScenarioReport report :
        controlPlane.diagnostics().snapshot().scenarios()) {
      System.out.println("matched." + report.id() + "=" + report.matchedCount());
      System.out.println("applied." + report.id() + "=" + report.appliedCount());
    }
  }
}

package com.macstab.chaos.jvm.core;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The agent's one expiry thread: it ends time-bounded scenarios when their bounds pass, also
 * without any call (semantics L19). One daemon thread for the whole JVM, however many scenarios are
 * time-bounded; each schedules one check and cancels it when it stops.
 *
 * <p>Agent-owned (L3): the thread's body runs with the dispatch depth raised ({@link
 * AgentThreads#platform}), and every call that may start the thread or enqueue a check raises it
 * too, so no scenario applies to the thread's start, its queue or its parking.
 */
final class ExpiryScheduler {

  private static final ScheduledThreadPoolExecutor EXECUTOR = create();

  private ExpiryScheduler() {}

  private static ScheduledThreadPoolExecutor create() {
    final ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            1, task -> AgentThreads.platform("macstab-chaos-expiry", true, task));
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  /**
   * Starts the expiry thread unless it runs already, so a scenario that needs it fails its start
   * instead of being left without an end.
   *
   * @throws OutOfMemoryError when the JVM cannot start the thread
   */
  static void ensureStarted() {
    ChaosDispatcher.incrementDispatchDepth();
    try {
      EXECUTOR.prestartCoreThread();
    } finally {
      ChaosDispatcher.decrementDispatchDepth();
    }
  }

  /**
   * Runs {@code check} on the expiry thread after {@code delayNanos}.
   *
   * @param check the expiry check
   * @param delayNanos the delay; {@code 0} runs it as soon as the thread is free
   * @return the scheduled check, to cancel when the scenario ends otherwise
   */
  static ScheduledFuture<?> schedule(final Runnable check, final long delayNanos) {
    ChaosDispatcher.incrementDispatchDepth();
    try {
      return EXECUTOR.schedule(check, delayNanos, TimeUnit.NANOSECONDS);
    } finally {
      ChaosDispatcher.decrementDispatchDepth();
    }
  }
}

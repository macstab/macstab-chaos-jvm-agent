package com.macstab.chaos.jvm.core;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The agent's one expiry thread: it ends time-bounded scenarios when their bounds pass, also
 * without any call (semantics L19). One daemon thread for the whole JVM, however many scenarios are
 * time-bounded; each schedules one check and cancels it when it stops. It also ends a scenario
 * whose stressor stopped producing load on its own (L17): the stressor's thread hands the end over
 * instead of ending the scenario itself, because a concurrent stop joins that thread while it holds
 * the scenario's monitor.
 *
 * <p>Agent-owned (L3): the thread's body runs with the dispatch depth raised ({@link
 * AgentThreads#platform}), and every call that may start the thread or enqueue a check raises it
 * too, so no scenario applies to the thread's start, its queue or its parking. A thread started by
 * a runtime used before the agent's install raised no depth (there was no dispatcher yet), and it
 * serves the JVM for good: its next task raises the depth for the rest of its life, and the install
 * hands it one ({@link #adoptAfterInstall()}).
 */
final class ExpiryScheduler {

  private static final ScheduledThreadPoolExecutor EXECUTOR = create();

  /** The expiry thread whose depth a task raised for the thread's life ({@link #owned}). */
  private static volatile @Nullable Thread raisedFor;

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
    DispatchDepth.increment();
    try {
      EXECUTOR.prestartCoreThread();
    } finally {
      DispatchDepth.decrement();
    }
  }

  /**
   * Runs {@code end} on the expiry thread as soon as it is free; never cancelled.
   *
   * @param end the end of a scenario
   */
  static void execute(final Runnable end) {
    DispatchDepth.increment();
    try {
      EXECUTOR.execute(owned(end));
    } finally {
      DispatchDepth.decrement();
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
    DispatchDepth.increment();
    try {
      return EXECUTOR.schedule(owned(check), delayNanos, TimeUnit.NANOSECONDS);
    } finally {
      DispatchDepth.decrement();
    }
  }

  /**
   * Hands a running expiry thread a task once the agent is installed, so a thread started before
   * the install raises its depth now rather than at its next check; starts no thread.
   */
  static void adoptAfterInstall() {
    DispatchDepth.increment();
    try {
      if (EXECUTOR.getPoolSize() > 0) {
        EXECUTOR.execute(owned(() -> {}));
      }
    } finally {
      DispatchDepth.decrement();
    }
  }

  /**
   * Returns {@code task} preceded, on a thread whose depth no task raised yet, by a raise kept for
   * the thread's life: once the dispatcher is installed, the thread's parking between tasks is the
   * agent's own too (L3). A thread whose body did raise it ends up one deeper, which changes
   * nothing.
   */
  private static Runnable owned(final Runnable task) {
    return () -> {
      final Thread self = Thread.currentThread();
      if (raisedFor != self && DispatchDepth.installed()) {
        DispatchDepth.increment();
        raisedFor = self;
      }
      task.run();
    };
  }
}

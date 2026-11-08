package com.macstab.chaos.jvm.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Starts and stops the agent's own threads (stressor and expiry threads), so no scenario is ever
 * evaluated for them (L3): each body runs, and each thread is started and stopped, with the
 * dispatch depth raised. A thread started with the depth raised still begins its own depth at zero,
 * so the body has to raise it too.
 */
final class AgentThreads {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  /** How long a rollback waits for each thread it stops. */
  private static final long ROLLBACK_JOIN_MILLIS = 1_000L;

  private AgentThreads() {}

  /**
   * Returns {@code body} wrapped so it runs with the dispatch depth raised.
   *
   * @param body the thread's work
   * @return the wrapped body
   */
  static Runnable owned(final Runnable body) {
    return () -> {
      ChaosDispatcher.incrementDispatchDepth();
      try {
        body.run();
      } finally {
        ChaosDispatcher.decrementDispatchDepth();
      }
    };
  }

  /**
   * Returns an unstarted platform thread whose body runs with the dispatch depth raised; start it
   * with {@link #startAll(List)}.
   *
   * @param name the thread name
   * @param daemon whether the thread is a daemon
   * @param body the thread's work
   * @return the unstarted thread
   */
  static Thread platform(final String name, final boolean daemon, final Runnable body) {
    final Thread thread = new Thread(owned(body), name);
    thread.setDaemon(daemon);
    return thread;
  }

  /**
   * Creates and starts one agent-owned platform thread.
   *
   * @param name the thread name
   * @param daemon whether the thread is a daemon
   * @param body the thread's work
   * @return the started thread
   */
  static Thread start(final String name, final boolean daemon, final Runnable body) {
    return startAll(List.of(platform(name, daemon, body))).get(0);
  }

  /**
   * Starts every thread with the dispatch depth raised. When a start fails, the threads already
   * started are stopped before the failure is rethrown, so a partial start leaves no thread behind.
   *
   * @param threads unstarted threads whose bodies are {@link #owned(Runnable) owned}
   * @return the started threads
   */
  static List<Thread> startAll(final List<Thread> threads) {
    final List<Thread> started = new ArrayList<>(threads.size());
    ChaosDispatcher.incrementDispatchDepth();
    try {
      for (final Thread thread : threads) {
        thread.start();
        started.add(thread);
      }
    } catch (final RuntimeException | Error failure) {
      try {
        stopAll(started, ROLLBACK_JOIN_MILLIS);
      } catch (final RuntimeException | Error rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    } finally {
      ChaosDispatcher.decrementDispatchDepth();
    }
    return List.copyOf(started);
  }

  /**
   * Interrupts every thread, then waits up to {@code joinMillis} for each to end. An interrupt of
   * the calling thread does not cut the wait short: it is kept and restored once every thread has
   * been waited for. The calling thread is never joined (a stressor thread that stops its own
   * stressor). Threads still alive afterwards are logged once and returned.
   *
   * @param threads the threads to stop
   * @param joinMillis the longest wait for each thread
   * @return the threads still alive
   */
  static List<Thread> stopAll(final Collection<Thread> threads, final long joinMillis) {
    final Thread self = Thread.currentThread();
    final List<Thread> survivors = new ArrayList<>();
    boolean interrupted = false;
    ChaosDispatcher.incrementDispatchDepth();
    try {
      for (final Thread thread : threads) {
        thread.interrupt();
      }
      for (final Thread thread : threads) {
        if (thread == self) {
          continue;
        }
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(joinMillis);
        long remaining;
        while (thread.isAlive() && (remaining = deadline - System.nanoTime()) > 0L) {
          try {
            thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
          } catch (final InterruptedException interruptedWhileJoining) {
            interrupted = true;
          }
        }
        if (thread.isAlive()) {
          survivors.add(thread);
        }
      }
    } finally {
      ChaosDispatcher.decrementDispatchDepth();
      if (interrupted) {
        self.interrupt();
      }
    }
    if (!survivors.isEmpty()) {
      LOGGER.warning(
          () ->
              "chaos-agent: "
                  + survivors.size()
                  + " stressor thread(s) still running after stop: "
                  + survivors.stream().map(Thread::getName).collect(Collectors.joining(", ")));
    }
    return List.copyOf(survivors);
  }
}

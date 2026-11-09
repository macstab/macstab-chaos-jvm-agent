package com.macstab.chaos.jvm.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Starts and stops the agent's own threads (stressor and expiry threads), so no scenario is ever
 * evaluated for them (L3): each body runs, and each thread is started and stopped, with the
 * dispatch depth raised. A thread started with the depth raised still begins its own depth at zero,
 * so the body has to raise it too. A stressor thread's body reports its failure (a {@link
 * Throwable} escaping it) to an end callback, so its scenario ends instead of staying {@code
 * ACTIVE} without load (L17).
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
   * Returns {@code body} wrapped so it runs with the dispatch depth raised and reports its own
   * failure: a {@link Throwable} that escapes the body is logged once and {@code onFailure} runs on
   * the failing thread (a stressor ends its scenario through it). A failure of {@code onFailure}
   * itself is logged once and swallowed.
   *
   * @param body the thread's work
   * @param onFailure runs when the body fails
   * @return the wrapped body
   */
  static Runnable owned(final Runnable body, final Runnable onFailure) {
    return owned(
        () -> {
          try {
            body.run();
          } catch (final Throwable failure) {
            LOGGER.log(
                Level.WARNING,
                failure,
                () -> "chaos-agent: agent thread " + Thread.currentThread().getName() + " failed");
            try {
              onFailure.run();
            } catch (final Throwable endFailure) {
              LOGGER.log(
                  Level.WARNING,
                  endFailure,
                  () ->
                      "chaos-agent: agent thread "
                          + Thread.currentThread().getName()
                          + " could not report its failure");
            }
          }
        });
  }

  /**
   * Returns an unstarted platform thread whose body runs with the dispatch depth raised; start it
   * with {@link #startAll(List)}. For the expiry thread, whose executor handles its tasks'
   * failures.
   *
   * @param name the thread name
   * @param daemon whether the thread is a daemon
   * @param body the thread's work
   * @return the unstarted thread
   */
  static Thread platform(final String name, final boolean daemon, final Runnable body) {
    return named(new Thread(owned(body)), name, daemon);
  }

  /**
   * Returns an unstarted platform thread whose body runs {@link #owned(Runnable, Runnable) owned},
   * reporting its failure to {@code onFailure}; start it with {@link #startAll(List)}.
   *
   * @param name the thread name
   * @param daemon whether the thread is a daemon
   * @param body the thread's work
   * @param onFailure runs when the body fails
   * @return the unstarted thread
   */
  static Thread platform(
      final String name, final boolean daemon, final Runnable body, final Runnable onFailure) {
    return platform(Thread::new, name, daemon, body, onFailure);
  }

  /**
   * As {@link #platform(String, boolean, Runnable, Runnable)}, with the thread made by {@code
   * threads} (a stressor thread factory, test seam U-72 c3).
   *
   * @param threads makes the thread for the owned body
   * @param name the thread name
   * @param daemon whether the thread is a daemon
   * @param body the thread's work
   * @param onFailure runs when the body fails
   * @return the unstarted thread
   */
  static Thread platform(
      final ThreadFactory threads,
      final String name,
      final boolean daemon,
      final Runnable body,
      final Runnable onFailure) {
    return named(threads.newThread(owned(body, onFailure)), name, daemon);
  }

  private static Thread named(final Thread thread, final String name, final boolean daemon) {
    thread.setName(name);
    thread.setDaemon(daemon);
    return thread;
  }

  /**
   * Creates and starts one agent-owned platform thread whose body reports its failure to {@code
   * onFailure}.
   *
   * @param name the thread name
   * @param daemon whether the thread is a daemon
   * @param body the thread's work
   * @param onFailure runs when the body fails
   * @return the started thread
   */
  static Thread start(
      final String name, final boolean daemon, final Runnable body, final Runnable onFailure) {
    return startAll(List.of(platform(name, daemon, body, onFailure))).get(0);
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

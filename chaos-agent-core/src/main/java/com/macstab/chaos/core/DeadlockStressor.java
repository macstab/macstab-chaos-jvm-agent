package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Permanently deadlocks {@code participantCount} threads using a circular lock-acquisition order.
 *
 * <p>N locks are created. Thread {@code i} acquires lock {@code i} first, waits for {@link
 * ChaosEffect.DeadlockEffect#acquisitionDelay()}, then attempts to acquire lock {@code (i+1) % N}.
 * Because every thread holds exactly one lock and waits for the lock held by its successor, a
 * circular wait forms — the classical Coffman deadlock condition.
 *
 * <p>A {@link CountDownLatch} ensures all participants acquire their first lock before any attempts
 * the second, guaranteeing the deadlock is fully formed rather than probabilistic.
 *
 * <p>{@link #close()} interrupts all participating threads. Because the threads are blocked in
 * {@link ReentrantLock#lockInterruptibly()}, the interrupt is propagated immediately and the
 * threads exit cleanly, releasing all locks.
 */
final class DeadlockStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private final List<Thread> participants;

  DeadlockStressor(final ChaosEffect.DeadlockEffect effect) {
    final int n = effect.participantCount();
    final long acquisitionDelayMs = effect.acquisitionDelay().toMillis();

    // Create N independent locks — one per participant.
    final ReentrantLock[] locks = new ReentrantLock[n];
    for (int i = 0; i < n; i++) {
      locks[i] = new ReentrantLock();
    }

    // Latch: all threads acquire their first lock before any tries the second.
    final CountDownLatch firstLockAcquired = new CountDownLatch(n);
    final List<Thread> threads = new ArrayList<>(n);

    for (int i = 0; i < n; i++) {
      final int index = i;
      final String name = "chaos-deadlock-" + i;
      final Thread thread =
          new Thread(
              () -> {
                    final ReentrantLock first = locks[index];
                    final ReentrantLock second = locks[(index + 1) % n];
                    try {
                      first.lockInterruptibly();
                      try {
                        firstLockAcquired.countDown();
                        // Wait until all participants hold their first lock, then attempt second.
                        firstLockAcquired.await();
                        if (acquisitionDelayMs > 0) {
                          Thread.sleep(acquisitionDelayMs);
                        }
                        second.lockInterruptibly();
                        try {
                          // Deadlock should prevent reaching here. If for some reason we do
                          // (e.g., during teardown), park until interrupted.
                          while (!Thread.currentThread().isInterrupted()) {
                            java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
                            if (Thread.interrupted()) {
                              return;
                            }
                          }
                        } finally {
                          second.unlock();
                        }
                      } finally {
                        first.unlock();
                      }
                    } catch (final InterruptedException e) {
                      Thread.currentThread().interrupt();
                      LOGGER.fine(() -> "chaos deadlock thread interrupted: " + name);
                    }
                  },
              name);
      thread.setDaemon(true);
      thread.start();
      threads.add(thread);
    }
    this.participants = List.copyOf(threads);
  }

  /**
   * Per-thread join deadline on {@link #close()}. Deadlocked threads are blocked in {@link
   * ReentrantLock#lockInterruptibly()}; the interrupt propagates immediately, so in practice the
   * join returns well below this ceiling. 200 ms is a soft upper bound that keeps close() bounded
   * even if a participant has been scheduled out by the OS at the moment we interrupt.
   */
  private static final long JOIN_TIMEOUT_MILLIS = 200L;

  @Override
  public void close() {
    // Interrupt-then-join honours ManagedStressor's "wait for termination" contract: without the
    // join, close() returns while the lockInterruptibly() frames are still unwinding, and a
    // deactivate-then-reactivate cycle can start a second generation of deadlock threads while
    // the first generation still holds locks. That surfaces as overlapping thread names and a
    // stale aliveCount() — issues that vanished once close() waits for the previous generation.
    for (final Thread thread : participants) {
      thread.interrupt();
    }
    for (final Thread thread : participants) {
      try {
        thread.join(JOIN_TIMEOUT_MILLIS);
      } catch (final InterruptedException interrupted) {
        // Restore the interrupt flag so callers see the signal and stop waiting on the remaining
        // participants. Chaos teardown yielding to shutdown is the correct behaviour.
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Returns the number of participant threads that are still alive. */
  long aliveCount() {
    return participants.stream().filter(Thread::isAlive).count();
  }
}

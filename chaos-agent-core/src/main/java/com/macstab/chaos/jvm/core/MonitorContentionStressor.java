package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Creates high contention on a shared monitor by spawning threads that compete for a single {@link
 * ReentrantLock}.
 *
 * <p>Each contending thread repeatedly acquires the lock, holds it for {@link
 * ChaosEffect.MonitorContentionEffect#lockHoldDuration()}, then releases it — immediately retrying
 * the acquisition. This generates a lock convoy: all waiting threads pile up in the lock queue and
 * the scheduler is under pressure to fairly distribute ownership.
 *
 * <p>When {@link ChaosEffect.MonitorContentionEffect#unfair()} is {@code true} the lock is
 * constructed without FIFO ordering, increasing the probability of thread starvation (some threads
 * may never acquire the lock while others acquire it repeatedly).
 *
 * <p>All contending threads start together via a {@link CountDownLatch} so that contention begins
 * simultaneously rather than being biased by thread startup order.
 *
 * <p>{@link #close()} sets a stop flag and interrupts all threads.
 */
final class MonitorContentionStressor implements ManagedStressor {

  private static final AgentLog LOGGER = AgentLog.named("com.macstab.chaos.jvm");

  private final List<Thread> contentionThreads;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  MonitorContentionStressor(final ChaosEffect.MonitorContentionEffect effect) {
    this(effect, NO_END);
  }

  /**
   * Creates and starts the contending threads, which end the scenario through {@code onEnd} if one
   * fails.
   *
   * @param effect the effect parameters
   * @param onEnd the scenario's end callback, told when a stressor thread fails
   */
  MonitorContentionStressor(
      final ChaosEffect.MonitorContentionEffect effect, final Consumer<String> onEnd) {
    final ReentrantLock lock = new ReentrantLock(!effect.unfair()); // fair=true unless unfair
    final long holdNanos = ManagedStressor.nanos(effect.lockHoldDuration());
    final int count = effect.contendingThreadCount();
    final CountDownLatch ready = new CountDownLatch(count);
    final List<Thread> threads = new ArrayList<>(count);

    for (int i = 0; i < count; i++) {
      final String name = "chaos-contention-" + i;
      threads.add(
          AgentThreads.platform(
              name,
              true,
              () -> {
                ready.countDown();
                try {
                  ready.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                while (!stopped.get()) {
                  // lockInterruptibly, not lock(): a thread blocked waiting for the lock
                  // must unblock on close()'s interrupt(), otherwise close() returns while
                  // the thread can still be waiting unboundedly for the lock.
                  try {
                    lock.lockInterruptibly();
                  } catch (final InterruptedException e) {
                    stopped.set(true);
                    Thread.currentThread().interrupt();
                    return;
                  }
                  try {
                    final long holdStart = System.nanoTime();
                    while (System.nanoTime() - holdStart < holdNanos && !stopped.get()) {
                      // Thread.sleep(0, nanos) holds the lock (unlike wait()) and is not
                      // instrumented by the chaos agent (LockSupport.park* is), preventing
                      // a chaos delay scenario from recursively targeting this stressor.
                      try {
                        Thread.sleep(0, 10_000 /* 10 µs */);
                      } catch (final InterruptedException ie) {
                        stopped.set(true);
                        Thread.currentThread().interrupt();
                        return;
                      }
                    }
                  } finally {
                    lock.unlock();
                  }
                }
                LOGGER.fine(() -> "chaos monitor-contention thread terminated: " + name);
              },
              ManagedStressor.failure(onEnd, "MonitorContention")));
    }
    this.contentionThreads = AgentThreads.startAll(threads);
  }

  /**
   * Per-thread join deadline on {@link #close()}. Contention threads park in small (10 µs) slices
   * and check the stop flag each iteration, so the expected wake-up time on interrupt is well under
   * a millisecond; 200 ms is a safety ceiling for OS scheduling jitter.
   */
  private static final long JOIN_TIMEOUT_MILLIS = 200L;

  @Override
  public void close() {
    stopped.set(true);
    // Join after interrupt — without this, close() returns while contending threads are still
    // draining their 10 µs park loops, and test suites asserting "no contention threads alive"
    // immediately after deactivate observe flaky counts. Bounded join honours ManagedStressor's
    // "wait for termination" contract.
    AgentThreads.stopAll(contentionThreads, JOIN_TIMEOUT_MILLIS);
  }

  /** Returns the number of contending threads that are still alive. */
  long aliveCount() {
    return contentionThreads.stream().filter(Thread::isAlive).count();
  }
}

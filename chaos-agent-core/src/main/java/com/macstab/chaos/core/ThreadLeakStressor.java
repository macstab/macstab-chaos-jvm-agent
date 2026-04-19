package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Spawns threads that park indefinitely, simulating a thread leak.
 *
 * <p>Each leaked thread runs a {@link java.util.concurrent.locks.LockSupport#park()} loop,
 * consuming a thread slot and associated OS resources without performing any useful work. This
 * models threads blocked on locks, I/O, or condition variables that are never signalled.
 *
 * <p>When {@link ChaosEffect.ThreadLeakEffect#daemon()} is {@code false} and no lifespan is set,
 * the leaked threads prevent a clean JVM exit until {@link #close()} is called.
 *
 * <p>When a lifespan is configured each thread terminates after that duration regardless of whether
 * {@link #close()} has been called.
 *
 * <p>{@link #close()} interrupts all leaked threads and waits briefly for them to terminate.
 */
final class ThreadLeakStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private final List<Thread> leakedThreads;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  ThreadLeakStressor(final ChaosEffect.ThreadLeakEffect effect) {
    final List<Thread> threads = new ArrayList<>(effect.threadCount());
    // Compute deadline without overflow: when lifespan is null, use Long.MAX_VALUE directly
    // rather than currentTimeMillis + Long.MAX_VALUE which would overflow to a negative number.
    final long deadlineMillis =
        effect.lifespan() != null
            ? System.currentTimeMillis() + effect.lifespan().toMillis()
            : Long.MAX_VALUE;

    for (int i = 0; i < effect.threadCount(); i++) {
      final String name = effect.namePrefix() + i;
      final Thread thread =
          Thread.ofPlatform()
              .daemon(effect.daemon())
              .name(name)
              .start(
                  () -> {
                    final long deadline = deadlineMillis;
                    while (!stopped.get() && System.currentTimeMillis() < deadline) {
                      java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L /* 50 ms */);
                      if (Thread.interrupted()) {
                        break;
                      }
                    }
                    LOGGER.fine(() -> "chaos thread-leak thread terminated: " + name);
                  });
      threads.add(thread);
    }
    this.leakedThreads = List.copyOf(threads);
  }

  @Override
  public void close() {
    // ManagedStressor.close() javadoc mandates waiting for termination before returning. The
    // previous implementation set the stop flag and returned — callers that immediately checked
    // aliveCount() or observed thread liveness raced against interrupt-observation latency (up
    // to the 50 ms parkNanos window below) and saw stale state. Interrupt every thread first so
    // each one begins winding down in parallel, then join each with a bounded per-thread
    // timeout. 200 ms × threadCount is still bounded overall in practice because by the time
    // the second join runs the threads are all already observing the interrupt/stop flag.
    stopped.set(true);
    for (final Thread thread : leakedThreads) {
      thread.interrupt();
    }
    for (final Thread thread : leakedThreads) {
      try {
        thread.join(200L);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  /** Returns the number of leaked threads that are still alive. */
  long aliveCount() {
    return leakedThreads.stream().filter(Thread::isAlive).count();
  }
}

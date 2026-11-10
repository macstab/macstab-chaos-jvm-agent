package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
 * {@link #close()} has been called, and the scenario ends ({@code INACTIVE}, reason {@code
 * "lifespan elapsed"}).
 *
 * <p>{@link #close()} interrupts all leaked threads and waits briefly for them to terminate.
 */
final class ThreadLeakStressor implements ManagedStressor {

  private static final AgentLog LOGGER = AgentLog.named("com.macstab.chaos.jvm");

  private final List<Thread> leakedThreads;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  ThreadLeakStressor(final ChaosEffect.ThreadLeakEffect effect) {
    this(effect, NO_END);
  }

  /**
   * Creates the stressor; it ends the scenario through {@code onEnd} when its lifespan elapses or a
   * leaked thread fails.
   *
   * @param effect the effect parameters
   * @param onEnd the scenario's end callback
   */
  ThreadLeakStressor(final ChaosEffect.ThreadLeakEffect effect, final Consumer<String> onEnd) {
    this(effect, onEnd, AgentThreads.PLATFORM);
  }

  /**
   * Creates the stressor with its threads made by {@code threadFactory} (test seam, U-72 c3).
   *
   * @param effect the effect parameters
   * @param onEnd the scenario's end callback
   * @param threadFactory makes each leaked thread
   */
  ThreadLeakStressor(
      final ChaosEffect.ThreadLeakEffect effect,
      final Consumer<String> onEnd,
      final java.util.concurrent.ThreadFactory threadFactory) {
    final List<Thread> threads = new ArrayList<>(effect.threadCount());
    // Elapsed time against a saturated lifespan: a deadline sum overflows for a huge lifespan.
    final java.time.Duration lifespan = effect.lifespan();
    final long lifespanNanos = lifespan != null ? ManagedStressor.nanos(lifespan) : Long.MAX_VALUE;
    final long start = System.nanoTime();

    for (int i = 0; i < effect.threadCount(); i++) {
      final String name = effect.namePrefix() + i;
      threads.add(
          AgentThreads.platform(
              threadFactory,
              name,
              effect.daemon(),
              () -> {
                while (!stopped.get() && System.nanoTime() - start < lifespanNanos) {
                  java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L /* 50 ms */);
                  if (Thread.interrupted()) {
                    ManagedStressor.interrupted(onEnd, "ThreadLeak", stopped.get());
                    return;
                  }
                }
                LOGGER.fine(() -> "[chaos-agent] chaos thread-leak thread terminated: " + name);
                if (!stopped.get()) {
                  // The leak ends with the lifespan, and so does the scenario (L17); the first
                  // thread to end reports it.
                  onEnd.accept("lifespan elapsed");
                }
              },
              ManagedStressor.failure(onEnd, "ThreadLeak")));
    }
    this.leakedThreads = AgentThreads.startAll(threads);
  }

  @Override
  public void close() {
    // ManagedStressor.close() javadoc mandates waiting for termination before returning: every
    // thread is interrupted first so they wind down in parallel, then each is joined with a
    // bounded per-thread timeout, also when the caller is interrupted.
    stopped.set(true);
    AgentThreads.stopAll(leakedThreads, 200L);
  }

  /** Returns the number of leaked threads that are still alive. */
  long aliveCount() {
    return leakedThreads.stream().filter(Thread::isAlive).count();
  }
}

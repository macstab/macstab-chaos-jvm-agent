package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.concurrent.TimeUnit;

/**
 * Creates objects with slow finalizers to back up the JVM finalizer queue.
 *
 * <p>Objects are created in the constructor and immediately become GC-eligible (no strong
 * references are retained). The GC will place them in the pending-finalizer queue; the finalizer
 * thread then processes them sequentially, sleeping for {@link
 * ChaosEffect.FinalizerBacklogEffect#finalizerDelay()} per object. A high {@link
 * ChaosEffect.FinalizerBacklogEffect#objectCount()} combined with a non-zero delay causes the
 * finalizer thread to fall progressively further behind, delaying native-memory or file-descriptor
 * reclamation and, in extreme cases, leading to OOM.
 *
 * <p>Note: {@code Object.finalize()} is deprecated for removal. This class suppresses the
 * deprecation warning intentionally — the purpose of this stressor is specifically to exercise the
 * finalizer pathway.
 *
 * <p>{@link #close()} ends the stressor's finalizers: one sleeping returns at once, and the ones
 * still queued return without sleeping, so the JVM's single Finalizer thread runs other objects'
 * finalizers again however long the delay. A finalizer sleeps in a timed wait on the stressor's
 * {@link Stop} monitor, which {@code close()} notifies; the Finalizer thread's interrupt flag is
 * never touched.
 */
final class FinalizerBacklogStressor implements ManagedStressor {

  private static final AgentLog LOGGER = AgentLog.named("com.macstab.chaos.jvm");

  private final int createdCount;
  private final long finalizerDelayNanos;
  private final Stop stop = new Stop();

  FinalizerBacklogStressor(final ChaosEffect.FinalizerBacklogEffect effect) {
    // In nanoseconds, saturated: a delay below a millisecond is kept, and one too large for
    // nanoseconds lasts until close() (L17).
    final long delayNanos = ManagedStressor.nanos(effect.finalizerDelay());

    // Create all objects without retaining references. They become GC-eligible immediately.
    for (int i = 0; i < effect.objectCount(); i++) {
      new FinalizableObject(delayNanos, stop);
    }
    this.createdCount = effect.objectCount();
    this.finalizerDelayNanos = delayNanos;
    LOGGER.fine(
        () ->
            "[chaos-agent] chaos finalizer-backlog stressor created "
                + createdCount
                + " objects with "
                + effect.finalizerDelay()
                + " finalizer delay");
  }

  @Override
  public void close() {
    // Wakes the finalizer sleeping now; the pending ones return without sleeping.
    stop.stop();
  }

  /**
   * The stressor's stop signal, and the monitor its finalizers wait on: {@link #stop()} wakes a
   * finalizer waiting in {@link #await(long)}.
   */
  static final class Stop {
    private boolean stopped;

    /** Ends every wait, now and later. */
    synchronized void stop() {
      stopped = true;
      notifyAll();
    }

    /**
     * Waits until {@code nanos} have elapsed or {@link #stop()} is called, whichever comes first.
     * Counts the elapsed time, so a delay saturated at {@link Long#MAX_VALUE} does not overflow.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    synchronized void await(final long nanos) throws InterruptedException {
      final long start = System.nanoTime();
      long left = nanos;
      while (!stopped && left > 0L) {
        TimeUnit.NANOSECONDS.timedWait(this, left);
        left = nanos - (System.nanoTime() - start);
      }
    }

    synchronized boolean stopped() {
      return stopped;
    }
  }

  /**
   * Returns the time each finalizer sleeps, in nanoseconds; package-private as a test seam.
   *
   * @return the finalizer delay
   */
  long finalizerDelayNanos() {
    return finalizerDelayNanos;
  }

  /** Returns the number of finalizable objects created by this stressor instance. */
  int createdCount() {
    return createdCount;
  }

  /**
   * An object with a slow finalizer. Each instance sleeps for {@code delayNanos} nanoseconds inside
   * its {@code finalize()} method, backing up the JVM's single-threaded finalizer thread.
   */
  @SuppressWarnings({"deprecation", "removal"})
  private static final class FinalizableObject {

    private final long delayNanos;
    private final Stop stop;

    FinalizableObject(final long delayNanos, final Stop stop) {
      this.delayNanos = delayNanos;
      this.stop = stop;
    }

    // A finalizer is the point: the stressor fills the finalizer queue.
    @SuppressWarnings({"Finalize", "removal"})
    @Override
    protected void finalize() {
      if (stop.stopped()) {
        return;
      }
      if (delayNanos > 0) {
        // Agent work on the JVM's Finalizer thread (L3): a user THREAD_SLEEP scenario never
        // applies to this sleep.
        DispatchDepth.increment();
        try {
          // A timed wait close() ends, not a sleep: a delay as long as the scenario, or longer,
          // must not hold the JVM's Finalizer thread once the scenario has stopped.
          stop.await(delayNanos);
        } catch (final InterruptedException ignored) {
          // Do NOT restore the interrupt flag: finalize() runs on the shared JVM finalizer
          // thread, and setting its interrupt bit leaks our chaos signal into every subsequent
          // finalizer on the queue. A legitimate finalize() with its own Thread.sleep or
          // IO call would observe an unexpected interrupt that did not originate from its
          // own bookkeeping — producing sporadic failures in unrelated user-domain objects
          // (JDBC, NIO channels, resource closers) whose root cause would be impossible to
          // trace back to chaos-agent. Swallow silently and let the finalizer complete; the
          // stressor's backlog effect is only observable through delay accumulation, not
          // through individual-object completion. close() never interrupts: an interrupt here
          // came from elsewhere (JVM shutdown) and the thread is already being torn down.
        } finally {
          DispatchDepth.decrement();
        }
      }
    }
  }
}

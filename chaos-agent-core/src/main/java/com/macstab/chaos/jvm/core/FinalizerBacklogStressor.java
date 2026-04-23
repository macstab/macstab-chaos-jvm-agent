package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

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
 * <p>{@link #close()} is a no-op: the objects are already GC-eligible at construction time.
 */
final class FinalizerBacklogStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  private final int createdCount;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  @SuppressWarnings({"deprecation", "removal"})
  FinalizerBacklogStressor(final ChaosEffect.FinalizerBacklogEffect effect) {
    final long delayMs = effect.finalizerDelay().toMillis();

    // Create all objects without retaining references. They become GC-eligible immediately.
    for (int i = 0; i < effect.objectCount(); i++) {
      new FinalizableObject(delayMs, stopped);
    }
    this.createdCount = effect.objectCount();
    LOGGER.fine(
        () ->
            "chaos finalizer-backlog stressor created "
                + createdCount
                + " objects with "
                + delayMs
                + " ms finalizer delay");
  }

  @Override
  public void close() {
    // Signal all pending FinalizableObject.finalize() calls to return early without sleeping.
    stopped.set(true);
  }

  /** Returns the number of finalizable objects created by this stressor instance. */
  int createdCount() {
    return createdCount;
  }

  /**
   * An object with a slow finalizer. Each instance sleeps for {@code delayMs} milliseconds inside
   * its {@code finalize()} method, backing up the JVM's single-threaded finalizer thread.
   */
  @SuppressWarnings({"deprecation", "removal"})
  private static final class FinalizableObject {

    private final long delayMs;
    private final AtomicBoolean stopped;

    FinalizableObject(final long delayMs, final AtomicBoolean stopped) {
      this.delayMs = delayMs;
      this.stopped = stopped;
    }

    @Override
    protected void finalize() {
      if (stopped.get()) {
        return;
      }
      if (delayMs > 0) {
        try {
          Thread.sleep(delayMs);
        } catch (final InterruptedException ignored) {
          // Do NOT restore the interrupt flag: finalize() runs on the shared JVM finalizer
          // thread, and setting its interrupt bit leaks our chaos signal into every subsequent
          // finalizer on the queue. A legitimate finalize() with its own Thread.sleep or
          // IO call would observe an unexpected interrupt that did not originate from its
          // own bookkeeping — producing sporadic failures in unrelated user-domain objects
          // (JDBC, NIO channels, resource closers) whose root cause would be impossible to
          // trace back to chaos-agent. Swallow silently and let the finalizer complete; the
          // stressor's backlog effect is only observable through delay accumulation, not
          // through individual-object completion. The interrupt was delivered by close() or
          // JVM shutdown and the thread is already being torn down.
        }
      }
    }
  }
}

package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
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

  private static final Logger LOGGER = Logger.getLogger("io.macstab.chaos");

  private final int createdCount;

  @SuppressWarnings({"deprecation", "removal"})
  FinalizerBacklogStressor(final ChaosEffect.FinalizerBacklogEffect effect) {
    final long delayMs = effect.finalizerDelay().toMillis();

    // Create all objects without retaining references. They become GC-eligible immediately.
    for (int i = 0; i < effect.objectCount(); i++) {
      new FinalizableObject(delayMs);
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
    // Objects are already GC-eligible; nothing to release.
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

    FinalizableObject(final long delayMs) {
      this.delayMs = delayMs;
    }

    @Override
    protected void finalize() {
      if (delayMs > 0) {
        try {
          Thread.sleep(delayMs);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}

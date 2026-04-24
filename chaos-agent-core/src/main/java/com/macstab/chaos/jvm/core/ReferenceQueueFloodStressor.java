package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Floods the JVM's {@code ReferenceHandler} thread by creating a large batch of {@link
 * WeakReference} objects pointing to immediately-unreachable referents, then triggering garbage
 * collection to enqueue them.
 *
 * <p>The {@code ReferenceHandler} thread must process every enqueued reference before GC can
 * reclaim the backing memory. A large backlog extends stop-the-world pause durations and can cause
 * application threads to observe stalls while waiting for GC to return memory.
 *
 * <p>This simulates the pattern where a framework-managed weak-reference cache (e.g., a classloader
 * cache, an HTTP session store, or a Hibernate second-level cache) suddenly loses all its referents
 * simultaneously.
 *
 * <p>The stressor runs on a daemon thread that repeats the flood cycle every {@code floodInterval}.
 * {@link #close()} stops the thread and drains the reference queue.
 */
final class ReferenceQueueFloodStressor implements ManagedStressor {

  private static final Logger LOGGER =
      Logger.getLogger(ReferenceQueueFloodStressor.class.getName());

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong floodCycleCount = new AtomicLong();
  private final ReferenceQueue<byte[]> queue = new ReferenceQueue<>();
  private final Thread floodThread;

  ReferenceQueueFloodStressor(final ChaosEffect.ReferenceQueueFloodEffect effect) {
    final int referenceCount = effect.referenceCount();
    final long intervalMillis = effect.floodInterval().toMillis();

    floodThread =
        new Thread(
            () -> {
              while (running.get()) {
                flood(referenceCount);
                floodCycleCount.incrementAndGet();
                if (!running.get()) {
                  break;
                }
                try {
                  Thread.sleep(intervalMillis);
                } catch (final InterruptedException interruptedException) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            },
            "macstab-chaos-refqueue-flood");
    floodThread.setDaemon(true);
    floodThread.start();
    LOGGER.fine(
        "ReferenceQueueFloodStressor started; referenceCount="
            + referenceCount
            + ", floodInterval="
            + intervalMillis
            + "ms");
  }

  @Override
  public void close() {
    running.set(false);
    floodThread.interrupt();
    try {
      floodThread.join(5_000L);
    } catch (final InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    }
    // Drain the queue so we do not leave the ReferenceHandler with a large backlog.
    while (queue.poll() != null) {
      // drain
    }
    LOGGER.fine(
        "ReferenceQueueFloodStressor stopped after " + floodCycleCount.get() + " flood cycles");
  }

  /** Returns the number of completed flood cycles so far. */
  long floodCycleCount() {
    return floodCycleCount.get();
  }

  private void flood(final int count) {
    // A WeakReference is only enqueued when its referent becomes unreachable AND the Reference
    // itself is still reachable at GC time. A bare `new WeakReference<>(...)` has no strong
    // root, so the Reference object is as garbage as its referent and the JVM is allowed to
    // collect both without touching the queue — a classic silent no-op. Anchor the References
    // in a local list that survives System.gc(), then drop the list so the referents become
    // unreachable while the References themselves remain live for enqueueing.
    final List<WeakReference<byte[]>> anchors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      anchors.add(new WeakReference<>(new byte[1024], queue));
    }
    // Suggest GC to enqueue the newly-unreachable referents. The `anchors` list keeps each
    // WeakReference strongly reachable during and after this call so that enqueueing can happen.
    System.gc();
    // Reachability fence: the JVM must not hoist `anchors` unreachable before System.gc()
    // observes the References. Without this, escape analysis + scalar replacement could
    // resurrect the original bug.
    java.lang.ref.Reference.reachabilityFence(anchors);
  }
}

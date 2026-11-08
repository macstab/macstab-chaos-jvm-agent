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
 * After each cycle it drains the references the collector has enqueued so far, so the queue holds
 * at most about one cycle's references and the stressor does not exhaust the heap itself; a
 * reference enqueued late is drained in the next cycle. {@link #close()} stops the thread and
 * drains what is left.
 */
final class ReferenceQueueFloodStressor implements ManagedStressor {

  private static final Logger LOGGER =
      Logger.getLogger(ReferenceQueueFloodStressor.class.getName());

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong floodCycleCount = new AtomicLong();
  private final AtomicLong drainedCount = new AtomicLong();
  private final ReferenceQueue<byte[]> queue = new ReferenceQueue<>();
  private final Thread floodThread;

  ReferenceQueueFloodStressor(final ChaosEffect.ReferenceQueueFloodEffect effect) {
    final int referenceCount = effect.referenceCount();
    final long intervalMillis = effect.floodInterval().toMillis();

    floodThread =
        AgentThreads.start(
            "macstab-chaos-refqueue-flood",
            true,
            () -> {
              while (running.get()) {
                flood(referenceCount);
                drain();
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
            });
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
    AgentThreads.stopAll(List.of(floodThread), 5_000L);
    drain();
    LOGGER.fine(
        "ReferenceQueueFloodStressor stopped after " + floodCycleCount.get() + " flood cycles");
  }

  /** Returns the number of completed flood cycles so far. */
  long floodCycleCount() {
    return floodCycleCount.get();
  }

  /** Returns the number of enqueued references drained from the queue so far. */
  long drainedCount() {
    return drainedCount.get();
  }

  /** Removes every reference enqueued so far, so the queue does not retain them. */
  private void drain() {
    long drained = 0;
    while (queue.poll() != null) {
      drained++;
    }
    drainedCount.addAndGet(drained);
  }

  private void flood(final int count) {
    // A WeakReference is only enqueued when its referent becomes unreachable AND the Reference
    // itself is still reachable at GC time. A bare `new WeakReference<>(...)` has no strong
    // root, so the Reference object is as garbage as its referent and the JVM is allowed to
    // collect both without touching the queue — a classic silent no-op. Anchor the References
    // in a local list that survives System.gc(), then drop the list so the referents become
    // unreachable while the References themselves remain live for enqueueing.
    final List<WeakReference<byte[]>> anchors = new ArrayList<>(count);
    try {
      for (int i = 0; i < count; i++) {
        anchors.add(new WeakReference<>(new byte[1024], queue));
      }
      // Suggest GC to enqueue the newly-unreachable referents. The `anchors` list keeps each
      // WeakReference strongly reachable during and after this call so that enqueueing can
      // happen.
      System.gc();
    } finally {
      // Reachability fence: the JVM must not hoist `anchors` unreachable before System.gc()
      // observes the References. Without this, escape analysis + scalar replacement could let
      // the References be collected before they are ever enqueued.
      java.lang.ref.Reference.reachabilityFence(anchors);
    }
  }
}

package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Takes {@code pinnedThreadCount} carrier threads of the virtual-thread scheduler out of service,
 * so other virtual threads queue for a carrier: the throughput collapse a pinned carrier causes.
 *
 * <h2>How a carrier is held</h2>
 *
 * <p>The stressor starts {@code pinnedThreadCount} <em>virtual</em> threads. Each one mounts on a
 * carrier and keeps it in cycles of {@link
 * ChaosEffect.VirtualThreadCarrierPinningEffect#pinDuration()}, never unmounting between cycles:
 *
 * <ul>
 *   <li><b>JDK 21–23:</b> the virtual thread sleeps while holding a private monitor. Blocking
 *       inside {@code synchronized} pins the carrier, so the carrier is held without using CPU —
 *       exactly the pinning an application causes.
 *   <li><b>JDK 24+:</b> JEP 491 lets virtual threads unmount inside {@code synchronized}, so a
 *       monitor no longer pins. The virtual thread instead keeps its carrier busy with a spin-wait.
 *       The carrier is just as unavailable to other virtual threads, but the stressor uses one CPU
 *       per held carrier while active.
 * </ul>
 *
 * <p>The loops run on <em>virtual</em> threads on purpose: a platform thread is not a carrier, so
 * holding one would never affect the virtual-thread scheduler and the effect would do nothing on
 * any JDK.
 *
 * <p>The stressor's threads are agent-owned: they are started and run with the dispatch depth
 * raised, so user scenarios (thread start, sleep, park) never apply to them. {@link #close()} stops
 * and joins them.
 */
final class VirtualThreadCarrierPinningStressor implements ManagedStressor {

  private static final AgentLog LOGGER = AgentLog.named("com.macstab.chaos.jvm");

  /** First JDK feature release in which {@code synchronized} no longer pins (JEP 491). */
  private static final int MONITORS_UNPIN_FROM_FEATURE = 24;

  private static final long JOIN_TIMEOUT_MILLIS = 1_000L;

  private final List<Thread> pinningThreads;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  VirtualThreadCarrierPinningStressor(final ChaosEffect.VirtualThreadCarrierPinningEffect effect) {
    this(effect, NO_END);
  }

  /**
   * Creates the stressor with the holding strategy of this JDK; its threads end the scenario
   * through {@code onEnd} if one fails.
   *
   * @param effect the effect parameters
   * @param onEnd the scenario's end callback, told when a stressor thread fails
   */
  VirtualThreadCarrierPinningStressor(
      final ChaosEffect.VirtualThreadCarrierPinningEffect effect, final Consumer<String> onEnd) {
    this(effect, Runtime.version().feature() < MONITORS_UNPIN_FROM_FEATURE, onEnd);
  }

  /**
   * Creates the stressor with an explicit holding strategy.
   *
   * @param effect the effect parameters
   * @param pinWithMonitor {@code true} to hold carriers by blocking inside {@code synchronized}
   *     (JDK 21–23), {@code false} to hold them with a spin-wait (JDK 24+)
   */
  VirtualThreadCarrierPinningStressor(
      final ChaosEffect.VirtualThreadCarrierPinningEffect effect, final boolean pinWithMonitor) {
    this(effect, pinWithMonitor, NO_END);
  }

  private VirtualThreadCarrierPinningStressor(
      final ChaosEffect.VirtualThreadCarrierPinningEffect effect,
      final boolean pinWithMonitor,
      final Consumer<String> onEnd) {
    final long pinNanos = ManagedStressor.nanos(effect.pinDuration());
    final int count = effect.pinnedThreadCount();
    final CountDownLatch ready = new CountDownLatch(count);
    final List<Thread> threads = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final String name = "chaos-carrier-pin-" + i;
      final Runnable body =
          pinWithMonitor ? () -> pinWithMonitor(pinNanos) : () -> occupyWithSpin(pinNanos);
      threads.add(
          Thread.ofVirtual()
              .name(name)
              .unstarted(
                  AgentThreads.owned(
                      () -> {
                        ready.countDown();
                        body.run();
                        LOGGER.fine(() -> "chaos carrier-pin thread terminated: " + name);
                      },
                      ManagedStressor.failure(onEnd, "VirtualThreadCarrierPinning"))));
    }
    this.pinningThreads = AgentThreads.startAll(threads);
  }

  /**
   * Returns {@code effect} with its thread count reduced so at least one carrier of the
   * virtual-thread scheduler stays free, or {@code null} when the scheduler has a single carrier.
   *
   * <p>Holding every carrier stops every virtual thread in the JVM until the scenario ends: an
   * application serving requests on virtual threads stops responding, which is a deadlock rather
   * than pinning. Whether a plan does that depends on the machine — {@code pinnedThreadCount = 4}
   * leaves carriers free on a 16-core workstation and holds all of them on a 4-core CI runner — so
   * it requires {@code allowDestructiveEffects}.
   *
   * @param effect the requested effect
   * @return the effect to run, or {@code null} to run nothing
   */
  static ChaosEffect.@Nullable VirtualThreadCarrierPinningEffect leavingOneCarrier(
      final ChaosEffect.VirtualThreadCarrierPinningEffect effect) {
    final int carriers = schedulerParallelism();
    final int allowed = carriers - 1;
    if (effect.pinnedThreadCount() <= allowed) {
      return effect;
    }
    if (allowed < 1) {
      LOGGER.warning(
          () ->
              "[chaos-agent] virtual-thread carrier pinning skipped: the scheduler has a single"
                  + " carrier, and holding it needs allowDestructiveEffects");
      return null;
    }
    LOGGER.warning(
        () ->
            "[chaos-agent] virtual-thread carrier pinning of "
                + effect.pinnedThreadCount()
                + " carriers capped at "
                + allowed
                + " of "
                + carriers
                + "; holding every carrier needs allowDestructiveEffects");
    return new ChaosEffect.VirtualThreadCarrierPinningEffect(allowed, effect.pinDuration());
  }

  /** The default virtual-thread scheduler's parallelism (its number of carriers). */
  static int schedulerParallelism() {
    final String configured = System.getProperty("jdk.virtualThreadScheduler.parallelism");
    if (configured != null) {
      try {
        return Integer.parseInt(configured.trim());
      } catch (final NumberFormatException ignored) {
        // the JDK rejects such a value too; fall back to its default
      }
    }
    return Runtime.getRuntime().availableProcessors();
  }

  /**
   * Holds the carrier by sleeping inside a private monitor. Each thread uses its own monitor so the
   * threads do not serialise on one lock. The monitor is re-entered immediately after each cycle,
   * without unmounting, so the carrier is never handed back while active.
   */
  private void pinWithMonitor(final long pinNanos) {
    final Object monitor = new Object();
    while (!stopped.get()) {
      synchronized (monitor) {
        final long start = System.nanoTime();
        while (!stopped.get() && System.nanoTime() - start < pinNanos) {
          try {
            Thread.sleep(1L);
          } catch (final InterruptedException interrupted) {
            return;
          }
        }
      }
    }
  }

  /** Holds the carrier by never blocking; checks the stop flag between spin iterations. */
  private void occupyWithSpin(final long pinNanos) {
    while (!stopped.get()) {
      final long start = System.nanoTime();
      while (!stopped.get() && System.nanoTime() - start < pinNanos) {
        Thread.onSpinWait();
      }
    }
  }

  @Override
  public void close() {
    stopped.set(true);
    // Join all threads even when interrupted mid-loop, then restore the interrupt: close() must
    // not return while carriers are still held.
    AgentThreads.stopAll(pinningThreads, JOIN_TIMEOUT_MILLIS);
  }

  long aliveCount() {
    return pinningThreads.stream().filter(Thread::isAlive).count();
  }
}

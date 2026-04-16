package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ManagedStressor} that simulates heap pressure by allocating and retaining a configurable
 * number of bytes for the lifetime of the associated chaos scenario.
 *
 * <p>On construction, the stressor allocates byte arrays totalling the configured byte count and
 * stores strong references to them in an internal list, preventing GC from collecting them. This
 * simulates an application running low on heap space without actually exhausting it.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Allocation happens eagerly in the constructor. {@link #close()} clears the internal list,
 * releasing the references and allowing the GC to reclaim the allocated memory.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The retained list is populated only in the constructor and cleared only in {@link #close()}.
 * Provided that the owning {@link ScenarioController} does not call the constructor and {@code
 * close()} concurrently, no external synchronization is required.
 */
final class HeapPressureStressor implements ManagedStressor {
  private final List<byte[]> retained = new ArrayList<>();

  /**
   * Allocates and retains byte arrays totalling the number of bytes specified by {@code effect}.
   *
   * <p>The total allocation is divided into chunks no larger than {@link
   * ChaosEffect.HeapPressureEffect#chunkSizeBytes()} to avoid a single oversized array request that
   * might fail with an {@link OutOfMemoryError} before the pressure target is reached.
   *
   * @param effect the heap-pressure effect configuration supplying the total byte target and chunk
   *     size
   */
  HeapPressureStressor(final ChaosEffect.HeapPressureEffect effect) {
    long remaining = effect.bytes();
    while (remaining > 0) {
      final int allocation = (int) Math.min(effect.chunkSizeBytes(), remaining);
      retained.add(new byte[allocation]);
      remaining -= allocation;
    }
  }

  /**
   * Releases all retained byte arrays by clearing the internal list.
   *
   * <p>After this method returns, the previously allocated arrays are eligible for GC, and the
   * simulated heap pressure is removed.
   */
  @Override
  public void close() {
    retained.clear();
  }
}

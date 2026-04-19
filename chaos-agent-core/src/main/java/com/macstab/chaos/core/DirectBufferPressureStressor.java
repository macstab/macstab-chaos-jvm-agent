package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Simulates native direct memory exhaustion by allocating off-heap {@link ByteBuffer}s without
 * triggering their Cleaner.
 *
 * <p>Two operating modes controlled by {@link
 * ChaosEffect.DirectBufferPressureEffect#registerCleaner()}:
 *
 * <ul>
 *   <li>{@code registerCleaner=false} (the default leak mode): strong references are kept in this
 *       stressor. No Cleaner is registered, so native memory is not freed until {@link #close()} is
 *       called or the JVM exits.
 *   <li>{@code registerCleaner=true}: a {@link Cleaner} action is registered whose referent is
 *       <em>this stressor</em>, so the cleaner fires only when this stressor instance becomes
 *       phantom-reachable (i.e., the stressor has been released by the agent). The direct buffer is
 *       also strongly retained so that it survives until explicit {@link #close()} or GC of the
 *       stressor itself.
 * </ul>
 *
 * <p>{@link #close()} attempts to release native memory immediately via reflection on the internal
 * {@code sun.nio.ch.DirectBuffer.cleaner()} mechanism. If that mechanism is not accessible (sealed
 * module), the buffer reference is simply nulled and native memory release is deferred to GC.
 */
final class DirectBufferPressureStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");
  private static final Cleaner CLEANER = Cleaner.create();

  private final List<ByteBuffer> retainedBuffers;

  DirectBufferPressureStressor(final ChaosEffect.DirectBufferPressureEffect effect) {
    final List<ByteBuffer> buffers = new ArrayList<>();
    long remaining = effect.totalBytes();
    while (remaining > 0) {
      final int size = (int) Math.min(effect.bufferSizeBytes(), remaining);
      final ByteBuffer buffer = ByteBuffer.allocateDirect(size);
      // Always retain a strong reference so the buffer survives until close() or until this
      // stressor instance becomes phantom-reachable. Without this retention, registerCleaner=true
      // previously used a throwaway local referent that became unreachable at loop-iteration
      // end, so GC could reclaim the buffer immediately and the stressor produced zero
      // sustained pressure.
      buffers.add(buffer);
      if (effect.registerCleaner()) {
        // Referent = this stressor. The cleaner fires only when the stressor is
        // phantom-reachable (e.g., dereferenced without explicit close()); freeDirectBuffer
        // is safely idempotent, so close()+GC doesn't double-free in a harmful way.
        CLEANER.register(this, () -> freeDirectBuffer(buffer));
      }
      remaining -= size;
    }
    this.retainedBuffers = List.copyOf(buffers);
  }

  @Override
  public void close() {
    for (final ByteBuffer buffer : retainedBuffers) {
      freeDirectBuffer(buffer);
    }
  }

  /** Returns the number of directly-retained buffers (registerCleaner=false only). */
  int retainedBufferCount() {
    return retainedBuffers.size();
  }

  private static void freeDirectBuffer(final ByteBuffer buffer) {
    if (buffer == null || !buffer.isDirect()) {
      return;
    }
    try {
      // Access the internal sun.nio.ch.DirectBuffer cleaner via reflection.
      // On JDK 9+ with --add-opens this is accessible; on sealed builds the fallback
      // is to let GC handle it.
      final Object cleaner = buffer.getClass().getMethod("cleaner").invoke(buffer);
      if (cleaner != null) {
        cleaner.getClass().getMethod("clean").invoke(cleaner);
      }
    } catch (final Exception e) {
      LOGGER.fine(() -> "DirectBufferPressureStressor: could not eagerly free direct buffer: " + e);
    }
  }
}

package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.ref.Cleaner;
import java.lang.reflect.Method;
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

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");
  private static final Cleaner CLEANER = Cleaner.create();

  /** Cached reflection handle for {@code sun.nio.ch.DirectBuffer.cleaner()}, or {@code null}. */
  private static final Method DIRECT_BUFFER_CLEANER = resolveCleanerMethod();

  /** Cached reflection handle for {@code sun.misc.Cleaner.clean()}, or {@code null}. */
  private static final Method CLEANER_CLEAN = resolveCleanerCleanMethod();

  private static Method resolveCleanerMethod() {
    try {
      final Method m = ByteBuffer.allocateDirect(1).getClass().getMethod("cleaner");
      m.setAccessible(true);
      return m;
    } catch (final Exception ignored) {
      return null;
    }
  }

  private static Method resolveCleanerCleanMethod() {
    if (DIRECT_BUFFER_CLEANER == null) {
      return null;
    }
    try {
      final Object cleaner = DIRECT_BUFFER_CLEANER.invoke(ByteBuffer.allocateDirect(1));
      if (cleaner == null) {
        return null;
      }
      final Method m = cleaner.getClass().getMethod("clean");
      m.setAccessible(true);
      return m;
    } catch (final Exception ignored) {
      return null;
    }
  }

  private volatile List<ByteBuffer> retainedBuffers;

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
    final List<ByteBuffer> buffers = retainedBuffers;
    retainedBuffers = List.of();
    if (buffers != null) {
      for (final ByteBuffer buffer : buffers) {
        freeDirectBuffer(buffer);
      }
    }
  }

  /** Returns the number of directly-retained buffers (registerCleaner=false only). */
  int retainedBufferCount() {
    final List<ByteBuffer> snapshot = retainedBuffers;
    return snapshot == null ? 0 : snapshot.size();
  }

  private static void freeDirectBuffer(final ByteBuffer buffer) {
    if (buffer == null || !buffer.isDirect() || DIRECT_BUFFER_CLEANER == null) {
      return;
    }
    try {
      final Object cleaner = DIRECT_BUFFER_CLEANER.invoke(buffer);
      if (cleaner != null && CLEANER_CLEAN != null) {
        CLEANER_CLEAN.invoke(cleaner);
      }
    } catch (final Exception e) {
      LOGGER.fine(() -> "DirectBufferPressureStressor: could not eagerly free direct buffer: " + e);
    }
  }
}

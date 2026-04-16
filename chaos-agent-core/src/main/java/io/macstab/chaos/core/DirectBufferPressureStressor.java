package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
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
 *   <li>{@code registerCleaner=true}: a {@link Cleaner} action is registered against a phantom
 *       object, but the direct buffer reference is then dropped. Native memory will be freed when
 *       the GC next collects the phantom — but because no strong reference to the buffer is kept,
 *       the GC may collect it at any time, making this mode less predictable as a pressure tool.
 * </ul>
 *
 * <p>{@link #close()} attempts to release native memory immediately via reflection on the internal
 * {@code sun.nio.ch.DirectBuffer.cleaner()} mechanism. If that mechanism is not accessible (sealed
 * module), the buffer reference is simply nulled and native memory release is deferred to GC.
 */
final class DirectBufferPressureStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("io.macstab.chaos");
  private static final Cleaner CLEANER = Cleaner.create();

  private final List<ByteBuffer> retainedBuffers;

  DirectBufferPressureStressor(final ChaosEffect.DirectBufferPressureEffect effect) {
    final List<ByteBuffer> buffers = new ArrayList<>();
    long remaining = effect.totalBytes();
    while (remaining > 0) {
      final int size = (int) Math.min(effect.bufferSizeBytes(), remaining);
      final ByteBuffer buffer = ByteBuffer.allocateDirect(size);
      if (effect.registerCleaner()) {
        // Register a Cleaner action but do NOT retain the buffer. Native memory will be
        // freed when GC collects the phantom referent.
        final Object referent = new Object();
        CLEANER.register(referent, () -> freeDirectBuffer(buffer));
      } else {
        buffers.add(buffer);
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

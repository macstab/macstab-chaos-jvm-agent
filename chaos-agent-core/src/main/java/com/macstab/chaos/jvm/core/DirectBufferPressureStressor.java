package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

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
 *   <li>{@code registerCleaner=true}: each buffer is allocated and dropped, so the JDK's own
 *       cleaner frees it when a collection finds it unreachable: the pressure lasts until the next
 *       GC, like a burst of short-lived buffers.
 * </ul>
 *
 * <p>{@link #close()} attempts to release native memory immediately via reflection on the internal
 * {@code sun.nio.ch.DirectBuffer.cleaner()} mechanism. If that mechanism is not accessible (sealed
 * module), the buffer reference is simply nulled and native memory release is deferred to GC.
 */
final class DirectBufferPressureStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  /** Cached reflection handle for {@code sun.nio.ch.DirectBuffer.cleaner()}, or {@code null}. */
  private static final @Nullable Method DIRECT_BUFFER_CLEANER = resolveCleanerMethod();

  /** Cached reflection handle for {@code sun.misc.Cleaner.clean()}, or {@code null}. */
  private static final @Nullable Method CLEANER_CLEAN = resolveCleanerCleanMethod();

  /**
   * Resolves {@code cleaner()} on the {@code sun.nio.ch.DirectBuffer} interface, whose package the
   * agent opens (manifest {@code Add-Opens} and {@code redefineModule}). The buffer's own class,
   * {@code java.nio.DirectByteBuffer}, is package-private in the unopened {@code java.nio} package:
   * looked up there, {@code setAccessible} always fails, the handle is {@code null}, and {@link
   * #close()} cannot free native memory eagerly — it is released only whenever a GC happens to
   * collect the buffers. That is logged once as a WARNING.
   */
  private static @Nullable Method resolveCleanerMethod() {
    try {
      final Method m = Class.forName("sun.nio.ch.DirectBuffer").getMethod("cleaner");
      m.setAccessible(true);
      return m;
    } catch (final Exception | LinkageError inaccessible) {
      warnNoEagerRelease(inaccessible);
      return null;
    }
  }

  private static void warnNoEagerRelease(final Throwable cause) {
    LOGGER.warning(
        () ->
            "DirectBufferPressure: the direct buffer cleaner is out of reach ("
                + cause
                + "); a stopped stressor's memory is freed only by a later GC");
  }

  private static @Nullable Method resolveCleanerCleanMethod() {
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
    } catch (final Exception inaccessible) {
      warnNoEagerRelease(inaccessible);
      return null;
    }
  }

  private volatile List<ByteBuffer> retainedBuffers;

  DirectBufferPressureStressor(final ChaosEffect.DirectBufferPressureEffect effect) {
    final List<ByteBuffer> buffers = new ArrayList<>();
    try {
      allocate(effect, buffers);
    } catch (final OutOfMemoryError exhausted) {
      // Out of direct memory (memory reserved elsewhere makes the activation-time limit check no
      // guarantee): free what this stressor took, so the failed start leaves nothing behind.
      for (final ByteBuffer buffer : buffers) {
        freeDirectBuffer(buffer);
      }
      throw exhausted;
    }
    this.retainedBuffers = List.copyOf(buffers);
  }

  private void allocate(
      final ChaosEffect.DirectBufferPressureEffect effect, final List<ByteBuffer> buffers) {
    long remaining = effect.totalBytes();
    while (remaining > 0) {
      final int size = (int) Math.min(effect.bufferSizeBytes(), remaining);
      final ByteBuffer buffer = ByteBuffer.allocateDirect(size);
      if (!effect.registerCleaner()) {
        buffers.add(buffer);
      }
      // else dropped: the JDK's cleaner frees it once a collection finds it unreachable
      remaining -= size;
    }
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

  /** Returns the number of retained buffers: none with registerCleaner=true, or after close(). */
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

package com.macstab.chaos.jvm.instrumentation.bridge;

import java.nio.ByteBuffer;

/**
 * The buffers of one channel read or write that chaos shapes: limited so the call transfers fewer
 * bytes (a torn write, a trickled read), and marked so one bit of the bytes a read returns can be
 * flipped. Covers a single buffer and the scattering / gathering {@code ByteBuffer[]} variants
 * alike.
 *
 * <p>Created only when a scenario shapes the call; the unshaped path allocates nothing. The advice
 * calls {@link #finish(long)} when the call returns or throws.
 *
 * <p>Loaded by the bootstrap class loader, like {@link BootstrapDispatcher}: JDK classes call it.
 */
public final class ChannelBuffers {

  private final ByteBuffer[] buffers;
  private final int offset;
  private final int length;

  /** The limits before the call, or {@code null} when the call is not limited. */
  private final int[] limits;

  /** The positions before the call, or {@code null} when the bytes read are not corrupted. */
  private final int[] positions;

  private ChannelBuffers(
      final ByteBuffer[] buffers,
      final int offset,
      final int length,
      final int[] limits,
      final int[] positions) {
    this.buffers = buffers;
    this.offset = offset;
    this.length = length;
    this.limits = limits;
    this.positions = positions;
  }

  /**
   * Returns the bytes {@code buffers[offset .. offset+length)} hold (writes) or can take (reads),
   * capped at {@link Integer#MAX_VALUE}; {@code -1} when the arguments are not valid, which the
   * call itself then rejects.
   *
   * @param buffers the buffers
   * @param offset the first buffer
   * @param length the number of buffers
   * @return the bytes, or {@code -1}
   */
  static int remaining(final ByteBuffer[] buffers, final int offset, final int length) {
    if (buffers == null || offset < 0 || length < 0 || offset > buffers.length - length) {
      return -1;
    }
    long total = 0;
    for (int i = offset; i < offset + length; i++) {
      if (buffers[i] == null) {
        return -1;
      }
      total += buffers[i].remaining();
    }
    return (int) Math.min(total, Integer.MAX_VALUE);
  }

  /**
   * Shapes the buffers for a call the dispatcher answered with {@code count}: a count below {@code
   * requested} limits the buffers to that many bytes, a negative count ({@code -(n) - 1}) also
   * marks the bytes read for corruption.
   *
   * @param buffers the buffers
   * @param offset the first buffer
   * @param length the number of buffers
   * @param requested what {@link #remaining} returned
   * @param count the dispatcher's answer
   * @return the shaping, or {@code null} when the call runs unchanged
   */
  static ChannelBuffers shape(
      final ByteBuffer[] buffers,
      final int offset,
      final int length,
      final int requested,
      final int count) {
    final boolean corrupt = count < 0;
    final int bytes = corrupt ? -count - 1 : count;
    final boolean limit = requested >= 0 && bytes < requested;
    if (requested < 0 || (!limit && !corrupt)) {
      return null;
    }
    int[] limits = null;
    if (limit) {
      limits = new int[length];
      long left = bytes;
      for (int i = 0; i < length; i++) {
        final ByteBuffer buffer = buffers[offset + i];
        limits[i] = buffer.limit();
        final int take = (int) Math.min(left, buffer.remaining());
        buffer.limit(buffer.position() + take);
        left -= take;
      }
    }
    int[] positions = null;
    if (corrupt) {
      positions = new int[length];
      for (int i = 0; i < length; i++) {
        positions[i] = buffers[offset + i].position();
      }
    }
    return new ChannelBuffers(buffers, offset, length, limits, positions);
  }

  /**
   * Restores the buffers' limits and, for a corrupted read, flips one bit of the bytes it returned.
   * The bytes a read returned lie between each buffer's position before and after it.
   *
   * @param transferred what the call returned; {@code 0} when it threw
   */
  public void finish(final long transferred) {
    if (limits != null) {
      for (int i = 0; i < length; i++) {
        buffers[offset + i].limit(limits[i]);
      }
    }
    if (positions == null || transferred <= 0) {
      return;
    }
    final java.util.SplittableRandom random =
        new java.util.SplittableRandom(BootstrapDispatcher.corruptionSeed());
    long target = random.nextLong(transferred);
    for (int i = 0; i < length; i++) {
      final ByteBuffer buffer = buffers[offset + i];
      final int received = buffer.position() - positions[i];
      if (target < received) {
        if (!buffer.isReadOnly()) {
          final int index = positions[i] + (int) target;
          buffer.put(index, (byte) (buffer.get(index) ^ (1 << random.nextInt(8))));
        }
        return;
      }
      target -= received;
    }
  }
}

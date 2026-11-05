package com.macstab.chaos.jvm.instrumentation.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How {@link ChannelBuffers} sizes and shapes the buffers of a channel call, at the edges the
 * dispatcher's callers reach: invalid buffer ranges, calls that transferred nothing, read-only
 * buffers.
 */
@DisplayName("ChannelBuffers")
class ChannelBuffersTest {

  private static ByteBuffer[] buffers(final int count, final int size) {
    final ByteBuffer[] buffers = new ByteBuffer[count];
    for (int i = 0; i < count; i++) {
      buffers[i] = ByteBuffer.allocate(size);
    }
    return buffers;
  }

  /** The number of bits set over the whole capacity of {@code buffers}. */
  private static int bitsSet(final ByteBuffer... buffers) {
    int bits = 0;
    for (final ByteBuffer buffer : buffers) {
      for (int i = 0; i < buffer.capacity(); i++) {
        bits += Integer.bitCount(buffer.get(i) & 0xFF);
      }
    }
    return bits;
  }

  @Test
  @DisplayName("sums the buffers of a valid range, and rejects an invalid one")
  void remaining() {
    final ByteBuffer[] buffers = buffers(3, 4);
    assertThat(ChannelBuffers.remaining(buffers, 1, 2)).isEqualTo(8);
    assertThat(ChannelBuffers.remaining(buffers, 0, 0)).isZero();
    assertThat(ChannelBuffers.remaining(null, 0, 1)).isEqualTo(-1);
    assertThat(ChannelBuffers.remaining(buffers, -1, 1)).isEqualTo(-1);
    assertThat(ChannelBuffers.remaining(buffers, 0, -1)).isEqualTo(-1);
    assertThat(ChannelBuffers.remaining(buffers, 2, 2)).isEqualTo(-1);
  }

  @Test
  @DisplayName("an invalid range or a full, uncorrupted count needs no shaping")
  void noShaping() {
    final ByteBuffer[] buffers = buffers(1, 8);
    assertThat(ChannelBuffers.shape(buffers, 0, 1, -1, -5)).isNull();
    assertThat(ChannelBuffers.shape(buffers, 0, 1, 8, 8)).isNull();
    assertThat(ChannelBuffers.shape(buffers, 0, 1, 8, 9)).isNull();
    assertThat(buffers[0].limit()).isEqualTo(8);
  }

  @Test
  @DisplayName("a corrupted read that transferred nothing, or did not advance, flips no bit")
  void corruptedReadWithoutBytes() {
    final ByteBuffer[] buffers = buffers(2, 4);
    final ChannelBuffers thrown = ChannelBuffers.shape(buffers, 0, 2, 8, -8 - 1);
    thrown.finish(0);
    final ChannelBuffers stalled = ChannelBuffers.shape(buffers, 0, 2, 8, -8 - 1);
    stalled.finish(4);
    assertThat(bitsSet(buffers)).isZero();
  }

  @Test
  @DisplayName("a corrupted read into a read-only buffer leaves it unchanged")
  void readOnlyBuffer() {
    final ByteBuffer readOnly = ByteBuffer.allocate(4).asReadOnlyBuffer();
    final ChannelBuffers shaping =
        ChannelBuffers.shape(new ByteBuffer[] {readOnly}, 0, 1, 4, -4 - 1);
    readOnly.position(4);
    shaping.finish(4);
    assertThat(bitsSet(readOnly)).isZero();
  }

  @Test
  @DisplayName("a limited and corrupted read restores the limits and flips one returned bit")
  void limitedAndCorrupted() {
    final ByteBuffer[] buffers = buffers(2, 4);
    final ChannelBuffers shaping = ChannelBuffers.shape(buffers, 0, 2, 8, -6 - 1);
    assertThat(buffers[0].remaining() + buffers[1].remaining()).isEqualTo(6);
    buffers[0].position(4);
    buffers[1].position(2);
    shaping.finish(6);
    assertThat(buffers[1].limit()).isEqualTo(4);
    assertThat(bitsSet(buffers)).isEqualTo(1);
    assertThat(buffers[1].get(2)).as("beyond the bytes read").isZero();
    assertThat(buffers[1].get(3)).as("beyond the bytes read").isZero();
  }
}

package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HeapPressureStressor")
class HeapPressureStressorTest {

  @Test
  @DisplayName("retains the configured bytes in chunks of at most the chunk size until closed")
  void retainsTheBytesInChunks() throws ReflectiveOperationException {
    final HeapPressureStressor stressor =
        new HeapPressureStressor(ChaosEffect.heapPressure(2_500_000L, 1_000_000));
    try {
      assertThat(retained(stressor))
          .extracting(chunk -> chunk.length)
          .containsExactly(1_000_000, 1_000_000, 500_000);
    } finally {
      stressor.close();
    }

    assertThat(retained(stressor)).as("released on close").isEmpty();
  }

  @SuppressWarnings("unchecked")
  private static List<byte[]> retained(final HeapPressureStressor stressor)
      throws ReflectiveOperationException {
    final Field field = HeapPressureStressor.class.getDeclaredField("retained");
    field.setAccessible(true);
    return (List<byte[]>) field.get(stressor);
  }
}

package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GC pressure stressor sizing")
class GcPressureStressorTest {

  @Test
  @DisplayName("a batch holds the rate's bytes for one interval, in objects of the given size")
  void batchFromRate() {
    // 10 MB/s in 50 ms batches of 1 KiB objects
    assertThat(GcPressureStressor.objectsPerBatch(10_000_000L, 1024)).isEqualTo(488);
  }

  @Test
  @DisplayName("a batch holds at least one object")
  void atLeastOneObject() {
    assertThat(GcPressureStressor.objectsPerBatch(1L, 1024)).isEqualTo(1);
  }

  @Test
  @DisplayName("a huge rate of small objects saturates instead of wrapping to a negative count")
  void hugeRateSaturates() {
    assertThat(GcPressureStressor.objectsPerBatch(Long.MAX_VALUE, 1)).isEqualTo(Integer.MAX_VALUE);
    assertThat(GcPressureStressor.objectsPerBatch(1_000_000_000_000_000L, 1))
        .isEqualTo(Integer.MAX_VALUE);
  }
}

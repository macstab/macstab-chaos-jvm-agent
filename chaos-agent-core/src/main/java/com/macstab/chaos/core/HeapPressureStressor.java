package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;

final class HeapPressureStressor implements ManagedStressor {
  private final List<byte[]> retained = new ArrayList<>();

  HeapPressureStressor(final ChaosEffect.HeapPressureEffect effect) {
    long remaining = effect.bytes();
    while (remaining > 0) {
      final int allocation = (int) Math.min(effect.chunkSizeBytes(), remaining);
      retained.add(new byte[allocation]);
      remaining -= allocation;
    }
  }

  @Override
  public void close() {
    retained.clear();
  }
}

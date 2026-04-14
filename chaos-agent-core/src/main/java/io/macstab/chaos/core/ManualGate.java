package io.macstab.chaos.core;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ManualGate {
  private volatile CountDownLatch latch = new CountDownLatch(1);

  void reset() {
    latch = new CountDownLatch(1);
  }

  void release() {
    latch.countDown();
  }

  void await(Duration maxBlock) throws InterruptedException {
    if (maxBlock == null) {
      latch.await();
      return;
    }
    latch.await(maxBlock.toMillis(), TimeUnit.MILLISECONDS);
  }
}

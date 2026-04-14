package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
import java.util.concurrent.atomic.AtomicBoolean;

final class KeepAliveStressor implements ManagedStressor {
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread thread;

  KeepAliveStressor(ChaosEffect.KeepAliveEffect effect) {
    this.thread =
        new Thread(
            () -> {
              while (running.get()) {
                try {
                  Thread.sleep(effect.heartbeat().toMillis());
                } catch (InterruptedException interruptedException) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            },
            effect.threadName());
    thread.setDaemon(effect.daemon());
    thread.start();
  }

  @Override
  public void close() {
    running.set(false);
    thread.interrupt();
  }
}

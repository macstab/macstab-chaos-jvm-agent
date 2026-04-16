package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ManagedStressor} that keeps a daemon thread alive for the lifetime of the associated chaos
 * scenario.
 *
 * <p>The background thread runs a tight heartbeat loop ({@code Thread.sleep} in a loop) to simulate
 * the presence of an additional non-terminating thread. This can be used to verify that application
 * behaviour is correct under the presence of unexpected long-lived threads or to consume
 * thread-pool slots.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The thread is started by the constructor and runs until {@link #close()} is called. {@link
 * #close()} sets a stop flag and interrupts the thread, causing the loop to exit.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@code running} is an {@link java.util.concurrent.atomic.AtomicBoolean}; the constructor and
 * {@link #close()} are thread-safe.
 */
final class KeepAliveStressor implements ManagedStressor {
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread thread;

  /**
   * Creates and immediately starts the keep-alive background thread.
   *
   * <p>The thread name and daemon status are taken from {@code effect}. The thread sleeps for
   * {@link ChaosEffect.KeepAliveEffect#heartbeat()} between iterations, waking early only when
   * interrupted. An {@link InterruptedException} restores the interrupt flag and exits the loop
   * cleanly.
   *
   * @param effect the keep-alive effect configuration supplying the thread name, daemon flag, and
   *     heartbeat interval
   */
  KeepAliveStressor(final ChaosEffect.KeepAliveEffect effect) {
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

  /**
   * Signals the background thread to stop and interrupts it.
   *
   * <p>Sets {@code running} to {@code false} so that the heartbeat loop exits after the current
   * sleep completes, then interrupts the thread so the loop exits immediately if it is currently
   * sleeping inside {@link Thread#sleep}.
   */
  @Override
  public void close() {
    running.set(false);
    thread.interrupt();
  }
}

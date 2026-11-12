package com.macstab.chaos.jvm.api;

import java.util.Objects;

/**
 * A listener the control plane calls on an agent-owned thread, through a bounded queue; created by
 * {@link ChaosEventListener#async(ChaosEventListener)}. Calling {@link #onEvent} directly calls the
 * delegate on the calling thread.
 */
public final class AsyncChaosEventListener implements ChaosEventListener {

  private final ChaosEventListener delegate;

  AsyncChaosEventListener(final ChaosEventListener delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Returns the wrapped listener.
   *
   * @return the delegate
   */
  public ChaosEventListener delegate() {
    return delegate;
  }

  @Override
  public void onEvent(final ChaosEvent event) {
    delegate.onEvent(event);
  }
}

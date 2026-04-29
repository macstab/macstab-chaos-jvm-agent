package com.macstab.chaos.jvm.testkit;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Test-scoped decorator around a JVM-wide {@link ChaosControlPlane} that tracks every handle
 * returned from {@link #activate(ChaosScenario)} / {@link #activate(ChaosPlan)} so the JUnit
 * extension can stop them all when a test finishes.
 *
 * <p>The underlying control plane is a JVM-wide singleton (see {@code
 * com.macstab.chaos.jvm.bootstrap.ChaosAgentBootstrap.RUNTIME}); closing it would kill every other
 * test's instrumentation. The wrapper therefore does not close the delegate — it only stops the
 * handles it issued. JVM-scoped scenarios that leak across tests are the single most common source
 * of test-suite flakiness in chaos-driven test fleets, so auto-cleanup at the extension level is
 * load-bearing.
 *
 * <p>{@link #close()} is intentionally a no-op. Tests that want the full JVM-wide control plane
 * should inject it themselves via {@link
 * com.macstab.chaos.jvm.bootstrap.ChaosPlatform#installLocally()} — at that point the caller owns
 * the JVM-wide state and the extension is out of the picture.
 */
public final class TrackingChaosControlPlane implements ChaosControlPlane {

  private static final Logger LOGGER = Logger.getLogger(TrackingChaosControlPlane.class.getName());

  private final ChaosControlPlane delegate;
  private final Deque<ChaosActivationHandle> handles = new ArrayDeque<>();

  /**
   * Wraps the given JVM-wide control plane so every handle issued through this instance is tracked
   * for {@link #stopTracked() stopTracked}-driven cleanup. The delegate itself is never closed.
   *
   * @param delegate the JVM-wide control plane to decorate; must not be {@code null}
   */
  public TrackingChaosControlPlane(final ChaosControlPlane delegate) {
    this.delegate = delegate;
  }

  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    final ChaosActivationHandle handle = delegate.activate(scenario);
    track(handle);
    return handle;
  }

  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    final ChaosActivationHandle handle = delegate.activate(plan);
    track(handle);
    return handle;
  }

  @Override
  public ChaosSession openSession(final String displayName) {
    return delegate.openSession(displayName);
  }

  @Override
  public ChaosDiagnostics diagnostics() {
    return delegate.diagnostics();
  }

  @Override
  public void addEventListener(final ChaosEventListener listener) {
    delegate.addEventListener(listener);
  }

  @Override
  public void close() {
    // See class javadoc: delegate is JVM-wide; closing it would affect other tests. The extension
    // relies exclusively on stopTracked() to release handles issued during this test.
  }

  private void track(final ChaosActivationHandle handle) {
    synchronized (handles) {
      handles.addLast(handle);
    }
  }

  /**
   * Stops and discards every handle issued through this wrapper, in reverse registration order.
   * Exceptions from individual {@code stop()} calls are logged and suppressed so one bad handle
   * cannot block cleanup of the rest.
   */
  public void stopTracked() {
    // Drain in a loop rather than taking one snapshot. A concurrent activate() call between
    // the snapshot and handles.clear() would add a handle that is already in 'handles' but not
    // in the snapshot's iterator — that handle would be silently orphaned for the rest of the
    // JVM lifetime. Looping until the deque stays empty after clear() guarantees every handle
    // registered before the deque is lastly observed empty is stopped.
    while (true) {
      final ArrayDeque<ChaosActivationHandle> snapshot;
      synchronized (handles) {
        if (handles.isEmpty()) {
          return;
        }
        snapshot = new ArrayDeque<>(handles);
        handles.clear();
      }
      stopHandlesInReverseOrder(snapshot);
    }
  }

  private void stopHandlesInReverseOrder(final ArrayDeque<ChaosActivationHandle> snapshot) {
    final Iterator<ChaosActivationHandle> reverseIterator = snapshot.descendingIterator();
    while (reverseIterator.hasNext()) {
      stopHandleSafely(reverseIterator.next());
    }
  }

  private void stopHandleSafely(final ChaosActivationHandle handle) {
    try {
      handle.stop();
    } catch (final RuntimeException exception) {
      LOGGER.log(
          Level.WARNING,
          exception,
          () -> "chaos-agent: failed to stop JVM-scoped handle during test teardown");
    }
  }
}

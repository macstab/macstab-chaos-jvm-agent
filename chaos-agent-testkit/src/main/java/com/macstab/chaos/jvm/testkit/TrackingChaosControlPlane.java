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
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Test-scoped decorator around a JVM-wide {@link ChaosControlPlane} that tracks every handle
 * returned from {@link #activate(ChaosScenario)} / {@link #activate(ChaosPlan)}, and by default
 * every session opened and every event listener added through it, so the JUnit extension can
 * release them all when a test finishes.
 *
 * <p>The underlying control plane is a JVM-wide singleton (see {@code
 * com.macstab.chaos.jvm.bootstrap.ChaosAgentBootstrap.RUNTIME}); closing it would kill every other
 * test's instrumentation. The wrapper therefore does not close the delegate — it only releases what
 * was activated, opened or added through it. JVM-scoped scenarios that leak across tests are the
 * single most common source of test-suite flakiness in chaos-driven test fleets, so auto-cleanup at
 * the extension level is load-bearing.
 *
 * <p>{@link #close()} is intentionally a no-op. Tests that want the full JVM-wide control plane
 * should inject it themselves via {@link
 * com.macstab.chaos.jvm.bootstrap.ChaosPlatform#installLocally()} — at that point the caller owns
 * the JVM-wide state and the extension is out of the picture.
 */
public final class TrackingChaosControlPlane implements ChaosControlPlane {

  private static final Logger LOGGER = Logger.getLogger(TrackingChaosControlPlane.class.getName());

  private final ChaosControlPlane delegate;
  private final boolean trackListenersAndSessions;
  private final Deque<ChaosActivationHandle> handles = new ArrayDeque<>();
  private final Deque<ChaosSession> sessions = new ArrayDeque<>();
  private final Deque<ChaosEventListener> listeners = new ArrayDeque<>();

  /**
   * Wraps the given JVM-wide control plane so every handle issued, session opened and event
   * listener added through this instance is released by {@link #stopTracked() stopTracked}. The
   * delegate itself is never closed.
   *
   * @param delegate the JVM-wide control plane to decorate; must not be {@code null}
   * @throws NullPointerException if {@code delegate} is {@code null}
   */
  public TrackingChaosControlPlane(final ChaosControlPlane delegate) {
    this(delegate, true);
  }

  /**
   * Wraps the given JVM-wide control plane so every handle issued through this instance is released
   * by {@link #stopTracked() stopTracked}; sessions and event listeners too when {@code
   * trackListenersAndSessions} is set. The delegate itself is never closed.
   *
   * <p>A plane handed to an application (a Spring, Micronaut or Quarkus bean) passes {@code false}:
   * the application's listeners and sessions belong to the application, not to a test.
   *
   * @param delegate the JVM-wide control plane to decorate; must not be {@code null}
   * @param trackListenersAndSessions whether {@link #stopTracked()} also closes the sessions opened
   *     and removes the listeners added through this instance
   * @throws NullPointerException if {@code delegate} is {@code null}
   */
  public TrackingChaosControlPlane(
      final ChaosControlPlane delegate, final boolean trackListenersAndSessions) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.trackListenersAndSessions = trackListenersAndSessions;
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
    final ChaosSession session = delegate.openSession(displayName);
    if (trackListenersAndSessions) {
      synchronized (handles) {
        sessions.addLast(session);
      }
    }
    return session;
  }

  @Override
  public ChaosDiagnostics diagnostics() {
    return delegate.diagnostics();
  }

  @Override
  public void addEventListener(final ChaosEventListener listener) {
    delegate.addEventListener(listener);
    if (trackListenersAndSessions) {
      synchronized (handles) {
        listeners.addLast(listener);
      }
    }
  }

  @Override
  public boolean removeEventListener(final ChaosEventListener listener) {
    // A listener the test removed itself is no longer released by stopTracked().
    synchronized (handles) {
      listeners.removeFirstOccurrence(listener);
    }
    return delegate.removeEventListener(listener);
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
   * Stops and discards every handle issued through this wrapper, in reverse registration order;
   * then, when sessions and listeners are tracked, closes the sessions opened and removes the
   * listeners added through it, so the listeners see the scenarios stop. A failure to release one
   * is logged as a {@code WARNING} and does not stop the release of the rest.
   */
  public void stopTracked() {
    // Drain in a loop rather than taking one snapshot. A concurrent activate() call between
    // the snapshot and handles.clear() would add a handle that is already in 'handles' but not
    // in the snapshot's iterator — that handle would be silently orphaned for the rest of the
    // JVM lifetime. Looping until the deques stay empty after clear() guarantees everything
    // registered before they are lastly observed empty is released.
    while (true) {
      final ArrayDeque<ChaosActivationHandle> handleSnapshot;
      final ArrayDeque<ChaosSession> sessionSnapshot;
      final ArrayDeque<ChaosEventListener> listenerSnapshot;
      synchronized (handles) {
        if (handles.isEmpty() && sessions.isEmpty() && listeners.isEmpty()) {
          return;
        }
        handleSnapshot = new ArrayDeque<>(handles);
        sessionSnapshot = new ArrayDeque<>(sessions);
        listenerSnapshot = new ArrayDeque<>(listeners);
        handles.clear();
        sessions.clear();
        listeners.clear();
      }
      final Iterator<ChaosActivationHandle> reverseHandles = handleSnapshot.descendingIterator();
      while (reverseHandles.hasNext()) {
        final ChaosActivationHandle handle = reverseHandles.next();
        // close(), not stop(): close() also removes the scenario from the registry, so the next
        // test can activate the same id and diagnostics do not accumulate stopped entries.
        releaseSafely(handle::close, "stop a JVM-scoped handle");
      }
      final Iterator<ChaosSession> reverseSessions = sessionSnapshot.descendingIterator();
      while (reverseSessions.hasNext()) {
        releaseSafely(reverseSessions.next()::close, "close a session");
      }
      for (final ChaosEventListener listener : listenerSnapshot) {
        releaseSafely(() -> delegate.removeEventListener(listener), "remove an event listener");
      }
    }
  }

  private static void releaseSafely(final Runnable release, final String what) {
    try {
      release.run();
    } catch (final RuntimeException exception) {
      LOGGER.log(
          Level.WARNING,
          exception,
          () -> "[chaos-agent] failed to " + what + " during test teardown");
    }
  }

  @Override
  public ChaosSession.ScopeBinding bindSession(final @Nullable String sessionId) {
    return delegate.bindSession(sessionId);
  }
}

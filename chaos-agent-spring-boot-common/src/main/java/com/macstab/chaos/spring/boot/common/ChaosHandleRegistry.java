package com.macstab.chaos.spring.boot.common;

import com.macstab.chaos.api.ChaosActivationHandle;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.DisposableBean;

/**
 * Tracks {@link ChaosActivationHandle} instances returned by runtime-triggered activations so that
 * the Actuator endpoint can stop them by scenario ID without exposing core-internal registry APIs.
 *
 * <p>Implements {@link DisposableBean} so handles registered against this bean are stopped when the
 * enclosing Spring context closes. The underlying {@link com.macstab.chaos.api.ChaosControlPlane}
 * is a JVM-wide singleton and is intentionally <b>not</b> closed here — only the handles owned by
 * this context are released.
 */
public final class ChaosHandleRegistry implements DisposableBean {

  private static final Logger LOGGER = Logger.getLogger(ChaosHandleRegistry.class.getName());

  private final Map<String, ChaosActivationHandle> handles = new ConcurrentHashMap<>();

  // Set to true by stopAll() / destroy() so that register() calls racing the shutdown are
  // short-circuited. Without the flag, a register() that happens just after stopAll() has
  // drained the map inserts a new entry that stopAll() has already moved past, and the
  // handle leaks past context close. Worse, stopAll()'s "drain until empty" loop is not
  // formally bounded: a misbehaving caller (e.g., a test fixture holding the Actuator
  // endpoint open and re-triggering activations in response to events) can livelock the
  // shutdown. The flag converts the race into a deterministic "stop the newly-registered
  // handle immediately and do not track it", making teardown bounded by the size of the
  // map at the moment closing was observed.
  private volatile boolean closing = false;

  /** Default constructor invoked by Spring when the bean is created. */
  public ChaosHandleRegistry() {}

  /**
   * Records a newly-activated handle so it can be stopped later by ID.
   *
   * <p>If a handle with the same ID was already registered, the previous handle is stopped before
   * being replaced. Without this, a caller who triggered two activations of the same scenario via
   * the Actuator endpoint would silently leak the first handle and could not stop it via {@link
   * #stop(String)} afterward.
   *
   * <p>If the registry is already closing (i.e. {@link #destroy()} / {@link #stopAll()} has begun),
   * the newly registered handle is stopped immediately instead of being tracked, so it cannot leak
   * past the context teardown window.
   *
   * @param handle the handle returned by {@code activate(...)}; must not be null
   */
  public void register(final ChaosActivationHandle handle) {
    if (closing) {
      // Context is tearing down — track would leak the handle past stopAll()'s drain.
      try {
        handle.stop();
      } catch (final RuntimeException exception) {
        LOGGER.log(
            Level.WARNING,
            exception,
            () -> "chaos-agent: failed to stop handle registered during shutdown: " + handle.id());
      }
      return;
    }
    final ChaosActivationHandle previous = handles.put(handle.id(), handle);
    if (previous != null && previous != handle) {
      try {
        previous.stop();
      } catch (final RuntimeException exception) {
        LOGGER.log(
            Level.WARNING,
            exception,
            () -> "chaos-agent: failed to stop previous handle for id=" + handle.id());
      }
    }
    // Re-check closing after the put: stopAll() may have drained the map in the window
    // between our initial closing==false read (above) and the put. If it has, the handle we
    // just inserted will never be drained — stopAll() has already exited. Remove it ourselves
    // and stop it inline, matching the short-circuit path above.
    if (closing && handles.remove(handle.id(), handle)) {
      try {
        handle.stop();
      } catch (final RuntimeException exception) {
        LOGGER.log(
            Level.WARNING,
            exception,
            () -> "chaos-agent: failed to stop late-racing handle for id=" + handle.id());
      }
    }
  }

  /**
   * Stops and removes the handle with the given ID, if any.
   *
   * @param id the handle identifier as returned by {@link ChaosActivationHandle#id()}
   * @return {@code true} if a handle was stopped; {@code false} if no handle matched
   */
  public boolean stop(final String id) {
    final ChaosActivationHandle handle = handles.remove(id);
    if (handle == null) {
      return false;
    }
    try {
      handle.stop();
      return true;
    } catch (final RuntimeException stopFailure) {
      // handle.stop() can throw from stressor teardown (interrupted joins, SecurityException on
      // thread-group access, IOException-wrapped errors during resource release). Without this
      // catch, the exception propagates through the Actuator HTTP handler into Spring MVC's
      // default error rendering, producing a 500 with internal stack frames in the response body
      // — an information-disclosure channel. The handle has already been removed from the map,
      // so returning false here keeps the registry consistent: the handle is no longer tracked
      // and cannot be stopped again through this registry regardless of the stop() outcome.
      LOGGER.log(Level.WARNING, stopFailure, () -> "chaos-agent: stop failed for handle id=" + id);
      return false;
    }
  }

  /**
   * Returns a read-only view of the handle identifiers currently tracked.
   *
   * @return an unmodifiable collection of handle IDs
   */
  public Collection<String> ids() {
    return Collections.unmodifiableSet(handles.keySet());
  }

  /**
   * Stops and forgets every tracked handle. Safe to call multiple times.
   *
   * @return the number of handles that were stopped successfully
   */
  public int stopAll() {
    // Flip the closing flag FIRST so any concurrent register() calls short-circuit and stop
    // their handles inline instead of racing our drain. Writes to the map after this point
    // come only from threads that observed closing=false before this write — those are
    // bounded by the current map contents plus the in-flight register() calls already
    // past the check, which themselves iterate on map entries we will observe in the pass.
    closing = true;
    int count = 0;
    // Two-pass bounded drain. After closing=true, register() calls that already passed the
    // closing==false check can still insert into the map — at most one batch before they
    // observe closing=true and stop inline. Two snapshot passes are sufficient: pass 0 drains
    // everything present at the time closing is set; pass 1 catches the bounded set of inserts
    // that slipped through the closing guard between our volatile write and pass 0's snapshot.
    for (int pass = 0; pass < 2; pass++) {
      for (final String key : java.util.List.copyOf(handles.keySet())) {
        final ChaosActivationHandle handle = handles.remove(key);
        if (handle == null) {
          continue;
        }
        try {
          handle.stop();
          count++;
        } catch (final RuntimeException ignored) {
          // best-effort stop-all
        }
      }
    }
    return count;
  }

  /**
   * Invoked by Spring when the enclosing context closes. Delegates to {@link #stopAll()} so that
   * handles created by this context (via the Actuator endpoint or {@code chaosStartupApplier}) do
   * not leak into subsequent contexts.
   */
  @Override
  public void destroy() {
    stopAll();
  }
}

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
   * @param handle the handle returned by {@code activate(...)}; must not be null
   */
  public void register(final ChaosActivationHandle handle) {
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
    int count = 0;
    // Use a key+value CAS remove so a concurrent register() that replaces the same id with a new
    // handle is preserved instead of being silently dropped by a blind remove(key). Iterate over
    // the live entrySet (not a snapshot) so a plain iterator.remove() gives us atomic per-entry
    // semantics backed by ConcurrentHashMap.
    final var iterator = handles.entrySet().iterator();
    while (iterator.hasNext()) {
      final Map.Entry<String, ChaosActivationHandle> entry = iterator.next();
      final ChaosActivationHandle handle = entry.getValue();
      if (!handles.remove(entry.getKey(), handle)) {
        // A concurrent register() swapped this entry; leave the replacement alone.
        continue;
      }
      try {
        handle.stop();
        count++;
      } catch (final RuntimeException ignored) {
        // best-effort stop-all
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

package com.macstab.chaos.spring.boot.common;

import com.macstab.chaos.api.ChaosActivationHandle;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks {@link ChaosActivationHandle} instances returned by runtime-triggered activations so that
 * the Actuator endpoint can stop them by scenario ID without exposing core-internal registry APIs.
 */
public final class ChaosHandleRegistry {

  private final Map<String, ChaosActivationHandle> handles = new ConcurrentHashMap<>();

  /** Default constructor invoked by Spring when the bean is created. */
  public ChaosHandleRegistry() {}

  /**
   * Records a newly-activated handle so it can be stopped later by ID.
   *
   * @param handle the handle returned by {@code activate(...)}; must not be null
   */
  public void register(final ChaosActivationHandle handle) {
    handles.put(handle.id(), handle);
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
    handle.stop();
    return true;
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
    for (final Map.Entry<String, ChaosActivationHandle> entry : handles.entrySet()) {
      handles.remove(entry.getKey());
      try {
        entry.getValue().stop();
        count++;
      } catch (final RuntimeException ignored) {
        // best-effort stop-all
      }
    }
    return count;
  }
}

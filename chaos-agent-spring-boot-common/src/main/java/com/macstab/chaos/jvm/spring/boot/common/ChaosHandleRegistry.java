package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.DisposableBean;

/**
 * Holds the handles of plans the starter activates for the application (the startup plan), so they
 * stop when the application context closes.
 *
 * <p>Implements {@link DisposableBean} so handles registered against this bean are stopped when the
 * enclosing Spring context closes. The underlying {@link
 * com.macstab.chaos.jvm.api.ChaosControlPlane} is a JVM-wide singleton and is intentionally
 * <b>not</b> closed here — only the handles owned by this context are released.
 */
public final class ChaosHandleRegistry implements DisposableBean {

  private static final Logger LOGGER = Logger.getLogger(ChaosHandleRegistry.class.getName());

  private final Map<String, ChaosActivationHandle> handles = new ConcurrentHashMap<>();

  // Set by destroy(): a register() racing the shutdown stops its handle itself instead of
  // inserting it after the drain, so no handle outlives the context.
  private volatile boolean closing = false;

  /** Default constructor invoked by Spring when the bean is created. */
  public ChaosHandleRegistry() {}

  /**
   * Records a newly-activated handle so it stops with the context. A handle registered under an id
   * already held replaces the previous one, which is stopped; once the context is closing, the
   * handle is stopped at once instead.
   *
   * @param handle the handle returned by {@code activate(...)}; must not be null
   */
  public void register(final ChaosActivationHandle handle) {
    if (closing) {
      // Context is tearing down — track would leak the handle past stopAll()'s drain.
      try {
        handle.close();
      } catch (final RuntimeException exception) {
        LOGGER.log(
            Level.WARNING,
            exception,
            () -> "chaos-agent: failed to stop handle registered during shutdown: " + handle.id());
      }
      return;
    }
    final ChaosActivationHandle previous = handles.put(handle.id(), handle);
    if (previous != null && !previous.equals(handle)) {
      try {
        previous.close();
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
        handle.close();
      } catch (final RuntimeException exception) {
        LOGGER.log(
            Level.WARNING,
            exception,
            () -> "chaos-agent: failed to stop late-racing handle for id=" + handle.id());
      }
    }
  }

  /** Stops every handle and refuses further registrations; called when the context closes. */
  @Override
  public void destroy() {
    closing = true;
    // Two passes: a register() that read closing=false before it was set may still insert once.
    for (int pass = 0; pass < 2; pass++) {
      for (final var entries = handles.values().iterator(); entries.hasNext(); ) {
        final ChaosActivationHandle handle = entries.next();
        entries.remove();
        try {
          handle.close();
        } catch (final RuntimeException stopFailure) {
          LOGGER.log(
              Level.WARNING,
              stopFailure,
              () -> "chaos-agent: stop failed for handle id=" + handle.id());
        }
      }
    }
  }
}

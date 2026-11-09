package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <b>not</b> closed here — only the handles owned by this context are released. A startup plan is
 * held through {@link #activateShared}: contexts with the same plan share one activation, which
 * stops when the last of them closes.
 */
public final class ChaosHandleRegistry implements DisposableBean {

  private static final Logger LOGGER = Logger.getLogger(ChaosHandleRegistry.class.getName());

  /**
   * The startup plans active in the JVM, by their canonical JSON ({@link ChaosPlanMapper#write}, so
   * whitespace in the file does not matter), with the number of contexts holding each. JVM-wide:
   * the plans run on the JVM-wide control plane.
   */
  private static final Map<String, SharedPlan> SHARED_PLANS = new HashMap<>();

  private final Map<String, ChaosActivationHandle> handles = new ConcurrentHashMap<>();

  /** The entries of {@link #SHARED_PLANS} this context holds; guarded by {@link #SHARED_PLANS}. */
  private final Set<SharedPlan> sharedPlans = new HashSet<>();

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
            () -> "[chaos-agent] failed to stop handle registered during shutdown: " + handle.id());
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
            () -> "[chaos-agent] failed to stop previous handle for id=" + handle.id());
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
            () -> "[chaos-agent] failed to stop late-racing handle for id=" + handle.id());
      }
    }
  }

  /**
   * Activates {@code plan} on {@code controlPlane}, or joins the activation of the same plan (equal
   * canonical JSON) that another context in the JVM holds. A Spring test suite caches several
   * contexts with one startup plan file; the plan runs once and stops when the last context holding
   * it closes.
   *
   * @param controlPlane the JVM-wide control plane
   * @param plan the plan
   * @return the plan's handle, shared by every context holding the plan
   * @throws IllegalStateException if this context is closing
   * @throws RuntimeException what the activation throws; nothing is held then
   */
  public ChaosActivationHandle activateShared(
      final ChaosControlPlane controlPlane, final ChaosPlan plan) {
    final String key = ChaosPlanMapper.write(plan);
    synchronized (SHARED_PLANS) {
      if (closing) {
        throw new IllegalStateException("the application context is closing");
      }
      SharedPlan shared = SHARED_PLANS.get(key);
      if (shared == null) {
        shared = new SharedPlan(key, controlPlane.activate(plan));
        SHARED_PLANS.put(key, shared);
      }
      if (sharedPlans.add(shared)) {
        shared.holders++;
      }
      return shared.handle;
    }
  }

  /** Stops every handle and refuses further registrations; called when the context closes. */
  @Override
  public void destroy() {
    closing = true;
    releaseSharedPlans();
    // Two passes: a register() that read closing=false before it was set may still insert once.
    for (int pass = 0; pass < 2; pass++) {
      for (final var entries = handles.values().iterator(); entries.hasNext(); ) {
        final ChaosActivationHandle handle = entries.next();
        entries.remove();
        stop(handle);
      }
    }
  }

  /** Drops this context's hold on its shared plans, stopping those no other context holds. */
  private void releaseSharedPlans() {
    final List<ChaosActivationHandle> unheld = new ArrayList<>();
    synchronized (SHARED_PLANS) {
      for (final SharedPlan shared : sharedPlans) {
        if (--shared.holders == 0) {
          SHARED_PLANS.remove(shared.key);
          unheld.add(shared.handle);
        }
      }
      sharedPlans.clear();
    }
    unheld.forEach(ChaosHandleRegistry::stop);
  }

  private static void stop(final ChaosActivationHandle handle) {
    try {
      handle.close();
    } catch (final RuntimeException stopFailure) {
      LOGGER.log(
          Level.WARNING,
          stopFailure,
          () -> "[chaos-agent] stop failed for handle id=" + handle.id());
    }
  }

  /** A plan active for one or more contexts; guarded by {@link #SHARED_PLANS}. */
  private static final class SharedPlan {
    private final String key;
    private final ChaosActivationHandle handle;
    private int holders;

    private SharedPlan(final String key, final ChaosActivationHandle handle) {
      this.key = key;
      this.handle = handle;
    }
  }
}

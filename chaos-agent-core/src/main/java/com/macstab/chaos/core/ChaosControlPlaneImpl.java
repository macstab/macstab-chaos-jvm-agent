package com.macstab.chaos.core;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationException;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosEventListener;
import com.macstab.chaos.api.ChaosMetricsSink;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.api.ChaosUnsupportedFeatureException;
import java.lang.instrument.Instrumentation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control-plane half of the chaos runtime. Owns scenario lifecycle, session management,
 * diagnostics, event listeners, instrumentation handle, and the shutdown-hook tracking map. Paired
 * with {@link ChaosDispatcher} which owns the hot-path interception logic.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are fully thread-safe. Shared mutable state uses {@code volatile}, {@link
 * java.util.concurrent.ConcurrentHashMap}, and CAS primitives.
 */
final class ChaosControlPlaneImpl implements ChaosControlPlane {
  private final Clock clock;
  private final FeatureSet featureSet;
  private final ScopeContext scopeContext;
  private final ObservabilityBus observabilityBus;
  private final ScenarioRegistry registry;
  private final Map<Thread, Thread> shutdownHooks = new ConcurrentHashMap<>();
  private volatile Optional<Instrumentation> instrumentation = Optional.empty();

  ChaosControlPlaneImpl(final Clock clock, final ChaosMetricsSink metricsSink) {
    this.clock = clock;
    this.featureSet = new FeatureSet();
    this.scopeContext = new ScopeContext();
    this.observabilityBus = new ObservabilityBus(metricsSink);
    this.registry = new ScenarioRegistry(clock, this::runtimeDetails);
  }

  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    return registerScenario(scenario, "jvm", null);
  }

  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    // A plan is atomic: either every scenario is registered or none are. If the 4th of 5
    // scenarios fails validation we must undo the first 3, otherwise the plan is silently
    // partially applied and the operator has no way to tell apart "this plan is active" from
    // "part of this plan is active". Roll back by stopping and destroying every successfully
    // registered handle before propagating the original exception.
    final List<ChaosActivationHandle> handles = new ArrayList<>();
    try {
      for (final ChaosScenario scenario : plan.scenarios()) {
        if (scenario.scope() != ChaosScenario.ScenarioScope.JVM) {
          throw new ChaosActivationException(
              "startup/global activation cannot register session-scoped scenario " + scenario.id());
        }
        handles.add(activate(scenario));
      }
      return new CompositeActivationHandle("plan:" + plan.metadata().name(), handles);
    } catch (final RuntimeException failure) {
      for (final ChaosActivationHandle handle : handles) {
        try {
          // activate(ChaosScenario) on this class returns a DefaultChaosActivationHandle, and the
          // plan-rollback list is populated exclusively by that path (see line 70 above). Only
          // destroy() unregisters the controller from the ScenarioRegistry; stop() alone would
          // leave ghost STOPPED entries in diagnostics and block re-activation under the same
          // id. Treat a non-default handle as a programmer error — it means this rollback list
          // was populated from outside the expected path and the rollback contract has been
          // broken.
          if (!(handle instanceof DefaultChaosActivationHandle defaultHandle)) {
            throw new IllegalStateException(
                "plan rollback received unexpected handle type: " + handle.getClass().getName());
          }
          defaultHandle.destroy();
        } catch (final RuntimeException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
      }
      throw failure;
    }
  }

  @Override
  public ChaosSession openSession(final String displayName) {
    // A session only needs {@code activateInSession} to register session-scoped scenarios; that
    // method lives on this control plane, so the session can hold a direct reference instead of
    // bouncing through the ChaosRuntime facade. This removes the UnsupportedOperationException
    // trap that used to sit here for any caller who got to the control plane without going
    // through ChaosRuntime.
    return new DefaultChaosSession(displayName, scopeContext, this);
  }

  ChaosSession openSession(final String displayName, final ChaosRuntime runtime) {
    return openSession(displayName);
  }

  @Override
  public ChaosDiagnostics diagnostics() {
    return registry;
  }

  @Override
  public void addEventListener(final ChaosEventListener listener) {
    Objects.requireNonNull(listener, "listener");
    observabilityBus.addListener(listener);
  }

  @Override
  public void close() {
    registry.controllers().forEach(ScenarioController::destroy);
  }

  void setInstrumentation(final Instrumentation inst) {
    this.instrumentation = Optional.of(Objects.requireNonNull(inst, "inst"));
  }

  Optional<Instrumentation> instrumentation() {
    return instrumentation;
  }

  DefaultChaosActivationHandle activateInSession(
      final DefaultChaosSession session, final ChaosScenario scenario) {
    if (scenario.scope() != ChaosScenario.ScenarioScope.SESSION) {
      throw new ChaosActivationException(
          "session activation requires scenario scope SESSION for " + scenario.id());
    }
    return registerScenario(scenario, "session:" + session.id(), session.id());
  }

  ScenarioRegistry registry() {
    return registry;
  }

  ScopeContext scopeContext() {
    return scopeContext;
  }

  FeatureSet featureSet() {
    return featureSet;
  }

  ObservabilityBus observabilityBus() {
    return observabilityBus;
  }

  Map<Thread, Thread> shutdownHooks() {
    return shutdownHooks;
  }

  Clock clock() {
    return clock;
  }

  private DefaultChaosActivationHandle registerScenario(
      final ChaosScenario scenario, final String scopeKey, final String sessionId) {
    ScenarioController controller = null;
    boolean registered = false;
    try {
      CompatibilityValidator.validate(scenario, featureSet);
      controller =
          new ScenarioController(
              scenario, scopeKey, sessionId, clock, observabilityBus, () -> instrumentation);
      registry.register(controller);
      registered = true;
      final DefaultChaosActivationHandle handle =
          new DefaultChaosActivationHandle(controller, registry);
      if (scenario.activationPolicy().startMode() == ActivationPolicy.StartMode.AUTOMATIC) {
        // handle.start() can throw mid-way through start-up (stressor constructors that allocate
        // eagerly, ClockSkewState overflow, etc.). If that happens, the controller has already
        // been registered and has already flipped itself to ACTIVE inside start() — if we leave
        // it, it will match on the hot dispatch path forever with no handle to stop it. Roll the
        // registration back before rethrowing so no orphan remains.
        handle.start();
      }
      return handle;
    } catch (final ChaosUnsupportedFeatureException unsupported) {
      unregisterAndDestroyQuietly(controller, registered);
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME,
          unsupported.getMessage());
      throw unsupported;
    } catch (final IllegalStateException stateException) {
      unregisterAndDestroyQuietly(controller, registered);
      final ChaosDiagnostics.FailureCategory category =
          stateException.getMessage() != null
                  && stateException.getMessage().contains("already active")
              ? ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT
              : ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION;
      registry.recordFailure(scenario.id(), category, stateException.getMessage());
      throw stateException;
    } catch (final RuntimeException runtimeException) {
      unregisterAndDestroyQuietly(controller, registered);
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          runtimeException.getMessage());
      throw runtimeException;
    }
  }

  private void unregisterAndDestroyQuietly(
      final ScenarioController controller, final boolean registered) {
    if (controller == null) {
      return;
    }
    if (registered) {
      try {
        registry.unregister(controller);
      } catch (final RuntimeException ignored) {
        // Best-effort rollback: a failure here must not mask the original exception reported
        // by the caller.
      }
    }
    try {
      controller.destroy();
    } catch (final RuntimeException ignored) {
      // Same rationale as above — swallow so the original failure propagates intact.
    }
  }

  private Map<String, String> runtimeDetails() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("jdkFeatureVersion", Integer.toString(featureSet.runtimeFeatureVersion()));
    details.put("virtualThreadsSupported", Boolean.toString(featureSet.supportsVirtualThreads()));
    details.put("jfrSupported", Boolean.toString(featureSet.jfrSupported()));
    details.put("currentSessionId", String.valueOf(scopeContext.currentSessionId()));
    return details;
  }
}

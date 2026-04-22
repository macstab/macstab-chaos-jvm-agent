package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import java.lang.instrument.Instrumentation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
  private final AtomicBoolean closed = new AtomicBoolean(false);
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
    // Idempotent close: a second call must not re-traverse the controllers and re-publish
    // STOPPED lifecycle events. Framework integrations (Spring ApplicationContext teardown +
    // JUnit @AfterAll, Quarkus shutdown + main-thread close, test suites asserting exact event
    // counts) frequently call close() more than once; without this guard every controller fires
    // a second STOPPED event via its ObservabilityBus on the repeat pass.
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    // Destroy *and* unregister each controller. destroy() alone only calls stop(); it does not
    // remove the controller from the registry. ScenarioRegistry.register() uses putIfAbsent and
    // throws on re-registration of the same id, so a ChaosRuntime that is closed and then re-opened
    // (Spring ContextRefreshed cycles, test-suite reinit, JVM-wide recreate) would see every
    // previously-registered scenario id fail to reactivate. Snapshot the controllers into a list
    // first — unregister() mutates the registry, so iterating the live view and mutating
    // concurrently risks ConcurrentModificationException from non-ConcurrentMap backing.
    final List<ScenarioController> toUnregister = new ArrayList<>(registry.controllers());
    for (final ScenarioController controller : toUnregister) {
      try {
        controller.destroy();
      } finally {
        registry.unregister(controller);
      }
    }
    // Close AutoCloseable listeners (e.g. JfrChaosEventSink's FlightRecorder periodic hook).
    // Previously those hooks were never removed, so every new ChaosRuntime accumulated a stale
    // FlightRecorder entry pinning dead runtimes — a silent JVM-wide leak that escalated in
    // test suites spinning up multiple runtimes.
    observabilityBus.close();
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
    boolean registeredInRegistry = false;
    try {
      CompatibilityValidator.validate(scenario, featureSet);
      controller =
          new ScenarioController(
              scenario, scopeKey, sessionId, clock, observabilityBus, () -> instrumentation);
      final DefaultChaosActivationHandle handle =
          new DefaultChaosActivationHandle(controller, registry);
      if (scenario.activationPolicy().startMode() == ActivationPolicy.StartMode.AUTOMATIC) {
        // Start before registering: the controller is not yet visible to evaluate() on the hot
        // dispatch path. If start() throws (stressor OOM, ClockSkewState overflow, listener
        // exception), the controller never enters the registry and the rollback catch below skips
        // the unregister call (registeredInRegistry=false). The only downside is a brief gap
        // between start and registration — in that window STARTED lifecycle events fire but
        // diagnostics does not yet show the scenario. This is a tolerable ordering artefact.
        handle.start();
      }
      registry.register(controller);
      registeredInRegistry = true;
      return handle;
    } catch (final ChaosUnsupportedFeatureException unsupported) {
      unregisterAndDestroyQuietly(controller, registeredInRegistry);
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME,
          unsupported.getMessage());
      throw unsupported;
    } catch (final IllegalStateException stateException) {
      unregisterAndDestroyQuietly(controller, registeredInRegistry);
      final ChaosDiagnostics.FailureCategory category =
          stateException.getMessage() != null
                  && stateException.getMessage().contains("already active")
              ? ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT
              : ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION;
      registry.recordFailure(scenario.id(), category, stateException.getMessage());
      throw stateException;
    } catch (final RuntimeException runtimeException) {
      unregisterAndDestroyQuietly(controller, registeredInRegistry);
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          runtimeException.getMessage());
      throw runtimeException;
    }
  }

  private void unregisterAndDestroyQuietly(
      final ScenarioController controller, final boolean wasRegistered) {
    if (controller == null) {
      return;
    }
    if (wasRegistered) {
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
    final Map<String, String> details = new LinkedHashMap<>();
    details.put("jdkFeatureVersion", Integer.toString(featureSet.runtimeFeatureVersion()));
    details.put("virtualThreadsSupported", Boolean.toString(featureSet.supportsVirtualThreads()));
    details.put("jfrSupported", Boolean.toString(featureSet.jfrSupported()));
    details.put("currentSessionId", String.valueOf(scopeContext.currentSessionId()));
    return details;
  }
}

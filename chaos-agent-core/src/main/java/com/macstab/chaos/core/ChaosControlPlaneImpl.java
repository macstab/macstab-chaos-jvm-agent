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
    final List<ChaosActivationHandle> handles = new ArrayList<>();
    for (final ChaosScenario scenario : plan.scenarios()) {
      if (scenario.scope() != ChaosScenario.ScenarioScope.JVM) {
        throw new ChaosActivationException(
            "startup/global activation cannot register session-scoped scenario " + scenario.id());
      }
      handles.add(activate(scenario));
    }
    return new CompositeActivationHandle("plan:" + plan.metadata().name(), handles);
  }

  @Override
  public ChaosSession openSession(final String displayName) {
    throw new UnsupportedOperationException(
        "openSession is routed through the ChaosRuntime facade");
  }

  ChaosSession openSession(final String displayName, final ChaosRuntime runtime) {
    return new DefaultChaosSession(displayName, scopeContext, runtime);
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
    try {
      CompatibilityValidator.validate(scenario, featureSet);
      final ScenarioController controller =
          new ScenarioController(
              scenario, scopeKey, sessionId, clock, observabilityBus, () -> instrumentation);
      registry.register(controller);
      final DefaultChaosActivationHandle handle =
          new DefaultChaosActivationHandle(controller, registry);
      if (scenario.activationPolicy().startMode() == ActivationPolicy.StartMode.AUTOMATIC) {
        handle.start();
      }
      return handle;
    } catch (final ChaosUnsupportedFeatureException unsupported) {
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME,
          unsupported.getMessage());
      throw unsupported;
    } catch (final IllegalStateException stateException) {
      final ChaosDiagnostics.FailureCategory category =
          stateException.getMessage() != null
                  && stateException.getMessage().contains("already active")
              ? ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT
              : ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION;
      registry.recordFailure(scenario.id(), category, stateException.getMessage());
      throw stateException;
    } catch (final RuntimeException runtimeException) {
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          runtimeException.getMessage());
      throw runtimeException;
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

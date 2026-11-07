package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationConflictException;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.instrument.Instrumentation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Control-plane half of the chaos runtime. Owns scenario lifecycle, session management,
 * diagnostics, event listeners and the instrumentation handle. Paired with {@link ChaosDispatcher}
 * which owns the hot-path interception logic.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are fully thread-safe. Shared mutable state uses {@code volatile}, {@link
 * java.util.concurrent.ConcurrentHashMap}, and CAS primitives. {@link #close()} and the steps that
 * add a scenario or listener hold {@code lifecycleLock}, so nothing is added once {@code close()}
 * has taken its snapshot; the dispatch path never takes it.
 */
final class ChaosControlPlaneImpl implements ChaosControlPlane {
  private final Clock clock;
  private final FeatureSet featureSet;
  private final ScopeContext scopeContext;
  private final ObservabilityBus observabilityBus;
  private final ScenarioRegistry registry;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Orders {@link #close()} against registering a scenario or adding a listener: either the
   * addition happens first and close() finds it, or it sees the plane closed and is refused.
   */
  private final Object lifecycleLock = new Object();

  /**
   * Which plan's observability settings a scenario id carries: only that owner may clear them, so a
   * rejected or later plan with the same ids cannot strip the settings of the running one.
   */
  private final Map<String, Object> observabilityOwners = new ConcurrentHashMap<>();

  /** Scenarios whose plan set {@code jmxEnabled=false}: JMX does not list or dump them. */
  private final Set<String> jmxHidden = ConcurrentHashMap.newKeySet();

  private volatile Optional<Instrumentation> instrumentation = Optional.empty();

  /**
   * Weaves the hooks a scenario needs before it starts; {@link HookDemand#NONE} without an agent.
   */
  private volatile HookDemand hookDemand = HookDemand.NONE;

  /**
   * Operations whose hooks carry a session to pool threads (task decoration on submit). Woven when
   * a session is opened, so session-scoped scenarios of any operation see work handed to executors.
   */
  private static final Set<OperationType> SESSION_PROPAGATION_OPERATIONS =
      EnumSet.of(
          OperationType.EXECUTOR_SUBMIT,
          OperationType.SCHEDULE_SUBMIT,
          OperationType.THREAD_START,
          OperationType.FORK_JOIN_TASK_RUN,
          // The HTTP client hooks carry the session id to other services (ChaosSession.HEADER).
          OperationType.HTTP_CLIENT_SEND);

  /** Open sessions by id, for {@link #bindSession(String)}. */
  private final Map<String, DefaultChaosSession> openSessions = new ConcurrentHashMap<>();

  ChaosControlPlaneImpl(final Clock clock, final ChaosMetricsSink metricsSink) {
    this.clock = clock;
    this.featureSet = new FeatureSet();
    this.scopeContext = new ScopeContext();
    this.observabilityBus = new ObservabilityBus(metricsSink);
    this.registry = new ScenarioRegistry(clock, this::runtimeDetails);
    // A removed method-selector scenario no longer needs its methods woven. Registration weaves
    // beforehand (see registerScenario), so this only has work to do after a removal.
    registry.onMethodSelectorsChanged(this::weaveMethods);
  }

  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    rejectSessionScope(scenario);
    return registerScenario(scenario, "jvm", null);
  }

  /**
   * Refuses a session-scoped scenario outside a session: with no session to match it would fire for
   * every thread in the JVM.
   */
  private void rejectSessionScope(final ChaosScenario scenario) {
    if (scenario.scope() == ChaosScenario.ScenarioScope.SESSION) {
      final ChaosActivationException failure = outsideSession(scenario);
      recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          failure.getMessage());
      throw failure;
    }
  }

  /**
   * Runs the checks {@link #activate(ChaosScenario)} runs before registering, without registering
   * anything: lets a caller validate a whole plan before it changes the active set.
   *
   * @param scenario the scenario
   * @throws com.macstab.chaos.jvm.api.ChaosActivationException if the scenario is invalid
   * @throws ChaosUnsupportedFeatureException if the runtime cannot run it
   */
  void validate(final ChaosScenario scenario) {
    if (scenario.scope() == ChaosScenario.ScenarioScope.SESSION) {
      throw outsideSession(scenario);
    }
    CompatibilityValidator.validate(scenario, featureSet);
  }

  /**
   * Weaves the hook groups {@code scenario}'s operations need, without activating it. Groups stay
   * woven, as after an activation. A method selector's weaving belongs to its registration and is
   * not done here.
   */
  void requireHooks(final ChaosScenario scenario) {
    try {
      hookDemand.require(SelectorOperations.of(scenario.selector()));
    } catch (final RuntimeException weavingFailure) {
      throw weavingFailed(scenario, weavingFailure);
    }
  }

  /**
   * The failure an activation or {@link #requireHooks} reports when hooks cannot be woven: the
   * API's own exceptions (an unsupported runtime) as they are, anything else as a {@link
   * com.macstab.chaos.jvm.api.ChaosInstrumentationException} with the weaving failure as its cause.
   */
  private static RuntimeException weavingFailed(
      final ChaosScenario scenario, final RuntimeException weavingFailure) {
    if (weavingFailure instanceof com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException
        || weavingFailure instanceof ChaosActivationException) {
      return weavingFailure;
    }
    return new com.macstab.chaos.jvm.api.ChaosInstrumentationException(
        "the hooks of scenario "
            + scenario.id()
            + " could not be woven: "
            + weavingFailure.getMessage(),
        weavingFailure);
  }

  /** Whether the JVM-scoped key of {@code scenarioId} is held by a live controller. */
  boolean isLive(final String scenarioId) {
    return registry.isLive("jvm::" + scenarioId);
  }

  private static ChaosActivationException outsideSession(final ChaosScenario scenario) {
    return new ChaosActivationException(
        "session-scoped scenario "
            + scenario.id()
            + " must be activated on a session (ChaosSession.activate); outside one it"
            + " would apply to every thread");
  }

  /**
   * Applies a plan's {@link ChaosPlan.Observability} to its scenarios: {@code
   * structuredLoggingEnabled=false} stops logging their events, {@code jmxEnabled=false} hides them
   * from JMX. The settings then belong to {@code owner}, replacing an earlier owner's.
   *
   * @param scenarioIds the plan's scenario ids
   * @param observability the plan's settings
   * @param owner the plan (handle, poller) the settings belong to
   */
  void applyPlanObservability(
      final java.util.Collection<String> scenarioIds,
      final ChaosPlan.Observability observability,
      final Object owner) {
    for (final String id : scenarioIds) {
      observabilityOwners.put(id, owner);
      observabilityBus.muteLogging(id, !observability.structuredLoggingEnabled());
      if (observability.jmxEnabled()) {
        jmxHidden.remove(id);
      } else {
        jmxHidden.add(id);
      }
    }
  }

  /**
   * Restores the default observability of scenarios whose settings {@code owner} set; settings of
   * another owner are left alone.
   *
   * @param scenarioIds the scenario ids
   * @param owner the owner that applied them
   */
  void clearPlanObservability(final java.util.Collection<String> scenarioIds, final Object owner) {
    for (final String id : scenarioIds) {
      if (observabilityOwners.remove(id, owner)) {
        observabilityBus.muteLogging(id, false);
        jmxHidden.remove(id);
      }
    }
  }

  /**
   * Returns whether JMX may list and dump the scenario.
   *
   * @param scenarioId the scenario id
   * @return {@code false} when its plan set {@code jmxEnabled=false}
   */
  boolean jmxVisible(final String scenarioId) {
    return !jmxHidden.contains(scenarioId);
  }

  /**
   * Returns the debug dump restricted to scenarios {@code include} accepts.
   *
   * @param include whether a scenario id is part of the dump
   * @return the dump
   */
  String debugDump(final java.util.function.Predicate<String> include) {
    return registry.debugDump(include);
  }

  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    return activatePlan(
        plan,
        "jvm",
        "plan:",
        this::validate,
        this::activate,
        handle -> {
          // Only destroy() unregisters a controller; stop() alone would leave it registered and
          // block re-activating its id. activate(ChaosScenario) returns a
          // DefaultChaosActivationHandle, so any other type means the contract is broken.
          if (!(handle instanceof DefaultChaosActivationHandle defaultHandle)) {
            throw new IllegalStateException(
                "plan rollback received unexpected handle type: " + handle.getClass().getName());
          }
          defaultHandle.destroy();
        });
  }

  /**
   * Activates a plan as one unit, for the control plane ({@code jvm}) and for a session.
   *
   * <p>Every scenario is validated and its key checked before anything is registered, so an invalid
   * fifth scenario never forces a rollback of four that already injected faults. Then the plan's
   * observability settings are applied and the scenarios registered; if one still fails, those
   * already registered are rolled back and the settings cleared, so the plan is never partly
   * active.
   *
   * @param plan the plan
   * @param scopeKey the scope of its scenarios ({@code jvm}, or {@code session:<id>})
   * @param idPrefix the prefix of the plan handle's id
   * @param validator the checks one scenario must pass before any is registered
   * @param activator registers one scenario
   * @param rollback undoes one registered scenario
   * @return the plan handle
   */
  ChaosActivationHandle activatePlan(
      final ChaosPlan plan,
      final String scopeKey,
      final String idPrefix,
      final Consumer<ChaosScenario> validator,
      final Function<ChaosScenario, ChaosActivationHandle> activator,
      final Consumer<ChaosActivationHandle> rollback) {
    ensureOpen();
    for (final ChaosScenario scenario : plan.scenarios()) {
      try {
        validator.accept(scenario);
      } catch (final RuntimeException invalid) {
        recordFailure(
            scenario.id(),
            ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
            invalid.getMessage());
        throw invalid;
      }
      final String key = scopeKey + "::" + scenario.id();
      if (registry.isLive(key)) {
        final ChaosActivationException conflict =
            new ChaosActivationConflictException("scenario key already active: " + key);
        recordFailure(
            scenario.id(),
            ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT,
            conflict.getMessage());
        throw conflict;
      }
    }
    final List<String> ids = plan.scenarios().stream().map(ChaosScenario::id).toList();
    final Object owner = new Object();
    applyPlanObservability(ids, plan.observability(), owner);
    final List<ChaosActivationHandle> handles = new ArrayList<>();
    try {
      for (final ChaosScenario scenario : plan.scenarios()) {
        handles.add(activator.apply(scenario));
      }
      final ChaosActivationHandle planHandle =
          new CompositeActivationHandle(
              idPrefix + plan.metadata().name(), handles, () -> clearPlanObservability(ids, owner));
      if (plan.observability().debugDumpOnStart()) {
        System.err.println(registry.debugDump());
      }
      return planHandle;
    } catch (final RuntimeException failure) {
      clearPlanObservability(ids, owner);
      for (final ChaosActivationHandle handle : handles) {
        try {
          rollback.accept(handle);
        } catch (final RuntimeException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
      }
      throw failure;
    }
  }

  /**
   * The checks a scenario must pass to be activated in a session: session scope, then the same
   * compatibility checks as everywhere else.
   *
   * @param scenario the scenario
   */
  void validateInSession(final ChaosScenario scenario) {
    requireSessionScope(scenario);
    CompatibilityValidator.validate(scenario, featureSet);
  }

  private static void requireSessionScope(final ChaosScenario scenario) {
    if (scenario.scope() != ChaosScenario.ScenarioScope.SESSION) {
      throw new ChaosActivationException(
          "session activation requires scenario scope SESSION for " + scenario.id());
    }
  }

  @Override
  public ChaosSession openSession(final String displayName) {
    // A session only needs {@code activateInSession} to register session-scoped scenarios; that
    // method lives on this control plane, so the session can hold a direct reference instead of
    // bouncing through the ChaosRuntime facade. Callers that reach the control plane without
    // going through ChaosRuntime can open sessions too.
    ensureOpen();
    hookDemand.require(SESSION_PROPAGATION_OPERATIONS);
    final DefaultChaosSession session = new DefaultChaosSession(displayName, scopeContext, this);
    openSessions.put(session.id(), session);
    return session;
  }

  @Override
  public ChaosSession.ScopeBinding bindSession(final @Nullable String sessionId) {
    final DefaultChaosSession session = sessionId == null ? null : openSessions.get(sessionId);
    // An id naming no open session still decides: the thread runs outside any session, even if it
    // inherited one (a server worker thread started while a test's session was bound).
    return session == null ? scopeContext.bindNone() : session.bind();
  }

  /**
   * Forgets a closed session.
   *
   * @param sessionId the session's id
   */
  void sessionClosed(final String sessionId) {
    openSessions.remove(sessionId);
  }

  @Override
  public ChaosDiagnostics diagnostics() {
    return registry;
  }

  @Override
  public void addEventListener(final ChaosEventListener listener) {
    Objects.requireNonNull(listener, "listener");
    synchronized (lifecycleLock) {
      ensureOpen();
      observabilityBus.addListener(listener);
    }
  }

  @Override
  public boolean removeEventListener(final ChaosEventListener listener) {
    Objects.requireNonNull(listener, "listener");
    return observabilityBus.removeListener(listener);
  }

  @Override
  public void close() {
    // Idempotent close: a second call must not re-traverse the controllers and re-publish
    // STOPPED lifecycle events. Framework integrations (Spring ApplicationContext teardown +
    // JUnit @AfterAll, Quarkus shutdown + main-thread close, test suites asserting exact event
    // counts) frequently call close() more than once; without this guard every controller fires
    // a second STOPPED event via its ObservabilityBus on the repeat pass.
    // Destroy *and* unregister each controller: destroy() alone only stops it, and a closed
    // runtime must hold no controllers. Snapshot the controllers into a list first — unregister()
    // mutates the registry, so iterating the live view and mutating
    // concurrently risks ConcurrentModificationException from non-ConcurrentMap backing.
    final List<ScenarioController> toUnregister;
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      toUnregister = new ArrayList<>(registry.controllers());
    }
    for (final ScenarioController controller : toUnregister) {
      try {
        controller.destroy();
      } finally {
        registry.unregister(controller);
      }
    }
    // Close AutoCloseable listeners (e.g. JfrChaosEventSink's FlightRecorder periodic hook).
    // A hook left registered pins its dead runtime in FlightRecorder — a silent JVM-wide leak
    // that grows with every runtime a test suite spins up.
    observabilityBus.close();
  }

  void setHookDemand(final HookDemand demand) {
    this.hookDemand = Objects.requireNonNull(demand, "demand");
  }

  void setInstrumentation(final Instrumentation inst) {
    this.instrumentation = Optional.of(Objects.requireNonNull(inst, "inst"));
  }

  Optional<Instrumentation> instrumentation() {
    return instrumentation;
  }

  DefaultChaosActivationHandle activateInSession(
      final DefaultChaosSession session, final ChaosScenario scenario) {
    requireSessionScope(scenario);
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

  Clock clock() {
    return clock;
  }

  private DefaultChaosActivationHandle registerScenario(
      final ChaosScenario scenario, final String scopeKey, final @Nullable String sessionId) {
    ensureOpen();
    ScenarioController controller = null;
    boolean validated = false;
    boolean weavingFailed = false;
    boolean registeredInRegistry = false;
    try {
      CompatibilityValidator.validate(scenario, featureSet);
      validated = true;
      try {
        hookDemand.require(SelectorOperations.of(scenario.selector()));
        if (scenario.selector() instanceof ChaosSelector.MethodSelector methodSelector) {
          // Weave before registering, so a weaving failure leaves nothing registered.
          registry.weaveBeforeRegistering(methodSelector, hookDemand::methods);
        }
      } catch (final RuntimeException weavingFailure) {
        weavingFailed = true;
        throw weavingFailed(scenario, weavingFailure);
      }
      controller =
          new ScenarioController(
              scenario, scopeKey, sessionId, clock, observabilityBus, () -> instrumentation);
      final DefaultChaosActivationHandle handle =
          new DefaultChaosActivationHandle(controller, registry);
      // Register before starting. A duplicate id is rejected here, before any side effect;
      // starting first would spawn the new controller's stressor and publish STARTED, then roll
      // it back with a STOPPED event carrying the id of the scenario that is still running. A
      // registered but not yet started controller is invisible to the dispatch path, because
      // evaluate() returns null until start() has set the started flag.
      final ScenarioController replaced;
      synchronized (lifecycleLock) {
        // A close() that ran since the check on entry has taken its snapshot: refuse, rather
        // than register a controller nothing would ever stop.
        ensureOpen();
        replaced = registry.register(controller);
      }
      registeredInRegistry = true;
      // The finished controller this one replaces reports its end before the new one begins.
      if (replaced != null) {
        destroyQuietly(replaced);
      }
      observabilityBus.publish(
          ChaosEvent.Type.REGISTERED,
          scenario.id(),
          "scenario registered",
          Map.of("scope", scopeKey));
      if (scenario.activationPolicy().startMode() == ActivationPolicy.StartMode.AUTOMATIC) {
        handle.start();
      }
      // A close() after the registration above has stopped the controller already: refuse,
      // rather than hand out a handle of a scenario that never runs.
      ensureOpen();
      return handle;
    } catch (final ChaosUnsupportedFeatureException unsupported) {
      unregisterAndDestroyQuietly(controller, registeredInRegistry);
      recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME,
          unsupported.getMessage());
      throw unsupported;
    } catch (final ChaosActivationException activationFailure) {
      // Thrown by the validator (e.g. destructive effect without opt-in), for hooks that cannot be
      // woven, or by the registry for a duplicate id; only the last is an activation conflict.
      final boolean conflict = validated && !weavingFailed && !registeredInRegistry;
      unregisterAndDestroyQuietly(controller, registeredInRegistry);
      recordFailure(
          scenario.id(),
          weavingFailed
              ? ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE
              : conflict
                  ? ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT
                  : ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          activationFailure.getMessage());
      throw activationFailure;
    } catch (final RuntimeException runtimeException) {
      unregisterAndDestroyQuietly(controller, registeredInRegistry);
      recordFailure(
          scenario.id(),
          weavingFailed
              ? ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE
              : ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          runtimeException.getMessage());
      throw runtimeException;
    }
  }

  private void weaveMethods(final Set<ChaosSelector.MethodSelector> selectors) {
    try {
      hookDemand.methods(selectors);
    } catch (final RuntimeException failure) {
      // Unweaving after a removal: the scenario is already gone, so leftover advice only costs an
      // evaluation that matches nothing. Report it and carry on.
      java.util.logging.Logger.getLogger("com.macstab.chaos.jvm")
          .warning(() -> "chaos-agent: could not update method hooks: " + failure);
    }
  }

  /** Records an activation failure in the diagnostics and publishes it as a FAILED event. */
  private void recordFailure(
      final String scenarioId,
      final ChaosDiagnostics.FailureCategory category,
      final @Nullable String message) {
    registry.recordFailure(scenarioId, category, message);
    observabilityBus.publish(
        ChaosEvent.Type.FAILED,
        scenarioId,
        String.valueOf(message),
        Map.of("category", category.name()));
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("chaos control plane is closed");
    }
  }

  private static void destroyQuietly(final ScenarioController controller) {
    try {
      controller.destroy();
    } catch (final RuntimeException ignored) {
      // The replaced controller had already finished; a failure while releasing its remaining
      // resources must not fail the new activation.
    }
  }

  private void unregisterAndDestroyQuietly(
      final @Nullable ScenarioController controller, final boolean wasRegistered) {
    // A controller that never reached the registry was never started either (start() runs only
    // after a successful register), so it holds no stressor, gate waiter or clock state.
    // Destroying it would only publish a STOPPED event carrying an id that may belong to a
    // scenario that is still running.
    if (controller == null || !wasRegistered) {
      // A method selector woven before the failed registration is dropped again.
      registry.reconcileMethodSelectors();
      return;
    }
    try {
      registry.unregister(controller);
    } catch (final RuntimeException ignored) {
      // Best-effort rollback: a failure here must not mask the original exception reported by
      // the caller.
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
    // The JDK 21 baseline has virtual threads; the key stays for diagnostics readers.
    details.put("virtualThreadsSupported", "true");
    details.put("jfrSupported", Boolean.toString(featureSet.jfrSupported()));
    details.put("currentSessionId", String.valueOf(scopeContext.currentSessionId()));
    details.put("droppedEvents", String.valueOf(observabilityBus.droppedEvents()));
    details.put("wovenHookGroups", String.join(",", new java.util.TreeSet<>(hookDemand.woven())));
    details.put(
        "unmatchedHooks", String.join("; ", new java.util.TreeSet<>(hookDemand.unmatched())));
    details.put("hookFailures", String.join("; ", new java.util.TreeSet<>(hookDemand.failures())));
    return details;
  }
}

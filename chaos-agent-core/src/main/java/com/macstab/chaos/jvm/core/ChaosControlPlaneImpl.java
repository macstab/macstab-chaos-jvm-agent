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
import java.util.logging.Level;
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
  private final StressorFactory stressorFactory;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Orders {@link #close()} against registering a scenario or adding a listener: either the
   * addition happens first and close() finds it, or it sees the plane closed and is refused.
   */
  private final Object lifecycleLock = new Object();

  /**
   * Which plan's observability settings a scenario carries, by {@code <scopeKey>::<id>}: only that
   * owner may clear them, so a rejected or later plan with the same ids cannot strip the settings
   * of the running one, and a plan in one scope never changes the same id in another.
   */
  private final Map<String, Object> observabilityOwners = new ConcurrentHashMap<>();

  /**
   * Scenarios whose plan set {@code jmxEnabled=false}, by {@code <scopeKey>::<id>}: JMX does not
   * list or dump them.
   */
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

  @SuppressWarnings("removal") // the deprecated sink is still fed until it is removed
  ChaosControlPlaneImpl(final Clock clock, final ChaosMetricsSink metricsSink) {
    this(clock, metricsSink, null);
  }

  /**
   * Creates a control plane whose stressors come from {@code stressorFactory}, or from the standard
   * factory when it is {@code null} (test seam, U-72 c2).
   */
  @SuppressWarnings("removal") // the deprecated sink is still fed until it is removed
  ChaosControlPlaneImpl(
      final Clock clock,
      final ChaosMetricsSink metricsSink,
      final @Nullable StressorFactory stressorFactory) {
    this.clock = clock;
    this.stressorFactory =
        stressorFactory != null ? stressorFactory : new StressorFactory(() -> instrumentation);
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
    return registerScenario(scenario, "jvm", null);
  }

  /**
   * Runs the checks {@link #activate(ChaosScenario)} runs before registering, without registering
   * anything: lets a caller validate a whole plan before it changes the active set. A refusal is
   * recorded in the diagnostics and published as {@code FAILED}, as an activation's is (L21).
   *
   * @param scenario the scenario
   * @throws com.macstab.chaos.jvm.api.ChaosActivationException if the scenario is session-scoped
   * @throws com.macstab.chaos.jvm.api.ChaosValidationException if the scenario is invalid
   * @throws ChaosUnsupportedFeatureException if the runtime cannot run it
   */
  void validate(final ChaosScenario scenario) {
    validate(scenario, ChaosScenario.ScenarioScope.JVM, "jvm");
  }

  /**
   * The checks a scenario must pass to be activated in a session: session scope, then the same
   * compatibility checks as everywhere else. A refusal is recorded, as by {@link
   * #validate(ChaosScenario)}.
   *
   * @param session the session
   * @param scenario the scenario
   */
  void validateInSession(final DefaultChaosSession session, final ChaosScenario scenario) {
    validate(scenario, ChaosScenario.ScenarioScope.SESSION, "session:" + session.id());
  }

  private void validate(
      final ChaosScenario scenario,
      final ChaosScenario.ScenarioScope activationScope,
      final String scopeKey) {
    try {
      CompatibilityValidator.validate(scenario, featureSet, activationScope);
    } catch (final ChaosUnsupportedFeatureException unsupported) {
      recordFailure(
          scopeKey,
          scenario.id(),
          ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME,
          unsupported.getMessage());
      throw unsupported;
    } catch (final RuntimeException invalid) {
      recordFailure(
          scopeKey,
          scenario.id(),
          ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          invalid.getMessage());
      throw invalid;
    }
  }

  /**
   * Weaves the hooks {@code scenario} needs, without activating it: its hook groups, and its method
   * selector added to the ones woven already, so the pre-checks of several scenarios add up. Woven
   * hooks stay, as after an activation; a method selector of a scenario that is never registered is
   * removed at the next removal. A weaving failure is recorded (L21).
   */
  void requireHooks(final ChaosScenario scenario) {
    try {
      hookDemand.require(SelectorOperations.of(scenario.selector()));
      if (scenario.selector() instanceof ChaosSelector.MethodSelector methodSelector) {
        registry.weaveBeforeRegistering(Set.of(methodSelector), hookDemand::methods);
      }
    } catch (final RuntimeException weavingFailure) {
      registry.reconcileMethodSelectors();
      throw recordedWeavingFailure(
          "jvm", scenario.id(), "scenario " + scenario.id(), weavingFailure);
    }
  }

  /**
   * Weaves every hook a plan needs before any of its scenarios is registered: the union of their
   * hook groups and all their method selectors, each in one call. A failure therefore starts none
   * of them; it is recorded under the plan's handle id, in the plan's scope.
   */
  private void weavePlan(final ChaosPlan plan, final String scopeKey, final String planId) {
    final Set<OperationType> operations = EnumSet.noneOf(OperationType.class);
    final Set<ChaosSelector.MethodSelector> methodSelectors = new java.util.HashSet<>();
    for (final ChaosScenario scenario : plan.scenarios()) {
      operations.addAll(SelectorOperations.of(scenario.selector()));
      if (scenario.selector() instanceof ChaosSelector.MethodSelector methodSelector) {
        methodSelectors.add(methodSelector);
      }
    }
    try {
      hookDemand.require(operations);
      if (!methodSelectors.isEmpty()) {
        registry.weaveBeforeRegistering(methodSelectors, hookDemand::methods);
      }
    } catch (final RuntimeException weavingFailure) {
      registry.reconcileMethodSelectors();
      throw recordedWeavingFailure(scopeKey, planId, "plan " + planId, weavingFailure);
    }
  }

  /**
   * Records a weaving failure under {@code recordedId} in {@code scopeKey} ({@code
   * UNSUPPORTED_RUNTIME} for an unsupported runtime, else {@code INSTRUMENTATION_FAILURE}) and
   * returns it as {@link #weavingFailed} reports it.
   */
  private RuntimeException recordedWeavingFailure(
      final String scopeKey,
      final String recordedId,
      final String subject,
      final RuntimeException weavingFailure) {
    final RuntimeException failure = weavingFailed(subject, weavingFailure);
    recordFailure(
        scopeKey,
        recordedId,
        failure instanceof ChaosUnsupportedFeatureException
            ? ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME
            : ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE,
        failure.getMessage());
    return failure;
  }

  /**
   * The failure an activation or {@link #requireHooks} reports when hooks cannot be woven: the
   * API's own exceptions (an unsupported runtime) as they are, anything else as a {@link
   * com.macstab.chaos.jvm.api.ChaosInstrumentationException} with the weaving failure as its cause.
   *
   * @param subject what the hooks were woven for, such as {@code scenario <id>}
   */
  private static RuntimeException weavingFailed(
      final String subject, final RuntimeException weavingFailure) {
    if (weavingFailure instanceof ChaosUnsupportedFeatureException
        || weavingFailure instanceof ChaosActivationException) {
      return weavingFailure;
    }
    return new com.macstab.chaos.jvm.api.ChaosInstrumentationException(
        "the hooks of " + subject + " could not be woven: " + weavingFailure.getMessage(),
        weavingFailure);
  }

  /** Whether the JVM-scoped key of {@code scenarioId} is held by a live controller. */
  boolean isLive(final String scenarioId) {
    return registry.isLive("jvm::" + scenarioId);
  }

  /**
   * Applies a plan's {@link ChaosPlan.Observability} to its scenarios in one scope: {@code
   * structuredLoggingEnabled=false} stops logging their events, {@code jmxEnabled=false} hides them
   * from JMX. The settings then belong to {@code owner}, replacing an earlier owner's; the same ids
   * in other scopes keep theirs.
   *
   * @param scopeKey the scope of the scenarios ({@code jvm}, or {@code session:<id>})
   * @param scenarioIds the plan's scenario ids
   * @param observability the plan's settings
   * @param owner the plan (handle, poller) the settings belong to
   */
  void applyPlanObservability(
      final String scopeKey,
      final java.util.Collection<String> scenarioIds,
      final ChaosPlan.Observability observability,
      final Object owner) {
    for (final String id : scenarioIds) {
      final String key = scopeKey + "::" + id;
      observabilityOwners.put(key, owner);
      observabilityBus.muteLogging(key, !observability.structuredLoggingEnabled());
      if (observability.jmxEnabled()) {
        jmxHidden.remove(key);
      } else {
        jmxHidden.add(key);
      }
    }
  }

  /**
   * Restores the default observability of scenarios whose settings {@code owner} set; settings of
   * another owner are left alone.
   *
   * @param scopeKey the scope of the scenarios
   * @param scenarioIds the scenario ids
   * @param owner the owner that applied them
   */
  void clearPlanObservability(
      final String scopeKey, final java.util.Collection<String> scenarioIds, final Object owner) {
    for (final String id : scenarioIds) {
      final String key = scopeKey + "::" + id;
      if (observabilityOwners.remove(key, owner)) {
        observabilityBus.muteLogging(key, false);
        jmxHidden.remove(key);
      }
    }
  }

  /**
   * Restores the default observability of every scenario in {@code scopeKey}, whichever plan set
   * it: a closed session leaves no settings behind, even for a plan handle that was never closed.
   * Idempotent.
   *
   * @param scopeKey the scope, {@code session:<id>}
   */
  void clearScopeObservability(final String scopeKey) {
    final String prefix = scopeKey + "::";
    for (final String key : List.copyOf(observabilityOwners.keySet())) {
      if (key.startsWith(prefix) && observabilityOwners.remove(key) != null) {
        observabilityBus.muteLogging(key, false);
        jmxHidden.remove(key);
      }
    }
  }

  /**
   * Returns whether JMX may list and dump the scenario.
   *
   * @param scopeKey the scenario's scope ({@code jvm}, or {@code session:<id>})
   * @param scenarioId the scenario id
   * @return {@code false} when its plan set {@code jmxEnabled=false}
   */
  boolean jmxVisible(final String scopeKey, final String scenarioId) {
    return !jmxHidden.contains(scopeKey + "::" + scenarioId);
  }

  /**
   * Returns the debug dump restricted to scenarios {@code include} accepts.
   *
   * @param include whether a scenario is part of the dump
   * @return the dump
   */
  String debugDump(final java.util.function.Predicate<ChaosDiagnostics.ScenarioReport> include) {
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
   * fifth scenario never forces a rollback of four that already injected faults; the validator
   * records its own refusals, a taken key is recorded here. Then every hook the plan needs is woven
   * at once (hook groups and method selectors), so a weaving failure starts none of its scenarios.
   * Then the plan's observability settings are applied and the scenarios registered; if one still
   * fails, those already registered are rolled back and the settings cleared, so the plan is never
   * partly active.
   *
   * @param plan the plan
   * @param scopeKey the scope of its scenarios ({@code jvm}, or {@code session:<id>})
   * @param idPrefix the prefix of the plan handle's id
   * @param validator the checks one scenario must pass before any is registered; records its
   *     refusals
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
      validator.accept(scenario);
      final String key = scopeKey + "::" + scenario.id();
      if (registry.isLive(key)) {
        final ChaosActivationException conflict =
            new ChaosActivationConflictException("scenario key already active: " + key);
        recordFailure(
            scopeKey,
            scenario.id(),
            ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT,
            conflict.getMessage());
        throw conflict;
      }
    }
    weavePlan(plan, scopeKey, idPrefix + plan.metadata().name());
    final List<String> ids = plan.scenarios().stream().map(ChaosScenario::id).toList();
    final Object owner = new Object();
    applyPlanObservability(scopeKey, ids, plan.observability(), owner);
    final List<ChaosActivationHandle> handles = new ArrayList<>();
    try {
      for (final ChaosScenario scenario : plan.scenarios()) {
        handles.add(activator.apply(scenario));
      }
      final ChaosActivationHandle planHandle =
          new CompositeActivationHandle(
              idPrefix + plan.metadata().name(),
              handles,
              () -> clearPlanObservability(scopeKey, ids, owner));
      if (plan.observability().debugDumpOnStart()) {
        System.err.println(registry.debugDump());
      }
      return planHandle;
    } catch (final RuntimeException failure) {
      clearPlanObservability(scopeKey, ids, owner);
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

  @Override
  public ChaosSession openSession(final String displayName) {
    // A session only needs {@code activateInSession} to register session-scoped scenarios; that
    // method lives on this control plane, so the session can hold a direct reference instead of
    // bouncing through the ChaosRuntime facade. Callers that reach the control plane without
    // going through ChaosRuntime can open sessions too.
    ensureOpen();
    try {
      hookDemand.require(SESSION_PROPAGATION_OPERATIONS);
    } catch (final RuntimeException weavingFailure) {
      throw recordedWeavingFailure(
          "jvm", "session:" + displayName, "session " + displayName, weavingFailure);
    }
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

  /**
   * Registers a lifecycle listener ({@link ObservabilityBus#addLifecycleListener}); removed with
   * {@link #removeEventListener}.
   *
   * @param listener the listener
   */
  void addLifecycleListener(final ChaosEventListener listener) {
    Objects.requireNonNull(listener, "listener");
    synchronized (lifecycleLock) {
      ensureOpen();
      observabilityBus.addLifecycleListener(listener);
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
    // A controller that fails to stop does not keep the others, or the listeners, alive: the first
    // failure is rethrown at the end, the later ones are added to it as suppressed.
    Throwable failure = null;
    for (final ScenarioController controller : toUnregister) {
      try {
        controller.destroy();
      } catch (final RuntimeException | Error destroyFailure) {
        failure = firstOf(failure, destroyFailure);
      } finally {
        registry.unregister(controller);
      }
    }
    // Close AutoCloseable listeners (e.g. JfrChaosEventSink's FlightRecorder periodic hook).
    // A hook left registered pins its dead runtime in FlightRecorder — a silent JVM-wide leak
    // that grows with every runtime a test suite spins up.
    try {
      observabilityBus.close();
    } catch (final RuntimeException | Error busFailure) {
      failure = firstOf(failure, busFailure);
    }
    if (failure instanceof RuntimeException runtimeFailure) {
      throw runtimeFailure;
    }
    if (failure instanceof Error error) {
      throw error;
    }
  }

  /** Returns {@code first}, with {@code next} added as suppressed, or {@code next} if none. */
  private static Throwable firstOf(final @Nullable Throwable first, final Throwable next) {
    if (first == null) {
      return next;
    }
    first.addSuppressed(next);
    return first;
  }

  void setHookDemand(final HookDemand demand) {
    this.hookDemand = Objects.requireNonNull(demand, "demand");
  }

  void setInstrumentation(final Instrumentation inst) {
    this.instrumentation = Optional.of(Objects.requireNonNull(inst, "inst"));
  }

  DefaultChaosActivationHandle activateInSession(
      final DefaultChaosSession session, final ChaosScenario scenario) {
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
      // The scope check first: a session-scoped scenario outside a session, or a JVM-scoped one in
      // a session, is refused and recorded like every other invalid scenario.
      CompatibilityValidator.validate(
          scenario,
          featureSet,
          sessionId == null
              ? ChaosScenario.ScenarioScope.JVM
              : ChaosScenario.ScenarioScope.SESSION);
      validated = true;
      try {
        hookDemand.require(SelectorOperations.of(scenario.selector()));
        if (scenario.selector() instanceof ChaosSelector.MethodSelector methodSelector) {
          // Weave before registering, so a weaving failure leaves nothing registered; a no-op when
          // its plan or a pre-check wove it already.
          registry.weaveBeforeRegistering(Set.of(methodSelector), hookDemand::methods);
        }
      } catch (final RuntimeException weavingFailure) {
        weavingFailed = true;
        throw weavingFailed("scenario " + scenario.id(), weavingFailure);
      }
      controller =
          new ScenarioController(
              scenario, scopeKey, sessionId, clock, observabilityBus, stressorFactory);
      final DefaultChaosActivationHandle handle =
          new DefaultChaosActivationHandle(
              controller,
              registry,
              failure -> recordStartFailure(scopeKey, scenario.id(), failure));
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
        // The controller, not the handle: a failure here is recorded once, by the catches below.
        controller.start();
      }
      // A close() after the registration above has stopped the controller already: refuse,
      // rather than hand out a handle of a scenario that never runs.
      ensureOpen();
      return handle;
    } catch (final ChaosUnsupportedFeatureException unsupported) {
      unregisterAndDestroyQuietly(controller, registeredInRegistry);
      recordFailure(
          scopeKey,
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
          scopeKey,
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
      // A refusal because the plane closed is no failure of the scenario, and the bus is closing.
      if (!closed.get()) {
        recordFailure(
            scopeKey,
            scenario.id(),
            weavingFailed
                ? ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE
                : ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
            runtimeException.getMessage());
      }
      throw runtimeException;
    } catch (final Error error) {
      // A linkage error or the like from a stressor or a listener: the same rollback, so no
      // REGISTERED controller is left behind, then the Error itself.
      try {
        unregisterAndDestroyQuietly(controller, registeredInRegistry);
      } catch (final Error rollbackFailure) {
        error.addSuppressed(rollbackFailure);
      }
      if (!closed.get()) {
        recordFailure(
            scopeKey,
            scenario.id(),
            ChaosDiagnostics.FailureCategory.INTERNAL_ERROR,
            error.toString());
      }
      throw error;
    }
  }

  /**
   * Records a failed {@link ChaosActivationHandle#start()} of a {@code MANUAL} scenario, with the
   * category an activation would record for the same failure.
   */
  private void recordStartFailure(
      final String scopeKey, final String scenarioId, final Throwable failure) {
    if (closed.get()) {
      return;
    }
    final ChaosDiagnostics.FailureCategory category;
    if (failure instanceof ChaosUnsupportedFeatureException) {
      category = ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME;
    } else if (failure instanceof Error) {
      category = ChaosDiagnostics.FailureCategory.INTERNAL_ERROR;
    } else {
      category = ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION;
    }
    recordFailure(
        scopeKey,
        scenarioId,
        category,
        failure instanceof Error ? failure.toString() : failure.getMessage());
  }

  private void weaveMethods(final Set<ChaosSelector.MethodSelector> selectors) {
    try {
      hookDemand.methods(selectors);
    } catch (final RuntimeException failure) {
      // Unweaving after a removal: the scenario is already gone, so leftover advice only costs an
      // evaluation that matches nothing. Report it and carry on.
      AgentLog.named(AgentLog.AGENT)
          .warning(() -> "[chaos-agent] could not update method hooks: " + failure);
    }
  }

  /**
   * Records an activation failure in the diagnostics and publishes it as a FAILED event carrying
   * the {@code category} and the {@code scope} of the refused scenario, so the failure of a muted
   * plan's scenario is muted like its other events.
   */
  private void recordFailure(
      final String scopeKey,
      final String scenarioId,
      final ChaosDiagnostics.FailureCategory category,
      final @Nullable String message) {
    registry.recordFailure(scenarioId, category, message);
    final Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("category", category.name());
    attributes.put("scope", scopeKey);
    // The message the diagnostics record: an exception without one is "(no message)", not "null".
    observabilityBus.publish(
        ChaosEvent.Type.FAILED,
        scenarioId,
        Objects.requireNonNullElse(message, "(no message)"),
        attributes);
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("chaos control plane is closed");
    }
  }

  private static void destroyQuietly(final ScenarioController controller) {
    try {
      // An expired controller ends first, with its INACTIVE event, here where no lock is held.
      controller.refreshExpiry();
      controller.destroy();
    } catch (final RuntimeException failure) {
      // The replaced controller had already finished; a failure while releasing its remaining
      // resources must not fail the new activation.
      AgentLog.named(AgentLog.AGENT)
          .log(
              Level.WARNING,
              failure,
              () ->
                  "[chaos-agent] could not release the replaced scenario "
                      + controller.scenario().id());
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
    } catch (final RuntimeException failure) {
      // Best-effort rollback: a failure here must not mask the original exception reported by
      // the caller.
      warnRollbackFailure(controller, "unregister", failure);
    }
    try {
      controller.destroy();
    } catch (final RuntimeException failure) {
      // Same rationale as above: logged, so the original failure propagates intact.
      warnRollbackFailure(controller, "stop", failure);
    }
  }

  private static void warnRollbackFailure(
      final ScenarioController controller, final String step, final RuntimeException failure) {
    AgentLog.named(AgentLog.AGENT)
        .log(
            Level.WARNING,
            failure,
            () ->
                "[chaos-agent] could not "
                    + step
                    + " scenario "
                    + controller.scenario().id()
                    + " while rolling back its failed activation");
  }

  private Map<String, String> runtimeDetails() {
    final Map<String, String> details = new LinkedHashMap<>();
    details.put("jdkFeatureVersion", Integer.toString(featureSet.runtimeFeatureVersion()));
    // The JDK 21 baseline has virtual threads; the key stays for diagnostics readers.
    details.put("virtualThreadsSupported", "true");
    details.put("jfrSupported", Boolean.toString(featureSet.jfrSupported()));
    final String currentSessionId = scopeContext.currentSessionId();
    if (currentSessionId != null) {
      details.put("currentSessionId", currentSessionId);
    }
    details.put("droppedEvents", String.valueOf(observabilityBus.droppedEvents()));
    details.put("droppedActivationFailures", String.valueOf(registry.droppedActivationFailures()));
    details.put("wovenHookGroups", String.join(",", new java.util.TreeSet<>(hookDemand.woven())));
    details.put(
        "unmatchedHooks", String.join("; ", new java.util.TreeSet<>(hookDemand.unmatched())));
    details.put("hookFailures", String.join("; ", new java.util.TreeSet<>(hookDemand.failures())));
    return details;
  }
}

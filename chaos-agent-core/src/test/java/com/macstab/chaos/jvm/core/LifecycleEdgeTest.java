package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle edges of the runtime, its sessions, scope bindings, registry and controllers: the paths
 * a misuse, a failing start or an unusual policy takes.
 */
@DisplayName("Lifecycle edges")
class LifecycleEdgeTest {

  private static final Duration HOUR = Duration.ofHours(1);

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<ChaosEvent> events = new CopyOnWriteArrayList<>();

  {
    runtime.addEventListener(events::add);
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private static ChaosScenario executorScenario(final String id, final ActivationPolicy policy) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(policy)
        .build();
  }

  private static ChaosScenario sessionScenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
  }

  private static ChaosScenario keepAliveScenario(
      final String id, final String threadName, final ActivationPolicy policy) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
        .effect(new ChaosEffect.KeepAliveEffect(threadName, true, HOUR))
        .activationPolicy(policy)
        .build();
  }

  private static long liveThreads(final String name) {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().equals(name) && thread.isAlive())
        .count();
  }

  private ScenarioController controller(final String id) {
    return runtime.registry().controllers().stream()
        .filter(controller -> controller.scenario().id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private long eventsOf(final ChaosEvent.Type type, final String id) {
    return events.stream()
        .filter(event -> event.type() == type && event.scenarioId().equals(id))
        .count();
  }

  private static RuntimeDecision submitDecision(final ChaosRuntime runtime) {
    return new DispatchEngine(runtime.controlPlane())
        .evaluate(
            new InvocationContext(
                OperationType.EXECUTOR_SUBMIT, "executor", "task", null, false, null, null, null));
  }

  private static Instrumentation unsupportedInstrumentation(final List<String> calls) {
    return (Instrumentation)
        Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
              calls.add(method.getName());
              if (method.getName().equals("isRetransformClassesSupported")) {
                return false;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Nested
  @DisplayName("ChaosRuntime")
  class RuntimeFacade {

    @Test
    @DisplayName("validate() rejects a session-scoped scenario and registers nothing")
    void validateRejectsSessionScope() {
      assertThatThrownBy(() -> runtime.validate(sessionScenario("s")))
          .isInstanceOf(ChaosActivationException.class)
          .hasMessageStartingWith("session-scoped scenario s must be activated on a session");
      assertThatCode(() -> runtime.validate(executorScenario("ok", ActivationPolicy.always())))
          .doesNotThrowAnyException();
      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
    }

    @Test
    @DisplayName("plan observability belongs to its last owner; another owner cannot clear it")
    void observabilityOwnership() {
      final Object first = new Object();
      final Object second = new Object();
      final ChaosPlan.Observability hidden = new ChaosPlan.Observability(false, true, false);
      runtime.applyPlanObservability(List.of("x"), hidden, first);
      runtime.applyPlanObservability(List.of("x"), hidden, second);

      runtime.clearPlanObservability(List.of("x"), first);
      assertThat(runtime.jmxVisible("jvm", "x")).isFalse();

      runtime.clearPlanObservability(List.of("x"), second);
      assertThat(runtime.jmxVisible("jvm", "x")).isTrue();
    }

    @Test
    @DisplayName("the installed instrumentation reaches a safepoint-storm stressor")
    void instrumentationReachesStressor() {
      final List<String> calls = new CopyOnWriteArrayList<>();
      final Instrumentation instrumentation = unsupportedInstrumentation(calls);
      runtime.setInstrumentation(instrumentation);

      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("storm")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.stress(ChaosSelector.StressTarget.SAFEPOINT_STORM))
                  .effect(new ChaosEffect.SafepointStormEffect(HOUR, 3))
                  .build());
      try {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> calls.contains("isRetransformClassesSupported"));
      } finally {
        handle.close();
      }
    }

    @Test
    @DisplayName("a missing instrumentation is rejected")
    void nullInstrumentationRejected() {
      assertThatThrownBy(() -> runtime.setInstrumentation(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("inst");
    }

    @Test
    @DisplayName("a session cannot activate a JVM-scoped scenario")
    void sessionRejectsJvmScenario() {
      final DefaultChaosSession session = (DefaultChaosSession) runtime.openSession("s");
      try {
        assertThatThrownBy(
                () -> session.activate(executorScenario("jvm-one", ActivationPolicy.always())))
            .isInstanceOf(ChaosActivationException.class)
            .hasMessage("session activation requires scenario scope SESSION for jvm-one");
        final ChaosActivationHandle handle = session.activate(sessionScenario("session-one"));
        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
        assertThat(controller("session-one").sessionId()).isEqualTo(session.id());
      } finally {
        session.close();
      }
    }

    @Test
    @DisplayName("the runtime exposes its control plane's registry, bus, clock and dispatcher")
    void exposesComponents() {
      final Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
      final ChaosRuntime fixedRuntime = new ChaosRuntime(fixed, ChaosMetricsSink.NOOP);
      try {
        assertThat(fixedRuntime.registry()).isSameAs(fixedRuntime.controlPlane().registry());
        assertThat(fixedRuntime.controlPlane().clock()).isSameAs(fixed);
        assertThat(fixedRuntime.dispatcher()).isNotNull().isSameAs(fixedRuntime.dispatcher());

        final List<ChaosEvent> busEvents = new CopyOnWriteArrayList<>();
        fixedRuntime.controlPlane().observabilityBus().addListener(busEvents::add);
        fixedRuntime.activate(executorScenario("bus", ActivationPolicy.always()));
        assertThat(busEvents)
            .extracting(ChaosEvent::type)
            .contains(ChaosEvent.Type.REGISTERED, ChaosEvent.Type.STARTED);
      } finally {
        fixedRuntime.close();
      }
    }
  }

  @Nested
  @DisplayName("control plane")
  class ControlPlane {

    @Test
    @DisplayName("re-activating a stopped id ends with REGISTERED, STARTED for the new scenario")
    void reactivationEventsEndWithTheNewScenario() {
      runtime.activate(executorScenario("again", ActivationPolicy.always())).stop();
      events.clear();

      runtime.activate(executorScenario("again", ActivationPolicy.always()));

      final List<ChaosEvent.Type> types = events.stream().map(ChaosEvent::type).toList();
      assertThat(types.subList(types.size() - 2, types.size()))
          .containsExactly(ChaosEvent.Type.REGISTERED, ChaosEvent.Type.STARTED);
    }

    @Test
    @DisplayName("a failure to unweave a removed method scenario is reported, not thrown")
    void unweaveFailureIsLogged() {
      runtime.setHookDemand(
          new HookDemand() {
            @Override
            public void require(final Set<OperationType> operations) {}

            @Override
            public void methods(final Set<ChaosSelector.MethodSelector> selectors) {
              if (selectors.isEmpty()) {
                throw new IllegalStateException("retransform refused");
              }
            }
          });
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("woven")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      new ChaosSelector.MethodSelector(
                          Set.of(OperationType.METHOD_ENTER),
                          NamePattern.exact("com.example.Service"),
                          null,
                          null))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .build());
      try (LogCapture log = LogCapture.agent()) {
        assertThatCode(handle::close).doesNotThrowAnyException();
        assertThat(log.messages())
            .contains(
                "[chaos-agent] could not update method hooks:"
                    + " java.lang.IllegalStateException: retransform refused");
      }
      assertThat(runtime.registry().controllers()).isEmpty();
    }

    @Test
    @DisplayName("a stressor that fails to start leaves nothing registered")
    void failingStartIsRolledBack() {
      // Parallelism 2 whatever the machine: the overflow needs more than one worker.
      final List<ChaosEvent> planeEvents = new CopyOnWriteArrayList<>();
      try (ChaosControlPlaneImpl plane =
          new ChaosControlPlaneImpl(
              java.time.Clock.systemUTC(),
              com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP,
              new StressorFactory(java.util.Optional::empty) {
                @Override
                ManagedStressor createIfNeeded(
                    final ChaosEffect effect,
                    final boolean allowDestructiveEffects,
                    final java.util.function.Consumer<String> onEnd) {
                  return effect instanceof ChaosEffect.ThreadLocalLeakEffect leak
                      ? new ThreadLocalLeakStressor(leak, 2)
                      : super.createIfNeeded(effect, allowDestructiveEffects, onEnd);
                }
              })) {
        plane.addEventListener(planeEvents::add);
        final ChaosScenario overflowing =
            ChaosScenario.builder("overflow")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.THREAD_LOCAL_LEAK))
                .effect(new ChaosEffect.ThreadLocalLeakEffect(Integer.MAX_VALUE, 1))
                .build();

        assertThatThrownBy(() -> plane.activate(overflowing))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("thread-local-leak total entries");

        assertThat(plane.registry().isLive("jvm::overflow")).isFalse();
        assertThat(plane.diagnostics().snapshot().failures())
            .extracting(
                ChaosDiagnostics.ActivationFailure::scenarioId,
                ChaosDiagnostics.ActivationFailure::category)
            .contains(
                org.assertj.core.groups.Tuple.tuple(
                    "overflow", ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION));
        assertThat(
                planeEvents.stream()
                    .filter(
                        event ->
                            event.type() == ChaosEvent.Type.STOPPED
                                && event.scenarioId().equals("overflow"))
                    .count())
            .isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("sessions")
  class Sessions {

    @Test
    @DisplayName("a session plan's observability settings apply to its scenarios until it closes")
    void sessionPlanObservability() {
      final ChaosSession session = runtime.openSession("plan-o");
      try {
        final ChaosActivationHandle plan =
            session.activate(
                new ChaosPlan(
                    new ChaosPlan.Metadata("hidden", ""),
                    new ChaosPlan.Observability(false, true, false),
                    List.of(sessionScenario("hidden-s"))));

        assertThat(runtime.jmxVisible("session:" + session.id(), "hidden-s")).isFalse();
        plan.close();
        assertThat(runtime.jmxVisible("session:" + session.id(), "hidden-s")).isTrue();
      } finally {
        session.close();
      }
    }

    @Test
    @DisplayName("a closed session refuses to activate")
    void closedSessionRefuses() {
      final ChaosSession session = runtime.openSession("closed");
      session.close();
      assertThatThrownBy(() -> session.activate(sessionScenario("late")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("cannot activate scenario on a closed session");
    }

    @Test
    @DisplayName("a session closed while its scenario activates destroys that scenario")
    void closedDuringActivation() {
      final ChaosSession session = runtime.openSession("racy");
      runtime.addEventListener(
          event -> {
            if (event.type() == ChaosEvent.Type.REGISTERED && event.scenarioId().equals("racy")) {
              session.close();
            }
          });

      assertThatThrownBy(() -> session.activate(sessionScenario("racy")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("session was closed during activation");
      assertThat(runtime.diagnostics().scenario("racy")).isEmpty();
    }

    @Test
    @DisplayName("a second close() does nothing")
    void secondCloseIsNoOp() {
      final ChaosSession session = runtime.openSession("twice");
      session.activate(sessionScenario("twice-s"));
      session.close();
      assertThatCode(session::close).doesNotThrowAnyException();
      assertThat(eventsOf(ChaosEvent.Type.STOPPED, "twice-s")).isEqualTo(1);
    }

    @Test
    @DisplayName("closing a session whose root binding is already gone reports it")
    void missingRootBindingOnEmptyStack() {
      final ChaosSession session = runtime.openSession("lost-root");
      final ChaosSession.ScopeBinding inner = session.bind();
      inner.close();
      // A second close of the inner binding removes the next entry of the same session: the root.
      inner.close();

      assertThatThrownBy(session::close)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "session root binding close() could not find sessionId='"
                  + session.id()
                  + "' on the stack");
    }

    @Test
    @DisplayName("closing a session whose root entry was removed under a sibling reports it")
    void missingRootBindingUnderSibling() {
      final ChaosSession first = runtime.openSession("first");
      final ChaosSession second = runtime.openSession("second");
      try {
        final ChaosSession.ScopeBinding inner = first.bind();
        inner.close();
        inner.close();

        assertThatThrownBy(first::close)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "session root binding close() could not find sessionId='"
                    + first.id()
                    + "' on the stack");
      } finally {
        second.close();
      }
    }

    @Test
    @DisplayName("closing a session plan's handle stops every scenario of the plan")
    void sessionPlanCloseStopsScenarios() {
      final ChaosSession session = runtime.openSession("planned");
      try {
        final ChaosActivationHandle plan =
            session.activate(
                new ChaosPlan(
                    new ChaosPlan.Metadata("p", ""),
                    new ChaosPlan.Observability(true, true, false),
                    List.of(sessionScenario("p1"), sessionScenario("p2"))));
        assertThat(plan.id()).isEqualTo("session-plan:p");
        plan.close();
        assertThat(plan.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
        assertThat(eventsOf(ChaosEvent.Type.STOPPED, "p1")).isEqualTo(1);
        assertThat(eventsOf(ChaosEvent.Type.STOPPED, "p2")).isEqualTo(1);
      } finally {
        session.close();
      }
    }
  }

  @Nested
  @DisplayName("scope bindings")
  class ScopeBindings {

    @Test
    @DisplayName("closing a nested binding twice fails loud")
    void doubleCloseFails() {
      final ScopeContext scope = new ScopeContext();
      final ChaosSession.ScopeBinding binding = scope.bind("nested");
      binding.close();
      assertThatThrownBy(binding::close)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "ScopeBinding close() could not find sessionId='nested' on the stack (already"
                  + " closed?)");
    }

    @Test
    @DisplayName(
        "a wrapped task that corrupts its own binding still completes; the failure is logged")
    void wrappedRunnableSurvivesBrokenBinding() throws Exception {
      final ScopeContext scope = new ScopeContext();
      final AtomicReference<String> seen = new AtomicReference<>();
      final Runnable wrapped =
          scope.wrap(
              "s",
              () -> {
                seen.set(scope.currentSessionId());
                final ChaosSession.ScopeBinding extra = scope.bind("s");
                extra.close();
                extra.close();
              });
      try (LogCapture log = LogCapture.agent()) {
        final Thread worker = new Thread(wrapped);
        worker.start();
        worker.join(5_000L);
        assertThat(seen).hasValue("s");
        assertThat(log.records())
            .anyMatch(
                record ->
                    record.getLevel() == Level.FINE
                        && record
                            .getMessage()
                            .equals(
                                "chaos scope binding close failed; session stack may be"
                                    + " inconsistent")
                        && record.getThrown() instanceof IllegalStateException);
      }
    }

    @Test
    @DisplayName("a wrapped callable that corrupts its own binding still returns its value")
    void wrappedCallableSurvivesBrokenBinding() throws Exception {
      final ScopeContext scope = new ScopeContext();
      final Callable<String> wrapped =
          scope.wrap(
              "c",
              () -> {
                final ChaosSession.ScopeBinding extra = scope.bind("c");
                extra.close();
                extra.close();
                return "done";
              });
      try (LogCapture log = LogCapture.agent()) {
        final AtomicReference<String> result = new AtomicReference<>();
        final Thread worker =
            new Thread(
                () -> {
                  try {
                    result.set(wrapped.call());
                  } catch (final Exception unexpected) {
                    result.set("failed: " + unexpected);
                  }
                });
        worker.start();
        worker.join(5_000L);
        assertThat(result).hasValue("done");
        assertThat(log.records())
            .extracting(LogRecord::getThrown)
            .anyMatch(thrown -> thrown instanceof IllegalStateException);
      }
    }
  }

  @Nested
  @DisplayName("registry")
  class Registry {

    @Test
    @DisplayName("a registry without a method-selector listener still tracks method selectors")
    void tracksMethodSelectorsWithoutListener() {
      final ScenarioRegistry registry = new ScenarioRegistry(Clock.systemUTC(), Map::of);
      final java.util.List<Set<ChaosSelector.MethodSelector>> delivered =
          new java.util.ArrayList<>();
      registry.onMethodSelectorsChanged(delivered::add);
      final ChaosSelector.MethodSelector selector =
          new ChaosSelector.MethodSelector(
              Set.of(OperationType.METHOD_ENTER), NamePattern.exact("com.example.A"), null, null);
      final ScenarioController controller =
          new ScenarioController(
              ChaosScenario.builder("m")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(selector)
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .build(),
              "jvm",
              null,
              Clock.systemUTC(),
              new ObservabilityBus(ChaosMetricsSink.NOOP),
              Optional::empty);
      registry.register(controller);
      assertThat(delivered).containsExactly(Set.of(selector));

      registry.unregister(controller);
      registry.unregister(controller);
      assertThat(delivered).containsExactly(Set.of(selector), Set.of());
      assertThat(registry.controllers()).isEmpty();
    }

    @Test
    @DisplayName("a stopped scenario no longer holds its key")
    void stoppedScenarioIsNotLive() {
      final ChaosActivationHandle handle =
          runtime.activate(executorScenario("stopped", ActivationPolicy.always()));
      assertThat(runtime.registry().isLive("jvm::stopped")).isTrue();
      handle.stop();
      assertThat(runtime.registry().isLive("jvm::stopped")).isFalse();
    }

    @Test
    @DisplayName("an operation no scenario selects matches nothing")
    void unselectedOperationMatchesNothing() {
      runtime.activate(executorScenario("submit-only", ActivationPolicy.always()));
      assertThat(
              runtime
                  .registry()
                  .match(
                      new InvocationContext(
                          OperationType.QUEUE_PUT, "queue", null, null, false, null, null, null)))
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("scenario controller")
  class Controller {

    @Test
    @DisplayName("a session scenario's controller carries the session scope key")
    void sessionScopeKey() {
      final ChaosSession session = runtime.openSession("keyed");
      try {
        session.activate(sessionScenario("keyed-s"));
        assertThat(controller("keyed-s").scopeKey()).isEqualTo("session:" + session.id());
      } finally {
        session.close();
      }
    }

    @Test
    @DisplayName("a controller that never transitioned has an empty reason")
    void freshControllerHasEmptyReason() {
      final ScenarioController controller =
          new ScenarioController(
              executorScenario("fresh", ActivationPolicy.manual()),
              "jvm",
              null,
              Clock.systemUTC(),
              new ObservabilityBus(ChaosMetricsSink.NOOP),
              Optional::empty);
      assertThat(controller.reason()).isEmpty();
      assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.REGISTERED);
    }

    @Test
    @DisplayName("a stress scenario started after stop stays stopped and leaves no stressor")
    void startAfterStopLeavesNoStressor() {
      final ChaosActivationHandle handle =
          runtime.activate(keepAliveScenario("restart", "edge-restart", ActivationPolicy.always()));
      handle.stop();
      handle.start();
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
      assertThat(liveThreads("edge-restart")).isZero();
    }

    @Test
    @DisplayName("starting an active stress scenario again keeps a single stressor")
    void secondStartKeepsOneStressor() {
      final ChaosActivationHandle handle =
          runtime.activate(keepAliveScenario("again", "edge-again", ActivationPolicy.always()));
      try {
        handle.start();
        assertThat(liveThreads("edge-again")).isEqualTo(1);
        assertThat(eventsOf(ChaosEvent.Type.STARTED, "again")).isEqualTo(1);
      } finally {
        handle.close();
      }
      await().atMost(5, TimeUnit.SECONDS).until(() -> liveThreads("edge-again") == 0);
    }

    @Test
    @DisplayName("starting an active scenario again publishes no second STARTED")
    void secondStartIsIdempotent() {
      final ChaosActivationHandle handle =
          runtime.activate(executorScenario("idem", ActivationPolicy.always()));
      handle.start();
      assertThat(eventsOf(ChaosEvent.Type.STARTED, "idem")).isEqualTo(1);
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("releasing the gate of a stopped scenario does nothing")
    void releaseOnStoppedGate() {
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("gated")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.gate(Duration.ofMillis(10)))
                  .build());
      handle.stop();
      handle.release();
      assertThat(eventsOf(ChaosEvent.Type.RELEASED, "gated")).isZero();
    }

    @Test
    @DisplayName("a stressor whose activeUntil has passed ends at once")
    void pastActiveUntilEndsStressor() {
      final ChaosActivationHandle handle =
          runtime.activate(
              keepAliveScenario(
                  "past",
                  "edge-past",
                  ActivationPolicy.builder().activeUntil(Instant.now().minusSeconds(60)).build()));
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> handle.state() == ChaosDiagnostics.ScenarioState.INACTIVE);
      await().atMost(5, TimeUnit.SECONDS).until(() -> liveThreads("edge-past") == 0);
    }

    @Test
    @DisplayName("a stressor ends at the earlier of activeFor and activeUntil: activeUntil")
    void activeUntilEarlierThanActiveFor() {
      final ChaosActivationHandle handle =
          runtime.activate(
              keepAliveScenario(
                  "until-first",
                  "edge-until-first",
                  ActivationPolicy.builder()
                      .activeFor(HOUR)
                      .activeUntil(Instant.now().plusMillis(200))
                      .build()));
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> handle.state() == ChaosDiagnostics.ScenarioState.INACTIVE);
    }

    @Test
    @DisplayName("a stressor ends at the earlier of activeFor and activeUntil: activeFor")
    void activeForEarlierThanActiveUntil() {
      final ChaosActivationHandle handle =
          runtime.activate(
              keepAliveScenario(
                  "for-first",
                  "edge-for-first",
                  ActivationPolicy.builder()
                      .activeFor(Duration.ofMillis(200))
                      .activeUntil(Instant.now().plus(HOUR))
                      .build()));
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> handle.state() == ChaosDiagnostics.ScenarioState.INACTIVE);
    }

    @Test
    @DisplayName("a stressor whose activeFor is beyond the nanosecond range never expires")
    void hugeActiveForNeverExpires() {
      final ChaosActivationHandle handle =
          runtime.activate(
              keepAliveScenario(
                  "forever",
                  "edge-forever",
                  ActivationPolicy.builder().activeFor(Duration.ofDays(365L * 300L)).build()));
      try {
        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
        assertThat(liveThreads("macstab-chaos-expiry-forever")).isZero();
      } finally {
        handle.close();
      }
    }

    @Test
    @DisplayName("an activeFor whose deadline is past Instant.MAX keeps the scenario applying")
    void unrepresentableDeadlineKeepsApplying() {
      final ChaosActivationHandle handle =
          runtime.activate(
              executorScenario(
                  "endless",
                  ActivationPolicy.builder()
                      .activeFor(Duration.ofSeconds(Long.MAX_VALUE))
                      .build()));
      assertThat(submitDecision(runtime).delayNanos()).isEqualTo(Duration.ofMillis(1).toNanos());
      assertThat(handle.appliedCount()).isEqualTo(1L);
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("a manually started scenario is within its activeFor window before it starts")
    void windowBeforeStart() {
      runtime.activate(
          executorScenario(
              "manual",
              ActivationPolicy.builder()
                  .startMode(ActivationPolicy.StartMode.MANUAL)
                  .activeFor(Duration.ofMillis(1))
                  .build()));
      assertThat(controller("manual").withinWindow()).isTrue();
    }

    @Test
    @DisplayName("a scenario not yet started whose activeUntil has passed stays registered")
    void expiredWindowBeforeStart() {
      final ChaosActivationHandle handle =
          runtime.activate(
              executorScenario(
                  "late",
                  ActivationPolicy.builder()
                      .startMode(ActivationPolicy.StartMode.MANUAL)
                      .activeUntil(Instant.now().minusSeconds(60))
                      .build()));
      assertThat(controller("late").withinWindow()).isFalse();
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.REGISTERED);
    }
  }

  @Nested
  @DisplayName("composite handles")
  class Composite {

    private static final class FailingHandle implements ChaosActivationHandle {
      private final String id;

      FailingHandle(final String id) {
        this.id = id;
      }

      @Override
      public String id() {
        return id;
      }

      @Override
      public void start() {
        throw new IllegalStateException(id + " cannot start");
      }

      @Override
      public void stop() {}

      @Override
      public void release() {}

      @Override
      public ChaosDiagnostics.ScenarioState state() {
        return ChaosDiagnostics.ScenarioState.STOPPED;
      }

      @Override
      public long matchedCount() {
        return 0;
      }

      @Override
      public long appliedCount() {
        return 0;
      }
    }

    @Test
    @DisplayName("every delegate is tried and every failure is reported")
    void aggregatesDelegateFailures() {
      final CompositeActivationHandle composite =
          new CompositeActivationHandle(
              "two", List.of(new FailingHandle("a"), new FailingHandle("b")));
      assertThatThrownBy(composite::start)
          .isExactlyInstanceOf(RuntimeException.class)
          .hasMessage("one or more delegates failed during composite start")
          .hasCauseInstanceOf(IllegalStateException.class)
          .satisfies(
              failure -> {
                assertThat(failure.getCause()).hasMessage("a cannot start");
                assertThat(failure.getSuppressed())
                    .singleElement()
                    .satisfies(suppressed -> assertThat(suppressed).hasMessage("b cannot start"));
              });
    }

    @Test
    @DisplayName("a composite without delegates is neither active nor stopped")
    void emptyCompositeIsInactive() {
      assertThat(new CompositeActivationHandle("none", List.of()).state())
          .isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    private final FeatureSet features = new FeatureSet();

    @Test
    @DisplayName("carrier pinning requires its own effect")
    void carrierPinningNeedsItsEffect() {
      final ChaosScenario scenario =
          ChaosScenario.builder("pin")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.stress(ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
              .effect(new ChaosEffect.HeapPressureEffect(1024, 1024))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, features))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessage(
              "StressTarget.VIRTUAL_THREAD_CARRIER_PINNING requires"
                  + " VirtualThreadCarrierPinningEffect");
    }

    @Test
    @DisplayName("a stressor rejects a replay log")
    void stressorRejectsReplay() {
      final ChaosScenario scenario =
          keepAliveScenario(
              "replayed",
              "edge-replayed",
              ActivationPolicy.builder().replayMatches(List.of(1L)).build());
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, features))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("replayMatches do not apply to them");
    }

    @Test
    @DisplayName("a clock skew rejects a replay log")
    void clockSkewRejectsReplay() {
      final ChaosScenario scenario =
          ChaosScenario.builder("skew")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)))
              .effect(ChaosEffect.skewClock(Duration.ofMinutes(5), ChaosEffect.ClockSkewMode.FIXED))
              .activationPolicy(ActivationPolicy.builder().replayMatches(List.of(1L)).build())
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, features))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("replayMatches do not apply to it");
    }
  }

  @Nested
  @DisplayName("activation handles")
  class Handles {

    @Test
    @DisplayName("destroying a handle twice stops the scenario once")
    void destroyTwice() {
      final DefaultChaosActivationHandle handle =
          (DefaultChaosActivationHandle)
              runtime.activate(executorScenario("destroyed", ActivationPolicy.always()));
      handle.destroy();
      handle.destroy();
      assertThat(eventsOf(ChaosEvent.Type.STOPPED, "destroyed")).isEqualTo(1);
      assertThat(runtime.diagnostics().scenario("destroyed")).isEmpty();
    }
  }
}

package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The control plane's lifecycle: plans and their composite handle, sessions, failed and conflicting
 * activations, closing, the events each transition publishes and how listeners are managed.
 */
@DisplayName("Control-plane lifecycle")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class ControlPlaneLifecycleTest {

  private static final ChaosPlan.Observability DEFAULT_OBSERVABILITY =
      new ChaosPlan.Observability(true, true, false);

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<ChaosEvent> events = new CopyOnWriteArrayList<>();

  @BeforeEach
  void listen() {
    runtime.addEventListener(events::add);
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private static ChaosScenario submitScenario(final String id, final ActivationPolicy policy) {
    return scenario(id, OperationType.EXECUTOR_SUBMIT, ChaosEffect.reject(id), policy);
  }

  private static ChaosScenario scenario(
      final String id,
      final OperationType operation,
      final ChaosEffect effect,
      final ActivationPolicy policy) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(
            operation == OperationType.QUEUE_PUT
                ? ChaosSelector.queue(Set.of(operation))
                : ChaosSelector.executor(Set.of(operation)))
        .effect(effect)
        .activationPolicy(policy)
        .build();
  }

  private static ChaosPlan plan(final String name, final ChaosScenario... scenarios) {
    return plan(name, DEFAULT_OBSERVABILITY, scenarios);
  }

  private static ChaosPlan plan(
      final String name,
      final ChaosPlan.Observability observability,
      final ChaosScenario... scenarios) {
    return new ChaosPlan(new ChaosPlan.Metadata(name, ""), observability, List.of(scenarios));
  }

  private long count(final ChaosEvent.Type type, final String scenarioId) {
    return events.stream()
        .filter(event -> event.type() == type && event.scenarioId().equals(scenarioId))
        .count();
  }

  private List<ChaosDiagnostics.ActivationFailure> failures() {
    return runtime.diagnostics().snapshot().failures();
  }

  private boolean submissionRejected() {
    try {
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, new AtomicInteger()::get);
      return false;
    } catch (final RejectedExecutionException rejected) {
      return true;
    }
  }

  @Nested
  @DisplayName("plans")
  class Plans {

    @Test
    @DisplayName("a plan handle starts, counts and stops every scenario of the plan together")
    void compositeHandle() throws Throwable {
      final ChaosActivationHandle handle =
          runtime.activate(
              plan(
                  "pair",
                  submitScenario("a", ActivationPolicy.manual()),
                  scenario(
                      "b",
                      OperationType.QUEUE_PUT,
                      ChaosEffect.delay(Duration.ofMillis(1)),
                      ActivationPolicy.manual())));

      assertThat(handle.id()).isEqualTo("plan:pair");
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.REGISTERED);
      assertThat(submissionRejected()).isFalse();

      handle.start();
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      assertThat(submissionRejected()).isTrue();
      runtime.beforeQueueOperation("QUEUE_PUT", this);
      assertThat(handle.matchedCount()).isEqualTo(2L);
      assertThat(handle.appliedCount()).isEqualTo(2L);

      handle.stop();
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
      assertThat(submissionRejected()).isFalse();
      assertThat(count(ChaosEvent.Type.STOPPED, "a")).isEqualTo(1L);
      assertThat(count(ChaosEvent.Type.STOPPED, "b")).isEqualTo(1L);
    }

    @Test
    @DisplayName("releasing a plan handle releases the gates of its scenarios")
    void compositeRelease() {
      final ChaosActivationHandle handle =
          runtime.activate(
              plan(
                  "gates",
                  scenario(
                      "gate",
                      OperationType.QUEUE_PUT,
                      ChaosEffect.gate(Duration.ofSeconds(30)),
                      ActivationPolicy.always())));

      handle.release();

      assertThat(count(ChaosEvent.Type.RELEASED, "gate")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a closed control plane refuses a plan before validating it")
    void closedRefusesAPlan() {
      runtime.close();

      assertThatThrownBy(
              () ->
                  runtime.activate(
                      plan(
                          "late",
                          ChaosScenario.builder("session-only")
                              .scope(ChaosScenario.ScenarioScope.SESSION)
                              .selector(
                                  ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                              .effect(ChaosEffect.suppress())
                              .build())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("chaos control plane is closed");
    }

    @Test
    @DisplayName("an invalid plan is recorded as an invalid configuration")
    void invalidPlanIsRecorded() {
      assertThatThrownBy(
              () ->
                  runtime.activate(
                      plan(
                          "invalid",
                          ChaosScenario.builder("session-only")
                              .scope(ChaosScenario.ScenarioScope.SESSION)
                              .selector(
                                  ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                              .effect(ChaosEffect.suppress())
                              .build())))
          .isInstanceOf(ChaosActivationException.class);

      assertThat(failures())
          .extracting(
              ChaosDiagnostics.ActivationFailure::scenarioId,
              ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(
                  "session-only", ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION));
      assertThat(count(ChaosEvent.Type.FAILED, "session-only")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a plan naming an active scenario is recorded as an activation conflict")
    void conflictingPlanIsRecorded() {
      runtime.activate(submitScenario("taken", ActivationPolicy.always()));

      assertThatThrownBy(
              () ->
                  runtime.activate(
                      plan("again", submitScenario("taken", ActivationPolicy.always()))))
          .isInstanceOf(ChaosActivationException.class);

      assertThat(failures())
          .extracting(ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT);
    }

    @Test
    @DisplayName("hooks that cannot be woven fail the activation as an instrumentation failure")
    void weavingFailureIsAnInstrumentationFailure() {
      runtime.setHookDemand(
          operations -> {
            throw new IllegalStateException("cannot weave executors");
          });

      assertThatThrownBy(() -> runtime.activate(submitScenario("x", ActivationPolicy.always())))
          .isInstanceOf(com.macstab.chaos.jvm.api.ChaosInstrumentationException.class)
          .hasMessageContaining("x")
          .hasRootCauseMessage("cannot weave executors");

      assertThat(failures())
          .extracting(ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE);
      assertThat(runtime.diagnostics().scenario("x")).isEmpty();
    }

    @Test
    @DisplayName("hooks woven ahead of an activation: the scenario's operations, nothing activated")
    void requireHooksWeavesWithoutActivating() {
      final List<Set<OperationType>> woven = new java.util.concurrent.CopyOnWriteArrayList<>();
      runtime.setHookDemand(
          operations -> {
            woven.add(Set.copyOf(operations));
            if (operations.contains(OperationType.QUEUE_PUT)) {
              throw new IllegalStateException("cannot weave queues");
            }
          });

      runtime.requireHooks(submitScenario("ahead", ActivationPolicy.always()));
      assertThat(woven).containsExactly(Set.of(OperationType.EXECUTOR_SUBMIT));
      assertThat(runtime.diagnostics().scenario("ahead")).isEmpty();

      assertThatThrownBy(
              () ->
                  runtime.requireHooks(
                      scenario(
                          "queues",
                          OperationType.QUEUE_PUT,
                          ChaosEffect.reject("q"),
                          ActivationPolicy.always())))
          .isInstanceOf(com.macstab.chaos.jvm.api.ChaosInstrumentationException.class)
          .hasMessageContaining("queues")
          .hasRootCauseMessage("cannot weave queues");
    }

    @Test
    @DisplayName("a plan whose second scenario fails to weave rolls back the first")
    void weavingFailureRollsBack() {
      runtime.setHookDemand(
          operations -> {
            if (operations.contains(OperationType.QUEUE_PUT)) {
              throw new IllegalStateException("cannot weave queues");
            }
          });

      assertThatThrownBy(
              () ->
                  runtime.activate(
                      plan(
                          "half",
                          new ChaosPlan.Observability(false, true, false),
                          submitScenario("first", ActivationPolicy.always()),
                          scenario(
                              "second",
                              OperationType.QUEUE_PUT,
                              ChaosEffect.reject("q"),
                              ActivationPolicy.always()))))
          .isInstanceOf(com.macstab.chaos.jvm.api.ChaosInstrumentationException.class)
          .hasRootCauseMessage("cannot weave queues");

      assertThat(submissionRejected()).as("the first scenario was rolled back").isFalse();
      assertThat(runtime.jmxVisible("jvm", "first")).as("its plan settings were cleared").isTrue();
      assertThat(failures())
          .extracting(ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE);
    }
  }

  @Nested
  @DisplayName("single activations")
  class Activations {

    @Test
    @DisplayName("a session-scoped scenario activated outside a session is recorded as invalid")
    void sessionScopeOutsideASession() {
      assertThatThrownBy(
              () ->
                  runtime.activate(
                      ChaosScenario.builder("session-only")
                          .scope(ChaosScenario.ScenarioScope.SESSION)
                          .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                          .effect(ChaosEffect.suppress())
                          .build()))
          .isInstanceOf(ChaosActivationException.class);

      assertThat(failures())
          .extracting(ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION);
    }

    @Test
    @DisplayName("activation asks the hook demand for the selector's operations")
    void hookDemandForTheSelector() {
      final List<Set<OperationType>> demanded = new CopyOnWriteArrayList<>();
      runtime.setHookDemand(demanded::add);

      runtime.activate(submitScenario("submit", ActivationPolicy.always()));

      assertThat(demanded).containsExactly(Set.of(OperationType.EXECUTOR_SUBMIT));
    }

    @Test
    @DisplayName("an unsupported runtime is recorded as such")
    void unsupportedRuntime() {
      runtime.setHookDemand(
          operations -> {
            throw new ChaosUnsupportedFeatureException("no hooks on this JVM");
          });

      assertThatThrownBy(() -> runtime.activate(submitScenario("x", ActivationPolicy.always())))
          .isInstanceOf(ChaosUnsupportedFeatureException.class);

      assertThat(failures())
          .extracting(ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME);
      assertThat(runtime.diagnostics().scenario("x")).isEmpty();
    }

    @Test
    @DisplayName("a duplicate activation leaves the running scenario untouched")
    void duplicateLeavesTheOriginal() {
      runtime.activate(submitScenario("taken", ActivationPolicy.always()));

      assertThatThrownBy(() -> runtime.activate(submitScenario("taken", ActivationPolicy.always())))
          .isInstanceOf(ChaosActivationException.class);

      assertThat(count(ChaosEvent.Type.STOPPED, "taken")).isZero();
      assertThat(runtime.diagnostics().scenario("taken").orElseThrow().state())
          .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      assertThat(submissionRejected()).isTrue();
    }

    @Test
    @DisplayName("re-activating a spent scenario replaces it and releases the old one")
    void spentScenarioIsReplaced() throws Throwable {
      final ActivationPolicy once = ActivationPolicy.builder().maxApplications(1L).build();
      runtime.activate(
          scenario("gate", OperationType.QUEUE_PUT, ChaosEffect.gate(Duration.ofMillis(1)), once));
      runtime.beforeQueueOperation("QUEUE_PUT", this);
      assertThat(runtime.diagnostics().scenario("gate").orElseThrow().state())
          .isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);

      runtime.activate(
          scenario("gate", OperationType.QUEUE_PUT, ChaosEffect.gate(Duration.ofMillis(1)), once));

      assertThat(count(ChaosEvent.Type.STOPPED, "gate")).as("the spent one was destroyed").isOne();
      assertThat(runtime.diagnostics().scenario("gate").orElseThrow().state())
          .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("stopping a handle publishes STOPPED; releasing a gate publishes RELEASED")
    void stopAndReleaseEvents() {
      final ChaosActivationHandle gate =
          runtime.activate(
              scenario(
                  "gate",
                  OperationType.QUEUE_PUT,
                  ChaosEffect.gate(Duration.ofSeconds(30)),
                  ActivationPolicy.always()));

      gate.release();
      assertThat(count(ChaosEvent.Type.RELEASED, "gate")).isOne();

      gate.stop();
      assertThat(count(ChaosEvent.Type.STOPPED, "gate")).isOne();
    }
  }

  @Nested
  @DisplayName("rate limits")
  class RateLimits {

    @Test
    @DisplayName("a rate limit lets exactly its permits through per window")
    void permitsPerWindow() {
      try (ChaosRuntime fixed =
          new ChaosRuntime(
              Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
              com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP)) {
        fixed.activate(
            submitScenario(
                "limited",
                ActivationPolicy.builder().rateLimit(2L, Duration.ofMinutes(1)).build()));
        int rejected = 0;
        for (int call = 0; call < 5; call++) {
          try {
            fixed.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, new AtomicInteger()::get);
          } catch (final RejectedExecutionException expected) {
            rejected++;
          }
        }

        assertThat(rejected).isEqualTo(2);
      }
    }
  }

  @Nested
  @DisplayName("sessions")
  class Sessions {

    @Test
    @DisplayName("opening a session weaves the hooks that carry it to other threads")
    void openingWeavesPropagation() {
      final List<Set<OperationType>> demanded = new CopyOnWriteArrayList<>();
      runtime.setHookDemand(demanded::add);

      try (ChaosSession session = runtime.openSession("weave")) {
        assertThat(session.displayName()).isEqualTo("weave");
      }

      assertThat(demanded)
          .singleElement()
          .satisfies(
              operations ->
                  assertThat(operations)
                      .contains(OperationType.EXECUTOR_SUBMIT, OperationType.THREAD_START));
    }

    @Test
    @DisplayName("closing a session unbinds it from the thread that opened it")
    void closeUnbinds() {
      final ChaosSession session = runtime.openSession("bound");
      assertThat(runtime.currentSessionId()).isEqualTo(session.id());

      session.close();

      assertThat(runtime.currentSessionId()).isNull();
    }

    @Test
    @DisplayName("a session wraps a callable to run in it on any thread")
    void wrapCallable() throws Exception {
      final AtomicReference<String> during = new AtomicReference<>();
      try (ChaosSession session = runtime.openSession("wrap")) {
        final Callable<String> wrapped =
            session.wrap(
                () -> {
                  during.set(runtime.currentSessionId());
                  return "value";
                });
        final AtomicReference<String> result = new AtomicReference<>();
        final Thread other =
            new Thread(
                () -> {
                  try {
                    result.set(wrapped.call());
                  } catch (final Exception failure) {
                    result.set(failure.toString());
                  }
                });
        other.start();
        other.join(10_000L);

        assertThat(result.get()).isEqualTo("value");
        assertThat(during.get()).isEqualTo(session.id());
      }
    }

    @Test
    @DisplayName("a plan activated on a session returns a handle over all its scenarios")
    void sessionPlan() {
      try (ChaosSession session = runtime.openSession("plan")) {
        final ChaosActivationHandle handle =
            session.activate(plan("in-session", sessionScenario("s1"), sessionScenario("s2")));

        assertThat(handle.id()).isEqualTo("session-plan:in-session");
        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
        assertThat(submissionRejected()).isTrue();
      }
    }

    @Test
    @DisplayName("a session plan with an invalid scenario activates none of its scenarios")
    void sessionPlanRollsBack() {
      try (ChaosSession session = runtime.openSession("plan")) {
        assertThatThrownBy(
                () ->
                    session.activate(
                        plan(
                            "broken",
                            sessionScenario("ok"),
                            submitScenario("jvm-scoped", ActivationPolicy.always()))))
            .isInstanceOf(ChaosActivationException.class);

        assertThat(submissionRejected()).isFalse();
        assertThat(count(ChaosEvent.Type.REGISTERED, "ok"))
            .as("validated before registering")
            .isZero();
      }
      assertThat(count(ChaosEvent.Type.STOPPED, "ok")).isZero();
    }

    private ChaosScenario sessionScenario(final String id) {
      return ChaosScenario.builder(id)
          .scope(ChaosScenario.ScenarioScope.SESSION)
          .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
          .effect(ChaosEffect.reject(id))
          .activationPolicy(ActivationPolicy.always())
          .build();
    }
  }

  @Nested
  @DisplayName("closing")
  class Closing {

    @Test
    @DisplayName("close stops and forgets every scenario once; a second close publishes nothing")
    void closeIsIdempotent() {
      runtime.activate(submitScenario("a", ActivationPolicy.always()));

      runtime.close();
      runtime.close();

      assertThat(count(ChaosEvent.Type.STOPPED, "a")).isOne();
      assertThat(runtime.diagnostics().scenario("a")).isEmpty();
    }

    @Test
    @DisplayName("close closes listeners that are AutoCloseable and detaches every listener")
    void closeDetachesListeners() {
      final AtomicInteger closed = new AtomicInteger();
      final ClosingListener closing = new ClosingListener(closed);
      runtime.addEventListener(closing);
      final ChaosActivationHandle handle =
          runtime.activate(submitScenario("a", ActivationPolicy.always()));

      runtime.close();
      final int seen = closing.events.get();
      handle.stop();

      assertThat(closed).hasValue(1);
      assertThat(closing.events).as("no event reaches a detached listener").hasValue(seen);
      assertThat(runtime.removeEventListener(closing)).isFalse();
    }

    @Test
    @DisplayName("an activation overtaken by close() is refused and leaves nothing running")
    void activationOvertakenByClose() throws Exception {
      final Throwable failure =
          raceWithClose(() -> runtime.activate(submitScenario("late", ActivationPolicy.always())));

      assertThat(failure)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("chaos control plane is closed");
      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
      assertThat(submissionRejected()).as("no fault is injected after close").isFalse();
      assertThat(failures()).as("closing is not a failure of the scenario").isEmpty();
    }

    @Test
    @DisplayName("an activation whose close() came after registering is refused, not a dead handle")
    void activationClosedAfterRegistering() {
      runtime.addEventListener(
          event -> {
            if (event.type() == ChaosEvent.Type.REGISTERED && "late".equals(event.scenarioId())) {
              runtime.close();
            }
          });

      assertThatThrownBy(() -> runtime.activate(submitScenario("late", ActivationPolicy.always())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("chaos control plane is closed");
      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
      assertThat(submissionRejected()).as("no fault is injected after close").isFalse();
      assertThat(failures()).as("closing is not a failure of the scenario").isEmpty();
    }

    @Test
    @DisplayName("a session activation overtaken by close() is refused and leaves nothing running")
    void sessionActivationOvertakenByClose() throws Exception {
      final ChaosSession session = runtime.openSession("racing");
      final ChaosScenario scenario =
          ChaosScenario.builder("late")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.reject("late"))
              .activationPolicy(ActivationPolicy.always())
              .build();

      final Throwable failure = raceWithClose(() -> session.activate(scenario));

      assertThat(failure).isInstanceOf(IllegalStateException.class);
      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
    }

    /**
     * Runs {@code activation} on another thread, holds it inside the weaving step, closes the
     * runtime meanwhile and lets the activation continue; returns what the activation threw.
     */
    private Throwable raceWithClose(final Callable<?> activation) throws Exception {
      final CountDownLatch inWeaving = new CountDownLatch(1);
      final CountDownLatch closed = new CountDownLatch(1);
      runtime.setHookDemand(
          operations -> {
            inWeaving.countDown();
            try {
              closed.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      final AtomicReference<Throwable> thrown = new AtomicReference<>();
      final Thread activating =
          new Thread(
              () -> {
                try {
                  activation.call();
                } catch (final Throwable failure) {
                  thrown.set(failure);
                }
              });
      activating.start();
      assertThat(inWeaving.await(5, TimeUnit.SECONDS)).isTrue();
      runtime.close();
      closed.countDown();
      activating.join(5_000L);
      return thrown.get();
    }
  }

  @Nested
  @DisplayName("A failure while a scenario starts")
  class StartFailures {

    private final List<ChaosEvent> planeEvents = new CopyOnWriteArrayList<>();

    /** A control plane whose stressor factory throws {@code failure} once, then works. */
    private ChaosControlPlaneImpl planeFailingOnce(final Throwable failure) {
      final AtomicInteger calls = new AtomicInteger();
      final ChaosControlPlaneImpl plane =
          new ChaosControlPlaneImpl(
              Clock.systemUTC(),
              com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP,
              new StressorFactory(java.util.Optional::empty) {
                @Override
                ManagedStressor createIfNeeded(
                    final ChaosEffect effect,
                    final boolean allowDestructiveEffects,
                    final java.util.function.Consumer<String> onEnd) {
                  if (calls.getAndIncrement() == 0) {
                    if (failure instanceof Error error) {
                      throw error;
                    }
                    throw (RuntimeException) failure;
                  }
                  return super.createIfNeeded(effect, allowDestructiveEffects, onEnd);
                }
              });
      plane.addEventListener(planeEvents::add);
      return plane;
    }

    private static ChaosScenario keepAlive(final ActivationPolicy policy) {
      return ChaosScenario.builder("keep-alive")
          .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
          .effect(
              new ChaosEffect.KeepAliveEffect("start-failure-keepalive", true, Duration.ofHours(1)))
          .activationPolicy(policy)
          .build();
    }

    private List<ChaosDiagnostics.FailureCategory> categories(final ChaosControlPlaneImpl plane) {
      return plane.diagnostics().snapshot().failures().stream()
          .map(ChaosDiagnostics.ActivationFailure::category)
          .toList();
    }

    @Test
    @DisplayName("an Error rolls the activation back, is recorded as INTERNAL_ERROR and rethrown")
    void errorDuringStart() {
      final ChaosControlPlaneImpl plane = planeFailingOnce(new NoClassDefFoundError("x"));
      try {
        assertThatThrownBy(() -> plane.activate(keepAlive(ActivationPolicy.always())))
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessage("x");

        assertThat(plane.diagnostics().snapshot().scenarios()).isEmpty();
        assertThat(categories(plane))
            .containsExactly(ChaosDiagnostics.FailureCategory.INTERNAL_ERROR);
        assertThat(planeEvents)
            .filteredOn(event -> event.type() == ChaosEvent.Type.FAILED)
            .hasSize(1);
        try (ChaosActivationHandle again = plane.activate(keepAlive(ActivationPolicy.always()))) {
          assertThat(again.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
        }
      } finally {
        plane.close();
      }
    }

    @Test
    @DisplayName("an OutOfMemoryError while the stressor starts is an unsupported runtime")
    void oomDuringStart() {
      final ChaosControlPlaneImpl plane =
          planeFailingOnce(new OutOfMemoryError("unable to create native thread"));
      try {
        assertThatThrownBy(() -> plane.activate(keepAlive(ActivationPolicy.always())))
            .isInstanceOf(ChaosUnsupportedFeatureException.class)
            .hasMessageContaining("unable to create native thread");

        assertThat(plane.diagnostics().snapshot().scenarios()).isEmpty();
        assertThat(categories(plane))
            .containsExactly(ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME);
      } finally {
        plane.close();
      }
    }

    @Test
    @DisplayName("a failing manual start is recorded and leaves the scenario registered")
    void manualStartFailure() {
      final ChaosControlPlaneImpl plane =
          planeFailingOnce(new OutOfMemoryError("unable to create native thread"));
      try {
        final ChaosActivationHandle handle = plane.activate(keepAlive(ActivationPolicy.manual()));

        assertThatThrownBy(handle::start).isInstanceOf(ChaosUnsupportedFeatureException.class);

        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.REGISTERED);
        assertThat(categories(plane))
            .containsExactly(ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME);
        assertThat(
                Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.getName().equals("start-failure-keepalive")))
            .isFalse();
        handle.start();
        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
        handle.close();
      } finally {
        plane.close();
      }
    }

    @Test
    @DisplayName("an Error in a manual start is recorded as INTERNAL_ERROR, naming the Error")
    void manualStartError() {
      final ChaosControlPlaneImpl plane = planeFailingOnce(new NoClassDefFoundError("missing"));
      try {
        final ChaosActivationHandle handle = plane.activate(keepAlive(ActivationPolicy.manual()));

        assertThatThrownBy(handle::start).isInstanceOf(NoClassDefFoundError.class);

        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.REGISTERED);
        assertThat(plane.diagnostics().snapshot().failures())
            .singleElement()
            .satisfies(
                failure -> {
                  assertThat(failure.category())
                      .isEqualTo(ChaosDiagnostics.FailureCategory.INTERNAL_ERROR);
                  assertThat(failure.message()).contains("NoClassDefFoundError: missing");
                });
        handle.close();
      } finally {
        plane.close();
      }
    }

    @Test
    @DisplayName("any other failure of a manual start is recorded as INVALID_CONFIGURATION")
    void manualStartRuntimeFailure() {
      final ChaosControlPlaneImpl plane =
          planeFailingOnce(new IllegalArgumentException("bad stressor"));
      try {
        final ChaosActivationHandle handle = plane.activate(keepAlive(ActivationPolicy.manual()));

        assertThatThrownBy(handle::start)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("bad stressor");

        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.REGISTERED);
        assertThat(plane.diagnostics().snapshot().failures())
            .singleElement()
            .satisfies(
                failure -> {
                  assertThat(failure.category())
                      .isEqualTo(ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION);
                  assertThat(failure.message()).isEqualTo("bad stressor");
                });
        handle.close();
      } finally {
        plane.close();
      }
    }
  }

  @Nested
  @DisplayName("A replaced scenario")
  class ReplacedScenario {

    @Test
    @DisplayName(
        "an expired scenario failing to release its stressor when replaced is logged once as a"
            + " WARNING; the new activation succeeds")
    void releaseFailureIsLogged() {
      // The first scenario expires between its STARTED event and its started flag: a scenario
      // with its id activated from the STARTED listener then replaces it while it still holds
      // its stressor, whose close fails.
      final Instant start = Instant.parse("2026-01-01T00:00:00Z");
      final AtomicReference<Instant> now = new AtomicReference<>(start);
      final Clock clock =
          new Clock() {
            @Override
            public java.time.ZoneId getZone() {
              return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
              return this;
            }

            @Override
            public Instant instant() {
              return now.get();
            }
          };
      final IllegalStateException releaseFailure = new IllegalStateException("cannot release");
      final AtomicInteger built = new AtomicInteger();
      final ChaosControlPlaneImpl plane =
          new ChaosControlPlaneImpl(
              clock,
              com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP,
              new StressorFactory(java.util.Optional::empty) {
                @Override
                ManagedStressor createIfNeeded(
                    final ChaosEffect effect,
                    final boolean allowDestructiveEffects,
                    final java.util.function.Consumer<String> onEnd) {
                  return built.getAndIncrement() == 0
                      ? () -> {
                        throw releaseFailure;
                      }
                      : () -> {};
                }
              });
      final AtomicReference<ChaosActivationHandle> replacement = new AtomicReference<>();
      final AtomicInteger startedEvents = new AtomicInteger();
      plane.addEventListener(
          event -> {
            if (event.type() == ChaosEvent.Type.STARTED && startedEvents.getAndIncrement() == 0) {
              now.set(start.plus(Duration.ofMinutes(2)));
              replacement.set(
                  plane.activate(submitScenario("replaced", ActivationPolicy.always())));
            }
          });
      try (LogCapture log = LogCapture.agent()) {
        final ChaosActivationHandle expired =
            plane.activate(
                submitScenario(
                    "replaced",
                    ActivationPolicy.builder()
                        .activeUntil(start.plus(Duration.ofMinutes(1)))
                        .build()));

        assertThat(expired.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
        assertThat(replacement.get().state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
        final List<java.util.logging.LogRecord> warnings =
            log.records().stream()
                .filter(record -> record.getLevel() == java.util.logging.Level.WARNING)
                .toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).startsWith("[chaos-agent] ").contains("replaced");
        assertThat(warnings.get(0).getThrown()).isSameAs(releaseFailure);
      } finally {
        plane.close();
      }
    }
  }

  @Nested
  @DisplayName("listeners")
  class Listeners {

    @Test
    @DisplayName("removing a listener that was never added removes nothing")
    void removeUnknownListener() {
      final ChaosEventListener stranger = event -> {};

      assertThat(runtime.removeEventListener(stranger)).isFalse();
      runtime.activate(submitScenario("a", ActivationPolicy.always()));
      assertThat(count(ChaosEvent.Type.STARTED, "a"))
          .as("the added listener still listens")
          .isOne();
    }

    @Test
    @DisplayName("an asynchronous listener receives events on its own thread without drops")
    void asyncListener() throws InterruptedException {
      final CountDownLatch started = new CountDownLatch(1);
      final AtomicReference<String> deliveredOn = new AtomicReference<>();
      runtime.addEventListener(
          ChaosEventListener.async(
              event -> {
                if (event.type() == ChaosEvent.Type.STARTED) {
                  deliveredOn.set(Thread.currentThread().getName());
                  started.countDown();
                }
              }));

      runtime.activate(submitScenario("a", ActivationPolicy.always()));

      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(deliveredOn.get()).isEqualTo("macstab-chaos-events");
      assertThat(runtime.diagnostics().snapshot().runtimeDetails())
          .containsEntry("droppedEvents", "0");
    }

    @Test
    @DisplayName("an asynchronous listener that falls behind drops events and counts them")
    void asyncListenerDrops() throws InterruptedException {
      final CountDownLatch hold = new CountDownLatch(1);
      final ChaosEventListener blocked =
          ChaosEventListener.async(
              event -> {
                try {
                  hold.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              });
      runtime.addEventListener(blocked);
      runtime.activate(
          scenario(
              "a",
              OperationType.EXECUTOR_SUBMIT,
              ChaosEffect.delay(Duration.ZERO),
              ActivationPolicy.always()));
      try {
        // Each call publishes APPLIED: far more than the delivery queue holds.
        for (int call = 0; call < 10_100; call++) {
          runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
        }

        assertThat(
                Long.parseLong(
                    runtime.diagnostics().snapshot().runtimeDetails().get("droppedEvents")))
            .isPositive();
      } finally {
        hold.countDown();
        runtime.removeEventListener(blocked);
      }
    }

    @Test
    @DisplayName("removing an asynchronous listener closes the listener it delivers to")
    void removingAnAsyncListenerClosesIt() {
      final AtomicInteger closed = new AtomicInteger();
      final ChaosEventListener async = ChaosEventListener.async(new ClosingListener(closed));
      runtime.addEventListener(async);

      assertThat(runtime.removeEventListener(async)).isTrue();

      assertThat(closed).hasValue(1);
    }
  }

  @Nested
  @DisplayName("A failure while a scenario ends")
  class EndFailures {

    /** A control plane whose every stressor fails to close with a failure of its own. */
    private ChaosControlPlaneImpl planeWithFailingCloses(
        final Clock clock, final List<RuntimeException> closeFailures) {
      return new ChaosControlPlaneImpl(
          clock,
          com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP,
          new StressorFactory(java.util.Optional::empty) {
            @Override
            ManagedStressor createIfNeeded(
                final ChaosEffect effect,
                final boolean allowDestructiveEffects,
                final java.util.function.Consumer<String> onEnd) {
              final RuntimeException closeFailure =
                  new IllegalStateException("cannot close " + closeFailures.size());
              closeFailures.add(closeFailure);
              return () -> {
                throw closeFailure;
              };
            }
          });
    }

    /** Waits up to 5 s for the INACTIVE event of the plane's only scenario. */
    private static ChaosEvent awaitInactive(final List<ChaosEvent> events)
        throws InterruptedException {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        for (final ChaosEvent event : events) {
          if (event.type() == ChaosEvent.Type.INACTIVE) {
            return event;
          }
        }
        Thread.sleep(10);
      }
      throw new AssertionError("no INACTIVE event within 5 s: " + events);
    }

    @Test
    @DisplayName(
        "a stressor that fails to close at expiry is logged once, and the scenario still reports"
            + " INACTIVE")
    void expiryWithFailingStressorClose() throws InterruptedException {
      // Arrange
      final List<RuntimeException> closeFailures = new CopyOnWriteArrayList<>();
      final ChaosControlPlaneImpl plane = planeWithFailingCloses(Clock.systemUTC(), closeFailures);
      final List<ChaosEvent> events = new CopyOnWriteArrayList<>();
      plane.addEventListener(events::add);
      try (LogCapture log = LogCapture.agent()) {
        // Act
        plane.activate(
            submitScenario(
                "expiring", ActivationPolicy.builder().activeFor(Duration.ofMillis(50)).build()));
        final ChaosEvent inactive = awaitInactive(events);

        // Assert
        assertThat(inactive.attributes()).containsEntry("reason", "expired");
        assertThat(log.records())
            .filteredOn(record -> record.getLevel() == java.util.logging.Level.WARNING)
            .singleElement()
            .satisfies(record -> assertThat(record.getThrown()).isSameAs(closeFailures.get(0)));
      } finally {
        plane.close();
      }
    }

    @Test
    @DisplayName(
        "an expiry check that fails (a clock that throws) is logged, and ends the scenario with"
            + " reason 'expiry failed'")
    void failingExpiryCheckEndsTheScenario() throws InterruptedException {
      // Arrange
      final AtomicReference<Boolean> broken = new AtomicReference<>(false);
      final IllegalStateException clockFailure = new IllegalStateException("clock broken");
      final Clock clock =
          new Clock() {
            @Override
            public java.time.ZoneId getZone() {
              return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
              return this;
            }

            @Override
            public Instant instant() {
              if (broken.get()) {
                throw clockFailure;
              }
              return Instant.now();
            }
          };
      final ChaosControlPlaneImpl plane =
          new ChaosControlPlaneImpl(clock, com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP);
      final List<ChaosEvent> events = new CopyOnWriteArrayList<>();
      plane.addEventListener(events::add);
      try (LogCapture log = LogCapture.agent()) {
        plane.activate(
            submitScenario(
                "expiring", ActivationPolicy.builder().activeFor(Duration.ofMillis(50)).build()));

        // Act
        broken.set(true);
        final ChaosEvent inactive = awaitInactive(events);

        // Assert
        assertThat(inactive.attributes()).containsEntry("reason", "expiry failed");
        assertThat(log.records())
            .filteredOn(record -> record.getLevel() == java.util.logging.Level.WARNING)
            .extracting(java.util.logging.LogRecord::getThrown)
            .contains(clockFailure);
      } finally {
        broken.set(false);
        plane.close();
      }
    }

    @Test
    @DisplayName(
        "close() stops every scenario and closes the listeners although a stop fails; the failure"
            + " is rethrown with the later ones suppressed")
    void closeContinuesAfterAFailingStop() {
      // Arrange
      final List<RuntimeException> closeFailures = new CopyOnWriteArrayList<>();
      final ChaosControlPlaneImpl plane = planeWithFailingCloses(Clock.systemUTC(), closeFailures);
      final AtomicInteger listenerCloses = new AtomicInteger();
      plane.addEventListener(new ClosingListener(listenerCloses));
      plane.activate(submitScenario("first", ActivationPolicy.always()));
      plane.activate(submitScenario("second", ActivationPolicy.always()));

      // Act
      final Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(plane::close);

      // Assert
      assertThat(closeFailures).hasSize(2);
      assertThat(thrown).isIn(closeFailures.get(0), closeFailures.get(1));
      assertThat(thrown.getSuppressed())
          .containsExactly(
              thrown == closeFailures.get(0) ? closeFailures.get(1) : closeFailures.get(0));
      assertThat(plane.registry().controllers()).isEmpty();
      assertThat(listenerCloses).hasValue(1);
    }
  }

  /** A listener that counts its events and its closes. */
  static final class ClosingListener implements ChaosEventListener, AutoCloseable {
    private final AtomicInteger closed;
    final AtomicInteger events = new AtomicInteger();

    ClosingListener(final AtomicInteger closed) {
      this.closed = closed;
    }

    @Override
    public void onEvent(final ChaosEvent event) {
      events.incrementAndGet();
    }

    @Override
    public void close() {
      closed.incrementAndGet();
    }
  }
}

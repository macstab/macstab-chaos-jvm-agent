package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosInstrumentationException;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Activation that cannot leak: scope, plan atomicity and observability ownership. */
@DisplayName("Activation integrity")
class ActivationIntegrityTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private static ChaosScenario scenario(final String id, final ChaosScenario.ScenarioScope scope) {
    return ChaosScenario.builder(id)
        .scope(scope)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
  }

  private static ChaosScenario invalid(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.tornWrite())
        .build();
  }

  private static ChaosPlan plan(
      final String name, final ChaosPlan.Observability observability, final ChaosScenario... all) {
    return new ChaosPlan(new ChaosPlan.Metadata(name, ""), observability, List.of(all));
  }

  @Test
  @DisplayName("a session-scoped scenario is refused outside a session instead of running JVM-wide")
  void sessionScopeOutsideSession() {
    assertThatThrownBy(() -> runtime.activate(scenario("s", ChaosScenario.ScenarioScope.SESSION)))
        .isInstanceOf(ChaosActivationException.class)
        .hasMessageContaining("must be activated on a session");
    assertThatThrownBy(() -> runtime.validate(scenario("s", ChaosScenario.ScenarioScope.SESSION)))
        .isInstanceOf(ChaosActivationException.class);
  }

  @Test
  @DisplayName("a plan with an invalid scenario starts none of its scenarios")
  void planValidatedFirst() {
    final List<ChaosEvent> started = new CopyOnWriteArrayList<>();
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.STARTED) {
            started.add(event);
          }
        });

    assertThatThrownBy(
            () ->
                runtime.activate(
                    plan(
                        "half",
                        null,
                        scenario("first", ChaosScenario.ScenarioScope.JVM),
                        invalid("second"))))
        .isInstanceOf(RuntimeException.class);

    assertThat(started).isEmpty();
    assertThat(runtime.diagnostics().scenario("first")).isEmpty();
  }

  @Test
  @DisplayName("a rejected plan with the same ids leaves the running plan's settings alone")
  void rejectedPlanKeepsOwnersSettings() {
    final ChaosPlan.Observability hidden = new ChaosPlan.Observability(false, false, false);
    runtime.activate(plan("a", hidden, scenario("x", ChaosScenario.ScenarioScope.JVM)));

    assertThatThrownBy(
            () ->
                runtime.activate(
                    plan(
                        "b",
                        new ChaosPlan.Observability(true, true, false),
                        scenario("x", ChaosScenario.ScenarioScope.JVM))))
        .isInstanceOf(ChaosActivationException.class)
        .hasMessageContaining("already active");

    assertThat(runtime.jmxVisible("jvm", "x")).as("still plan a's setting").isFalse();
  }

  @Test
  @DisplayName("closing a plan twice does not strip the settings of the plan that took over")
  void doubleCloseKeepsSuccessorsSettings() {
    final ChaosActivationHandle first =
        runtime.activate(
            plan(
                "a",
                new ChaosPlan.Observability(false, true, false),
                scenario("x", ChaosScenario.ScenarioScope.JVM)));
    first.close();
    runtime.activate(
        plan(
            "b",
            new ChaosPlan.Observability(false, true, false),
            scenario("x", ChaosScenario.ScenarioScope.JVM)));

    first.close();

    assertThat(runtime.jmxVisible("jvm", "x")).isFalse();
  }

  @Nested
  @DisplayName("every refusal and weaving failure is recorded once (L21)")
  class Recording {

    private final List<ChaosEvent> failed = new CopyOnWriteArrayList<>();
    private final List<ChaosEvent> started = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listen() {
      runtime.addEventListener(
          event -> {
            if (event.type() == ChaosEvent.Type.FAILED) {
              failed.add(event);
            } else if (event.type() == ChaosEvent.Type.STARTED) {
              started.add(event);
            }
          });
    }

    @Test
    @DisplayName("a JVM-scoped scenario activated on a session is refused and recorded")
    void sessionScopeRefusalIsRecorded() {
      try (ChaosSession session = runtime.openSession("recorded")) {
        assertThatThrownBy(
                () -> session.activate(scenario("jvm-in-session", ChaosScenario.ScenarioScope.JVM)))
            .isInstanceOf(ChaosActivationException.class);
      }

      assertThat(failures("jvm-in-session"))
          .containsExactly(ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION);
      assertThat(failedIds()).containsExactly("jvm-in-session");
    }

    @Test
    @DisplayName("validate() of an invalid scenario publishes FAILED and records it")
    void validateRecords() {
      assertThatThrownBy(() -> runtime.validate(invalid("bad")))
          .isInstanceOf(ChaosValidationException.class);

      assertThat(failures("bad"))
          .containsExactly(ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION);
      assertThat(failedIds()).containsExactly("bad");
      assertThat(runtime.diagnostics().scenario("bad")).isEmpty();
    }

    @Test
    @DisplayName("validate() of a scenario the runtime cannot run records UNSUPPORTED_RUNTIME")
    void validateRecordsUnsupported() {
      final ChaosScenario tooMuchHeap =
          ChaosScenario.builder("heap")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
              .effect(
                  new ChaosEffect.HeapPressureEffect(
                      ChaosEffect.HeapPressureEffect.MAX_BYTES, 1024 * 1024))
              .build();

      assertThatThrownBy(() -> runtime.validate(tooMuchHeap))
          .isInstanceOf(ChaosUnsupportedFeatureException.class);

      assertThat(failures("heap"))
          .containsExactly(ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME);
    }

    @Test
    @DisplayName("a session plan whose scenario is invalid is recorded once")
    void sessionPlanValidationIsRecordedOnce() {
      try (ChaosSession session = runtime.openSession("plan-session")) {
        assertThatThrownBy(() -> session.activate(plan("p", null, invalid("bad-in-plan"))))
            .isInstanceOf(RuntimeException.class);
      }

      assertThat(failures("bad-in-plan")).hasSize(1);
      assertThat(failedIds()).containsExactly("bad-in-plan");
    }

    @Test
    @DisplayName("a plan whose second method selector cannot be woven starts none of its scenarios")
    void planMethodWeaveFailureStartsNothing() {
      final ChaosSelector.MethodSelector broken = method("b.Broken");
      runtime.setHookDemand(
          new HookDemand() {
            @Override
            public void require(final Set<OperationType> operations) {}

            @Override
            public void methods(final Set<ChaosSelector.MethodSelector> selectors) {
              if (selectors.contains(broken)) {
                throw new IllegalStateException("cannot weave b.Broken");
              }
            }
          });

      assertThatThrownBy(
              () ->
                  runtime.activate(
                      plan(
                          "woven",
                          null,
                          methodScenario("first", method("a.Fine")),
                          methodScenario("second", broken))))
          .isInstanceOf(ChaosInstrumentationException.class);

      assertThat(started).isEmpty();
      assertThat(failedIds()).containsExactly("plan:woven");
      assertThat(failures("plan:woven"))
          .containsExactly(ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE);
    }

    @Test
    @DisplayName("a plan whose second hook group cannot be woven starts none of its scenarios")
    void planHookGroupFailureStartsNothing() {
      runtime.setHookDemand(
          operations -> {
            if (operations.contains(OperationType.FILE_IO_READ)) {
              throw new IllegalStateException("cannot weave the file hooks");
            }
          });
      final ChaosScenario fileRead =
          ChaosScenario.builder("file")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();

      assertThatThrownBy(
              () ->
                  runtime.activate(
                      plan(
                          "groups",
                          null,
                          scenario("executor", ChaosScenario.ScenarioScope.JVM),
                          fileRead)))
          .isInstanceOf(ChaosInstrumentationException.class);

      assertThat(started).isEmpty();
      assertThat(runtime.diagnostics().scenario("executor")).isEmpty();
      assertThat(failedIds()).containsExactly("plan:groups");
    }

    @Test
    @DisplayName("a plan with three method selectors weaves them in one call")
    void planWeavesOnce() {
      final List<Set<ChaosSelector.MethodSelector>> weaves = new CopyOnWriteArrayList<>();
      runtime.setHookDemand(
          new HookDemand() {
            @Override
            public void require(final Set<OperationType> operations) {}

            @Override
            public void methods(final Set<ChaosSelector.MethodSelector> selectors) {
              weaves.add(selectors);
            }
          });

      runtime.activate(
          plan(
              "three",
              null,
              methodScenario("m1", method("a.One")),
              methodScenario("m2", method("a.Two")),
              methodScenario("m3", method("a.Three"))));

      assertThat(weaves)
          .containsExactly(Set.of(method("a.One"), method("a.Two"), method("a.Three")));
    }

    @Test
    @DisplayName("requireHooks pre-weaves method selectors, and two pre-weaves add up")
    void requireHooksPreWeavesAndAddsUp() {
      final List<Set<ChaosSelector.MethodSelector>> weaves = new CopyOnWriteArrayList<>();
      runtime.setHookDemand(
          new HookDemand() {
            @Override
            public void require(final Set<OperationType> operations) {}

            @Override
            public void methods(final Set<ChaosSelector.MethodSelector> selectors) {
              weaves.add(selectors);
            }
          });

      runtime.requireHooks(methodScenario("p1", method("a.One")));
      runtime.requireHooks(methodScenario("p2", method("a.Two")));

      assertThat(weaves).isNotEmpty();
      assertThat(weaves.get(weaves.size() - 1))
          .containsExactlyInAnyOrder(method("a.One"), method("a.Two"));
    }

    @Test
    @DisplayName("a weaving failure in requireHooks is recorded")
    void requireHooksRecords() {
      runtime.setHookDemand(
          operations -> {
            throw new IllegalStateException("cannot weave");
          });

      assertThatThrownBy(
              () -> runtime.requireHooks(scenario("hooks", ChaosScenario.ScenarioScope.JVM)))
          .isInstanceOf(ChaosInstrumentationException.class);

      assertThat(failures("hooks"))
          .containsExactly(ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE);
    }

    @Test
    @DisplayName("an openSession whose hooks cannot be woven is wrapped and recorded")
    void openSessionWeaveFailure() {
      runtime.setHookDemand(
          operations -> {
            throw new IllegalStateException("cannot weave the session hooks");
          });

      assertThatThrownBy(() -> runtime.openSession("weaveless"))
          .isInstanceOf(ChaosInstrumentationException.class)
          .hasRootCauseMessage("cannot weave the session hooks");

      assertThat(failures("session:weaveless"))
          .containsExactly(ChaosDiagnostics.FailureCategory.INSTRUMENTATION_FAILURE);
      assertThat(failedIds()).containsExactly("session:weaveless");
    }

    private List<ChaosDiagnostics.FailureCategory> failures(final String id) {
      return runtime.diagnostics().snapshot().failures().stream()
          .filter(failure -> failure.scenarioId().equals(id))
          .map(ChaosDiagnostics.ActivationFailure::category)
          .toList();
    }

    private List<String> failedIds() {
      return failed.stream().map(ChaosEvent::scenarioId).toList();
    }
  }

  private static ChaosSelector.MethodSelector method(final String className) {
    return new ChaosSelector.MethodSelector(
        Set.of(OperationType.METHOD_ENTER), NamePattern.exact(className), NamePattern.any(), null);
  }

  private static ChaosScenario methodScenario(
      final String id, final ChaosSelector.MethodSelector selector) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(selector)
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
  }
}

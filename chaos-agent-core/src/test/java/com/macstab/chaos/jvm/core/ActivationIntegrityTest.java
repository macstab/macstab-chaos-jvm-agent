package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
  @Tag("law-L21")
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

    assertThat(runtime.jmxVisible("x")).as("still plan a's setting").isFalse();
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

    assertThat(runtime.jmxVisible("x")).isFalse();
  }
}

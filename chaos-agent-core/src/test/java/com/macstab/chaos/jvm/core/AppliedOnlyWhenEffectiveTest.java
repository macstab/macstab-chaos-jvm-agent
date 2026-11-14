package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A scenario that matched but lost on precedence did not take effect: it must not report APPLIED,
 * count as applied, or spend its {@code maxApplications} budget.
 */
@DisplayName("APPLIED is recorded only for contributions that take effect")
class AppliedOnlyWhenEffectiveTest {

  private ChaosRuntime runtime;
  private final List<ChaosEvent> applied = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.APPLIED) {
            applied.add(event);
          }
        });
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("the losing terminal scenario keeps its budget and reports no APPLIED")
  void losingTerminalKeepsBudget() {
    // Arrange: a low-precedence suppress capped at one application, a high-precedence reject.
    runtime.activate(scenario("low-suppress", ChaosEffect.suppress(), 0, capped(1L)));
    runtime.activate(scenario("high-reject", ChaosEffect.reject("won"), 100, null));

    // Act
    assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
        .isInstanceOf(RejectedExecutionException.class);

    // Assert
    assertThat(applied).extracting(ChaosEvent::scenarioId).containsExactly("high-reject");
    assertThat(report("low-suppress").appliedCount()).isZero();
    assertThat(report("low-suppress").state())
        .as("the loser still has its single application left")
        .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("delays of all matching scenarios are applied alongside the winning terminal")
  void delaysAreAppliedWithTheTerminal() {
    // Arrange
    runtime.activate(scenario("delay", ChaosEffect.delay(Duration.ofMillis(1)), 0, null));
    runtime.activate(scenario("reject", ChaosEffect.reject("won"), 100, null));

    // Act
    assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
        .isInstanceOf(RejectedExecutionException.class);

    // Assert
    assertThat(applied)
        .extracting(ChaosEvent::scenarioId)
        .containsExactlyInAnyOrder("delay", "reject");
  }

  @Test
  @DisplayName("once the loser wins later, its budget is spent and it becomes INACTIVE")
  void loserSpendsBudgetWhenItTakesEffect() {
    // Arrange
    runtime.activate(scenario("capped-suppress", ChaosEffect.suppress(), 0, capped(1L)));

    // Act: alone, the suppress takes effect once.
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    // Assert
    assertThat(report("capped-suppress").appliedCount()).isEqualTo(1L);
    assertThat(report("capped-suppress").state())
        .isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
  }

  private static ActivationPolicy capped(final long maxApplications) {
    return new ActivationPolicy(
        ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0L, maxApplications, null, null, 0L, false);
  }

  private ChaosDiagnostics.ScenarioReport report(final String id) {
    return runtime.diagnostics().scenario(id).orElseThrow();
  }

  private static ChaosScenario scenario(
      final String id,
      final ChaosEffect effect,
      final int precedence,
      final ActivationPolicy policy) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(effect)
        .precedence(precedence)
        .activationPolicy(policy == null ? ActivationPolicy.always() : policy)
        .build();
  }
}

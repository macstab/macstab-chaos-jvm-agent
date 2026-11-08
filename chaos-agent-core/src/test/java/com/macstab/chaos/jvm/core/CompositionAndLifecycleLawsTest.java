package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
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
 * The laws of {@code docs/semantics.md} on composing scenarios (§4) and on the lifecycle (§6) that
 * no other test states on its own: delay saturation, the one gate, the terminal tie-break, the
 * finality of stop and the replacement rules.
 */
@DisplayName("Composition and lifecycle laws")
class CompositionAndLifecycleLawsTest {

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
  @DisplayName("the delays of every matching scenario are summed into one delay")
  void delaysAddUp() {
    runtime.activate(
        scenario("a", ChaosEffect.delay(Duration.ofHours(300)), 0, ActivationPolicy.always()));
    runtime.activate(
        scenario("b", ChaosEffect.delay(Duration.ofHours(400)), 0, ActivationPolicy.always()));
    runtime.activate(
        scenario("c", ChaosEffect.delay(Duration.ofMillis(5)), 0, ActivationPolicy.always()));

    final RuntimeDecision decision =
        new DispatchEngine(runtime.controlPlane())
            .evaluate(
                new InvocationContext(
                    OperationType.EXECUTOR_SUBMIT,
                    "executor",
                    "task",
                    null,
                    false,
                    null,
                    null,
                    null));

    assertThat(decision.delayNanos())
        .isEqualTo(Duration.ofHours(700).toNanos() + Duration.ofMillis(5).toNanos());
  }

  @Test
  @DisplayName("a single delay is bounded, so no number of scenarios can overflow the sum")
  void delayIsBounded() {
    assertThatThrownBy(() -> ChaosEffect.delay(Duration.ofHours(721)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PT720H");
  }

  @Test
  @DisplayName("of two gates only the higher-precedence one holds the caller and is applied")
  void oneGate() {
    runtime.activate(
        scenario("a-low", ChaosEffect.gate(Duration.ofSeconds(30)), 0, ActivationPolicy.always()));
    runtime.activate(
        scenario(
            "b-high", ChaosEffect.gate(Duration.ofMillis(50)), 100, ActivationPolicy.always()));

    final long start = System.nanoTime();
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    final Duration held = Duration.ofNanos(System.nanoTime() - start);

    assertThat(held)
        .as("held by the 50 ms gate, not the 30 s one")
        .isLessThan(Duration.ofSeconds(5));
    assertThat(applied).extracting(ChaosEvent::scenarioId).containsExactly("b-high");
  }

  @Test
  @DisplayName("of two terminals with equal precedence the smaller scenario id decides")
  void terminalTieBreak() {
    // Activated in reverse order, so activation order cannot be what decides.
    runtime.activate(
        scenario("b-second", ChaosEffect.reject("second"), 7, ActivationPolicy.always()));
    runtime.activate(
        scenario("a-first", ChaosEffect.reject("first"), 7, ActivationPolicy.always()));

    for (int call = 0; call < 20; call++) {
      assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessageContaining("first");
    }
    assertThat(applied).extracting(ChaosEvent::scenarioId).containsOnly("a-first");
  }

  @Test
  @DisplayName(
      "data-shaping effects are exclusive terminals: of trickle and corruptRead one applies")
  void dataShapingEffectsAreExclusive() throws Throwable {
    runtime.activate(
        ChaosScenario.builder("a-trickle")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
            .effect(ChaosEffect.trickle(10))
            .precedence(5)
            .activationPolicy(ActivationPolicy.always())
            .build());
    runtime.activate(
        ChaosScenario.builder("b-corrupt")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
            .effect(ChaosEffect.corruptRead())
            .activationPolicy(ActivationPolicy.always())
            .build());

    final int count = runtime.beforeSocketChannelRead(this, 64);

    assertThat(count).as("trickled to 10 bytes, not marked for corruption").isEqualTo(10);
    assertThat(applied).extracting(ChaosEvent::scenarioId).containsExactly("a-trickle");
  }

  @Test
  @DisplayName("start() on a stopped handle is a no-op: the scenario never fires again")
  void stopIsFinal() {
    final ChaosActivationHandle handle =
        runtime.activate(
            scenario("stopped", ChaosEffect.reject("no"), 0, ActivationPolicy.always()));
    handle.stop();

    handle.start();

    assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
    final Runnable task = () -> {};
    assertThat(runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, task)).isSameAs(task);
    assertThat(applied).extracting(ChaosEvent::scenarioId).doesNotContain("stopped");
  }

  @Test
  @DisplayName("a REGISTERED id cannot be activated again; the waiting scenario is unchanged")
  void registeredIdConflicts() {
    final ChaosActivationHandle waiting =
        runtime.activate(scenario("same", ChaosEffect.reject("a"), 0, ActivationPolicy.manual()));

    assertThatThrownBy(
            () ->
                runtime.activate(
                    scenario("same", ChaosEffect.reject("b"), 0, ActivationPolicy.always())))
        .isInstanceOf(ChaosActivationException.class);

    assertThat(waiting.state()).isEqualTo(ChaosDiagnostics.ScenarioState.REGISTERED);
    waiting.start();
    assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
        .hasMessageContaining("a");
  }

  @Test
  @DisplayName("an INACTIVE id is replaced by a new activation")
  void inactiveIdIsReplaced() {
    final ActivationPolicy once =
        new ActivationPolicy(
            ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0L, 1L, null, null, 0L, false);
    runtime.activate(scenario("spent", ChaosEffect.suppress(), 0, once));
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    assertThat(runtime.diagnostics().scenario("spent").orElseThrow().state())
        .isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);

    runtime.activate(scenario("spent", ChaosEffect.reject("again"), 0, ActivationPolicy.always()));

    assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
        .hasMessageContaining("again");
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
        .activationPolicy(policy)
        .build();
  }
}

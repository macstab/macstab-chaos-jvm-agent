package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link DefaultChaosActivationHandle#destroy()} calls {@link
 * ScenarioRegistry#unregister(ScenarioController)} so that closed session scenarios do not
 * accumulate indefinitely in the registry.
 */
@DisplayName("Registry cleanup on session close")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class RegistryCleanupTest {

  @Test
  @DisplayName("session scenario is removed from registry diagnostics after session close")
  void sessionScenarioRemovedFromRegistryAfterClose() {
    try (ChaosRuntime runtime = new ChaosRuntime()) {

      try (final var session = runtime.openSession("cleanup-test")) {
        session.activate(
            ChaosScenario.builder("session-cleanup")
                .scope(ChaosScenario.ScenarioScope.SESSION)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());

        // Confirm it appears in diagnostics while the session is open.
        final boolean appearsInDiagnostics =
            runtime.diagnostics().snapshot().scenarios().stream()
                .anyMatch(r -> "session-cleanup".equals(r.id()));
        assertThat(appearsInDiagnostics).as("scenario in diagnostics before close").isTrue();
      }
      // After close() the handle's destroy() must have called registry.unregister().
      final boolean stillInDiagnostics =
          runtime.diagnostics().snapshot().scenarios().stream()
              .anyMatch(r -> "session-cleanup".equals(r.id()));
      assertThat(stillInDiagnostics).as("scenario removed from registry after close").isFalse();
    }
  }

  @Test
  @DisplayName("the sessions with scenarios follow register, replace and unregister")
  void sessionsWithScenariosFollowTheRegistry() {
    final ChaosScenario scenario =
        ChaosScenario.builder("session-scenario")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final String sessionId;
      final String otherId;

      try (final var session = runtime.openSession("with-scenario");
          final var other = runtime.openSession("without-scenario")) {
        sessionId = session.id();
        otherId = other.id();
        assertThat(runtime.registry().hasScenarios(sessionId)).as("before activate").isFalse();

        session.activate(scenario).stop();
        assertThat(runtime.registry().hasScenarios(sessionId)).as("registered, stopped").isTrue();

        // A stopped scenario is replaced by the next activation under its id.
        session.activate(scenario);
        assertThat(runtime.registry().hasScenarios(sessionId)).as("replaced").isTrue();
        assertThat(runtime.registry().hasScenarios(otherId)).as("other session").isFalse();
      }

      assertThat(runtime.registry().hasScenarios(sessionId)).as("after close").isFalse();
    }
  }

  @Test
  @DisplayName("JVM-scoped scenario persists in registry after stop()")
  void jvmScenarioPersistsAfterStop() {
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final var handle =
          runtime.activate(
              ChaosScenario.builder("jvm-persist")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      handle.stop();

      // stop() does NOT remove from registry — only destroy() (called by session close) does.
      final boolean inDiagnostics =
          runtime.diagnostics().snapshot().scenarios().stream()
              .anyMatch(r -> "jvm-persist".equals(r.id()));
      assertThat(inDiagnostics).as("JVM scenario remains in registry after stop").isTrue();
      assertThat(handle.state())
          .as("state is STOPPED after stop()")
          .isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
    }
  }

  @Test
  @DisplayName("multiple sessions each clean up independently")
  void multipleSessionsCleanUpIndependently() {
    try (ChaosRuntime runtime = new ChaosRuntime()) {

      try (final var sessionA = runtime.openSession("session-a")) {
        sessionA.activate(
            ChaosScenario.builder("scenario-a")
                .scope(ChaosScenario.ScenarioScope.SESSION)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());

        try (final var sessionB = runtime.openSession("session-b")) {
          sessionB.activate(
              ChaosScenario.builder("scenario-b")
                  .scope(ChaosScenario.ScenarioScope.SESSION)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

          // Both present while both sessions are open.
          final ChaosDiagnostics.Snapshot snap1 = runtime.diagnostics().snapshot();
          assertThat(snap1.scenarios().stream().anyMatch(r -> "scenario-a".equals(r.id())))
              .isTrue();
          assertThat(snap1.scenarios().stream().anyMatch(r -> "scenario-b".equals(r.id())))
              .isTrue();
        }

        // sessionB closed: scenario-b must be removed; scenario-a must remain.
        final ChaosDiagnostics.Snapshot snap2 = runtime.diagnostics().snapshot();
        assertThat(snap2.scenarios().stream().anyMatch(r -> "scenario-a".equals(r.id()))).isTrue();
        assertThat(snap2.scenarios().stream().anyMatch(r -> "scenario-b".equals(r.id()))).isFalse();
      }

      // sessionA closed: scenario-a must now also be removed.
      final ChaosDiagnostics.Snapshot snap3 = runtime.diagnostics().snapshot();
      assertThat(snap3.scenarios().stream().anyMatch(r -> "scenario-a".equals(r.id()))).isFalse();
    }
  }

  /**
   * The same id registered in several scopes: lookup by bare id and the equal-precedence order are
   * decided by the scope key ({@code "jvm"} before {@code "session:…"}, sessions by key), never by
   * the registry's hash order. The keys are chosen and registered in both orders, so a hash order
   * that puts a session first is among the cases.
   */
  @Test
  @DisplayName("the same id in several scopes resolves to the JVM's, then the smallest session key")
  void sameIdResolvesByScopeKey() {
    for (final List<String> scopes :
        List.of(
            List.of("jvm", "session:a", "session:zz"),
            List.of("session:zz", "session:a", "jvm"),
            List.of("session:9f3", "jvm", "session:0b1"),
            List.of("session:x7", "session:q", "jvm"))) {
      final ScenarioRegistry registry = new ScenarioRegistry(Clock.systemUTC(), Map::of);
      for (final String scope : scopes) {
        registry.register(controller(scope));
      }

      assertThat(registry.scenario("shared").orElseThrow().scopeKey())
          .as("registered as %s", scopes)
          .isEqualTo("jvm");
      assertThat(
              Arrays.stream(registry.candidates(OperationType.EXECUTOR_SUBMIT))
                  .map(ScenarioController::scopeKey)
                  .toList())
          .as("match order of %s", scopes)
          .isEqualTo(scopes.stream().sorted().toList());
    }
  }

  @Test
  @DisplayName("without a JVM scenario the id resolves to the smallest session key")
  void sessionsOnlyResolveToTheSmallestKey() {
    for (final List<String> scopes :
        List.of(
            List.of("session:b", "session:a"),
            List.of("session:a", "session:b"),
            List.of("session:x7", "session:q", "session:0b1"))) {
      final ScenarioRegistry registry = new ScenarioRegistry(Clock.systemUTC(), Map::of);
      for (final String scope : scopes) {
        registry.register(controller(scope));
      }

      assertThat(registry.scenario("shared").orElseThrow().scopeKey())
          .as("registered as %s", scopes)
          .isEqualTo(scopes.stream().sorted().findFirst().orElseThrow());
    }
  }

  @Test
  @DisplayName("an Error from the re-weave of a registration leaves the registry as it was")
  void errorFromTheReweaveLeavesNoController() {
    final ScenarioRegistry registry = new ScenarioRegistry(Clock.systemUTC(), Map::of);
    final ScenarioController finished = methodController("before");
    registry.register(finished);
    finished.stop();
    final LinkageError linkage = new LinkageError("retransformation failed");
    registry.onMethodSelectorsChanged(
        selectors -> {
          throw linkage;
        });
    final ScenarioController failing = methodController("call");

    assertThatThrownBy(() -> registry.register(failing)).isSameAs(linkage);

    assertThat(registry.isLive(failing.key())).as("no live controller under the key").isFalse();
    assertThat(registry.controllers()).as("the finished one is kept").containsExactly(finished);
    assertThat(registry.candidates(OperationType.METHOD_ENTER)).containsExactly(finished);
  }

  private static ScenarioController methodController(final String method) {
    return new ScenarioController(
        ChaosScenario.builder("woven")
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.METHOD_ENTER),
                    NamePattern.exact("com.example.Service"),
                    NamePattern.exact(method)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build(),
        "jvm",
        null,
        Clock.systemUTC(),
        new ObservabilityBus(com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP),
        java.util.Optional::empty);
  }

  private static ScenarioController controller(final String scopeKey) {
    return new ScenarioController(
        ChaosScenario.builder("shared")
            .scope(
                scopeKey.equals("jvm")
                    ? ChaosScenario.ScenarioScope.JVM
                    : ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build(),
        scopeKey,
        scopeKey.equals("jvm") ? null : scopeKey.substring("session:".length()),
        Clock.systemUTC(),
        new ObservabilityBus(com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP),
        java.util.Optional::empty);
  }
}

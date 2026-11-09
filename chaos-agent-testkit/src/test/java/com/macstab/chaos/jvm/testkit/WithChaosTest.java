package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ChaosAgentExtension.class)
@WithChaos(resource = "classpath:chaos/with-chaos-plan.json")
@DisplayName("@WithChaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WithChaosTest {

  /** Set by the test whose method-level scenario a later test expects to be gone. */
  private static final AtomicBoolean METHOD_SCENARIO_RAN = new AtomicBoolean();

  @Test
  @DisplayName("activates a class-level resource plan for the test")
  void classLevelResource(final ChaosControlPlane controlPlane) {
    ChaosAssertions.assertThat(controlPlane.diagnostics())
        .hasNoFailuresFor("wc-class-jvm")
        .scenario("wc-class-jvm")
        .hasState(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @Order(1)
  @WithChaos(
      """
      {"id": "wc-method-session", "scope": "SESSION",
       "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
       "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
      """)
  @DisplayName("activates an inline single scenario and binds the test thread to the session")
  void methodLevelInlineScenario(final ChaosControlPlane controlPlane) throws Exception {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    ChaosAssertions.assertThat(controlPlane.diagnostics())
        .scenario("wc-method-session")
        .applied()
        .atLeast(1);
    METHOD_SCENARIO_RAN.set(true);
  }

  @Test
  @Order(2)
  @DisplayName("method-level scenarios of an earlier test are gone")
  void methodScenariosEndWithTheirTest(final ChaosControlPlane controlPlane) {
    // Ordered after the test that activates it; run alone, there is nothing to check.
    assumeThat(METHOD_SCENARIO_RAN).as("the test that activated it ran first").isTrue();
    final ChaosDiagnostics.ScenarioReport report =
        controlPlane.diagnostics().scenario("wc-method-session").orElse(null);
    if (report != null) {
      assertThat(report.state()).isNotEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }
  }

  @Nested
  @DisplayName("in a @Nested class")
  class InANestedClass {

    @Test
    @DisplayName("the outer class's scenarios apply to the nested test")
    void outerScenariosApply(final ChaosControlPlane controlPlane) {
      assertThat(controlPlane.diagnostics().scenario("wc-class-jvm")).isPresent();
    }
  }

  @Nested
  @DisplayName("WithChaosSupport")
  class Support {

    @Test
    @DisplayName("rejects an annotation with neither or both JSON sources")
    void rejectsAmbiguousSource() throws NoSuchMethodException {
      final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
      try (ChaosSession session = controlPlane.openSession("support")) {
        assertThatThrownBy(
                () ->
                    WithChaosSupport.activate(
                        Fixtures.class.getDeclaredMethod("empty"), controlPlane, session))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");
        assertThatThrownBy(
                () ->
                    WithChaosSupport.activate(
                        Fixtures.class.getDeclaredMethod("both"), controlPlane, session))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");
      }
    }

    @Test
    @DisplayName("names a missing resource")
    void missingResource() throws NoSuchMethodException {
      final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
      try (ChaosSession session = controlPlane.openSession("support")) {
        assertThatThrownBy(
                () ->
                    WithChaosSupport.activate(
                        Fixtures.class.getDeclaredMethod("missing"), controlPlane, session))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nope.json");
      }
    }

    @Test
    @DisplayName("activates repeated annotations and rolls back every handle when one fails")
    void rollsBackOnFailure() throws NoSuchMethodException {
      final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
      try (ChaosSession session = controlPlane.openSession("support")) {
        assertThatThrownBy(
                () ->
                    WithChaosSupport.activate(
                        Fixtures.class.getDeclaredMethod("secondInvalid"), controlPlane, session))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing or null value: selector")
            .hasCauseInstanceOf(com.macstab.chaos.jvm.startup.ConfigLoadException.class);
        // Rolled back: unregistered, or at least no longer active.
        assertThat(
                controlPlane
                    .diagnostics()
                    .scenario("wc-rollback")
                    .filter(r -> r.state() == ChaosDiagnostics.ScenarioState.ACTIVE))
            .isEmpty();

        final List<ChaosActivationHandle> handles =
            WithChaosSupport.activate(
                Fixtures.class.getDeclaredMethod("repeated"), controlPlane, session);
        assertThat(handles).hasSize(2);
        WithChaosSupport.close(handles);
        final List<ChaosActivationHandle> fromArray =
            WithChaosSupport.activate(
                Fixtures.class.getDeclaredMethod("arrayOfScenarios"), controlPlane, session);
        assertThat(fromArray).hasSize(1);
        WithChaosSupport.close(fromArray);
        assertThat(handles).isEmpty();
      }
    }
  }

  @SuppressWarnings("unused")
  static final class Fixtures {
    private static final String SCENARIO =
        """
        {"id": "%s", "scope": "SESSION",
         "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
         "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
        """;

    @WithChaos
    void empty() {}

    @WithChaos(value = "{}", resource = "x.json")
    void both() {}

    @WithChaos(resource = "/chaos/nope.json")
    void missing() {}

    @WithChaos(
        """
        {"id": "wc-rollback", "scope": "SESSION",
         "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
         "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
        """)
    @WithChaos("{\"id\": \"wc-broken\"}")
    void secondInvalid() {}

    @WithChaos(
        """
        [{"id": "wc-array-scenarios-1", "scope": "SESSION",
          "description": "an id or text that mentions \\"scenarios\\" is still one scenario",
          "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
          "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]
        """)
    void arrayOfScenarios() {}

    @WithChaos(
        """
        {"id": "wc-repeat-a", "scope": "SESSION",
         "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
         "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
        """)
    @WithChaos(
        """
        {"metadata": {"name": "b"}, "scenarios": [
          {"id": "wc-repeat-b", "scope": "SESSION",
           "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
           "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]}
        """)
    void repeated() {}
  }
}

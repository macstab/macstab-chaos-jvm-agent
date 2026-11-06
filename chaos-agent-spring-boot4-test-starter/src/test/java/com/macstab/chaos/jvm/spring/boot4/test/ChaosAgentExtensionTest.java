package com.macstab.chaos.jvm.spring.boot4.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ChaosAgentExtension.class)
@DisplayName("ChaosAgentExtension (Spring Boot 4)")
class ChaosAgentExtensionTest {

  private static ChaosSession outerSession;
  private static ChaosActivationHandle beanScenario;

  @BeforeAll
  static void arm(final ChaosSession session) {
    outerSession = session;
    // What a context bean activates must outlive the nested classes of a class still running.
    beanScenario =
        ChaosAgentExtension.contextPlane(ChaosPlatform.installLocally())
            .activate(
                ChaosScenario.builder("outer-bean")
                    .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .build());
  }

  @AfterAll
  static void beanScenarioOutlivesTheNestedClasses() {
    assertThat(beanScenario.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Nested
  @DisplayName("nested classes")
  class NestedClasses {

    @Test
    @DisplayName("a nested class shares the outer class's session")
    void sharesTheOuterSession(final ChaosSession session) {
      assertThat(session).isSameAs(outerSession);
    }
  }

  @Nested
  @DisplayName("parameter injection")
  class ParameterInjection {

    @Test
    @DisplayName("injects ChaosSession as parameter")
    void injectsChaosSession(final ChaosSession session) {
      assertThat(session).isNotNull();
    }

    @Test
    @DisplayName("injects ChaosControlPlane as parameter")
    void injectsChaosControlPlane(final ChaosControlPlane controlPlane) {
      assertThat(controlPlane).isNotNull();
    }

    @Test
    @DisplayName("session id is non-null")
    void sessionIdIsNonNull(final ChaosSession session) {
      assertThat(session.id()).isNotNull();
    }
  }

  @Nested
  @DisplayName("session lifecycle")
  class SessionLifecycle {

    @Test
    @DisplayName("session is open and usable during test execution")
    void sessionIsOpenDuringTest(final ChaosSession session) {
      // A closed session refuses activations; an open one runs them.
      try (ChaosActivationHandle handle =
          session.activate(
              ChaosScenario.builder("open-session")
                  .scope(ChaosScenario.ScenarioScope.SESSION)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .build())) {
        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      }
    }

    @Test
    @DisplayName("same session instance is shared across tests in the class")
    void sessionIsStableAcrossTests(final ChaosSession session) {
      assertThat(session).isSameAs(SessionLifecycle.capturedSession(session));
    }

    private static volatile ChaosSession captured;

    private static ChaosSession capturedSession(final ChaosSession candidate) {
      if (captured == null) {
        captured = candidate;
      }
      return captured;
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("session close after class")
  class AfterAllLifecycle {

    @Test
    @DisplayName("session bind works within the class")
    void sessionBindWorks(final ChaosSession session) {
      try (ChaosSession.ScopeBinding binding = session.bind()) {
        assertThat(binding).isNotNull();
      }
    }
  }
}

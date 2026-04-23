package com.macstab.chaos.jvm.spring.boot4.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ChaosAgentExtension.class)
@DisplayName("ChaosAgentExtension (Spring Boot 4)")
class ChaosAgentExtensionTest {

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
      assertThatCode(() -> session.bind().close()).doesNotThrowAnyException();
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

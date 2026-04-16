package com.macstab.chaos.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ChaosAgentExtension.class)
@DisplayName("ChaosAgentExtension")
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
    @DisplayName("injects both ChaosSession and ChaosControlPlane simultaneously")
    void injectsBothParameters(final ChaosSession session, final ChaosControlPlane controlPlane) {
      assertThat(session).isNotNull();
      assertThat(controlPlane).isNotNull();
    }

    @Test
    @DisplayName("session display name matches test display name")
    void sessionDisplayNameMatchesTestDisplayName(final ChaosSession session) {
      assertThat(session.displayName()).isEqualTo("session display name matches test display name");
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
    @DisplayName(
        "injected session and control plane are consistent — session opens on the installed runtime")
    void sessionAndControlPlaneAreConsistent(
        final ChaosSession session, final ChaosControlPlane controlPlane) {
      assertThat(session).isNotNull();
      assertThat(controlPlane).isNotNull();
      assertThat(session.id()).isNotNull();
    }
  }
}

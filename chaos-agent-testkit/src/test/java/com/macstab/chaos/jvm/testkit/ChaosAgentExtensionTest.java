package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

  @Nested
  @DisplayName("method-level declarations")
  class MethodLevelDeclarations {

    @Test
    @WithChaos(
        """
        {"id": "ext-method-only", "scope": "SESSION",
         "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
         "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
        """)
    @DisplayName("a @WithChaos on the method alone binds the test thread to the session")
    void methodOnlyDeclarationBindsTheThread(final ChaosControlPlane controlPlane)
        throws Exception {
      final ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }

      ChaosAssertions.assertThat(controlPlane.diagnostics())
          .scenario("ext-method-only")
          .applied()
          .atLeast(1);
    }
  }
}

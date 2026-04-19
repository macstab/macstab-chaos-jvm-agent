package com.macstab.chaos.spring.boot4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosScenario.ScenarioScope;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.OperationType;
import com.macstab.chaos.spring.boot.common.ChaosAutoConfiguration;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Verifies that {@link ChaosAgentEnvironmentPostProcessor} self-attaches the JVM agent with full
 * Phase 2 instrumentation before the Spring Boot application context is refreshed.
 *
 * <p>Uses {@link SpringApplication#run} (not {@link
 * org.springframework.boot.test.context.runner.ApplicationContextRunner}) so that the {@code
 * EnvironmentPostProcessor} SPI is fired by the real Spring Boot startup machinery.
 */
@DisplayName("ChaosAgentEnvironmentPostProcessor (Spring Boot 4)")
class ChaosAgentEnvironmentPostProcessorTest {

  @Test
  @DisplayName(
      "Phase 2 socket chaos fires after early attach via EnvironmentPostProcessor — macstab.chaos.enabled=true")
  void socketChaosFiresAfterEarlyAttach() throws Exception {
    final ConfigurableApplicationContext ctx =
        new SpringApplication(MinimalApp.class)
            .run("--spring.main.web-application-type=none", "--macstab.chaos.enabled=true");

    try {
      final ChaosControlPlane chaos = ctx.getBean(ChaosControlPlane.class);

      final ChaosScenario rejectConnects =
          ChaosScenario.builder("ep-test-sb4-socket-connect")
              .scope(ScenarioScope.JVM)
              .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)))
              .effect(ChaosEffect.reject("sb4-env-post-processor-chaos"))
              .activationPolicy(ActivationPolicy.always())
              .build();

      try (ChaosActivationHandle handle = chaos.activate(rejectConnects)) {
        // 192.0.2.1 is TEST-NET-1 (RFC 5737) — not routable on any real network.
        // The chaos agent intercepts the connect call before the OS sees it, so the
        // address is irrelevant; what matters is that ConnectException is thrown with
        // the injected reason string proving chaos fired rather than a real OS refusal.
        assertThatThrownBy(() -> new Socket().connect(new InetSocketAddress("192.0.2.1", 80)))
            .isInstanceOf(ConnectException.class)
            .hasMessageContaining("sb4-env-post-processor-chaos");

        assertThat(
                chaos
                    .diagnostics()
                    .scenario("ep-test-sb4-socket-connect")
                    .map(r -> r.appliedCount()))
            .hasValueSatisfying(count -> assertThat(count).isGreaterThan(0));
      }

      // After handle.close() the scenario is STOPPED — no further interception.
      assertThat(chaos.diagnostics().scenario("ep-test-sb4-socket-connect"))
          .hasValueSatisfying(
              r ->
                  assertThat(r.state())
                      .isEqualTo(com.macstab.chaos.api.ChaosDiagnostics.ScenarioState.STOPPED));
    } finally {
      ctx.close();
    }
  }

  @Test
  @DisplayName("agent is NOT attached when macstab.chaos.enabled=false (disabled path)")
  void noAttachWhenDisabled() {
    // When disabled, the EnvironmentPostProcessor is a no-op. The ChaosAutoConfiguration
    // ConditionalOnProperty gate means no ChaosControlPlane bean exists in the context,
    // confirming the disabled path does not touch the JVM agent.
    final ConfigurableApplicationContext ctx =
        new SpringApplication(MinimalApp.class)
            .run("--spring.main.web-application-type=none", "--macstab.chaos.enabled=false");
    try {
      assertThat(ctx.getBeanNamesForType(ChaosControlPlane.class)).isEmpty();
    } finally {
      ctx.close();
    }
  }

  @SpringBootConfiguration
  @Import(ChaosAutoConfiguration.class)
  static class MinimalApp {}
}

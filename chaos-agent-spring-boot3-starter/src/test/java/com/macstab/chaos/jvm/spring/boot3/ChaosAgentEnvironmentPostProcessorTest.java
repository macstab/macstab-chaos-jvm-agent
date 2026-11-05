package com.macstab.chaos.jvm.spring.boot3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.spring.boot.common.ChaosAutoConfiguration;
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
@DisplayName("ChaosAgentEnvironmentPostProcessor (Spring Boot 3)")
class ChaosAgentEnvironmentPostProcessorTest {

  @Test
  @DisplayName(
      "Phase 2 socket chaos fires after early attach via EnvironmentPostProcessor — macstab.chaos.enabled=true")
  void socketChaosFiresAfterEarlyAttach() throws Exception {
    final ConfigurableApplicationContext ctx =
        new SpringApplication(MinimalApp.class)
            .run("--spring.main.web-application-type=none", "--macstab.chaos.enabled=true");

    try {
      // Marker property is set ONLY by the EnvironmentPostProcessor, so its presence proves
      // the SPI wiring fires. Without this assertion the test would also pass if the
      // auto-configuration bean attached the agent during context refresh instead.
      assertThat(
              ctx.getEnvironment()
                  .getProperty(ChaosAgentEnvironmentPostProcessor.ATTACH_MARKER_PROPERTY))
          .isEqualTo("spring-boot-3");
      assertThat(
              ctx.getEnvironment()
                  .getPropertySources()
                  .contains(ChaosAgentEnvironmentPostProcessor.ATTACH_MARKER_SOURCE))
          .isTrue();

      final ChaosControlPlane chaos = ctx.getBean(ChaosControlPlane.class);

      final ChaosScenario rejectConnects =
          ChaosScenario.builder("ep-test-sb3-socket-connect")
              .scope(ScenarioScope.JVM)
              .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)))
              .effect(ChaosEffect.reject("sb3-env-post-processor-chaos"))
              .activationPolicy(ActivationPolicy.always())
              .build();

      try (ChaosActivationHandle handle = chaos.activate(rejectConnects)) {
        // 192.0.2.1 is TEST-NET-1 (RFC 5737) — not routable on any real network.
        // The chaos agent intercepts the connect call before the OS sees it, so the
        // address is irrelevant; what matters is that ConnectException is thrown with
        // the injected reason string proving chaos fired rather than a real OS refusal.
        assertThatThrownBy(() -> new Socket().connect(new InetSocketAddress("192.0.2.1", 80)))
            .isInstanceOf(ConnectException.class)
            .hasMessageContaining("sb3-env-post-processor-chaos");

        assertThat(
                chaos
                    .diagnostics()
                    .scenario("ep-test-sb3-socket-connect")
                    .map(r -> r.appliedCount()))
            .hasValueSatisfying(count -> assertThat(count).isGreaterThan(0));
      }

      // handle.close() stops the scenario and removes it from the registry.
      assertThat(chaos.diagnostics().scenario("ep-test-sb3-socket-connect")).isEmpty();
    } finally {
      ctx.close();
    }
  }

  @Test
  @DisplayName("early attach sees macstab.chaos.enabled set only in config data (properties file)")
  void earlyAttachSeesConfigData() {
    final ConfigurableApplicationContext ctx =
        new SpringApplication(MinimalApp.class)
            .run(
                "--spring.main.web-application-type=none",
                "--spring.config.location=classpath:chaos-enabled-by-config-data.properties");
    try {
      assertThat(
              ctx.getEnvironment()
                  .getProperty(ChaosAgentEnvironmentPostProcessor.ATTACH_MARKER_PROPERTY))
          .as("the post-processor runs after config data and attaches early")
          .isEqualTo("spring-boot-3");
    } finally {
      ctx.close();
    }
  }

  @Test
  @DisplayName("runs directly after ConfigDataEnvironmentPostProcessor")
  void ordersAfterConfigData() {
    assertThat(new ChaosAgentEnvironmentPostProcessor().getOrder())
        .isGreaterThan(
            org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER);
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
      // Conversely, the EPP must NOT have attached the agent \u2014 its marker property
      // must be absent when chaos is disabled.
      assertThat(
              ctx.getEnvironment()
                  .getProperty(ChaosAgentEnvironmentPostProcessor.ATTACH_MARKER_PROPERTY))
          .isNull();
    } finally {
      ctx.close();
    }
  }

  @SpringBootConfiguration
  @Import(ChaosAutoConfiguration.class)
  static class MinimalApp {}

  @Test
  @DisplayName("macstab.chaos.enabled=true from an untrusted source (remote config) is ignored")
  void untrustedSourceIsIgnored() {
    final org.springframework.core.env.StandardEnvironment environment =
        new org.springframework.core.env.StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "configserver:remote", java.util.Map.of("macstab.chaos.enabled", "true")));
    new ChaosAgentEnvironmentPostProcessor().postProcessEnvironment(environment, null);
    assertThat(environment.getProperty("macstab.chaos.agent.attach-phase"))
        .as("not attached: the switch came from a source an attacker could control")
        .isNull();
  }
}

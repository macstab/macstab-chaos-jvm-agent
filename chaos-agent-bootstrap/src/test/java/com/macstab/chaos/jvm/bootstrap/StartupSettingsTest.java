package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The start-up switches read from the agent arguments before the property and environment. */
@DisplayName("Start-up settings")
class StartupSettingsTest {

  @Test
  @DisplayName("the weaving mode in the agent args wins over the environment")
  void weavingModeFromArgs() {
    final Map<String, String> eagerEnvironment =
        Map.of(ChaosAgentBootstrap.INSTRUMENTATION_ENV, "eager");

    assertThat(ChaosAgentBootstrap.eagerInstrumentation("instrumentation= EAGER ", Map.of()))
        .isTrue();
    assertThat(
            ChaosAgentBootstrap.eagerInstrumentation("instrumentation=onDemand", eagerEnvironment))
        .as("the agent argument is read first")
        .isFalse();
    assertThat(ChaosAgentBootstrap.eagerInstrumentation("", eagerEnvironment)).isTrue();
    assertThat(ChaosAgentBootstrap.eagerInstrumentation("", Map.of())).isFalse();
  }

  @Test
  @DisplayName("a list in the agent args wins over the environment and drops blank entries")
  void listFromArgs() {
    final Map<String, String> environment =
        Map.of(ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ENV, "env.example");

    assertThat(
            ChaosAgentBootstrap.listSetting(
                "sessionHeaderHosts= a.example , ,b.example,",
                environment,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ARG,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_PROPERTY,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ENV))
        .containsExactly("a.example", "b.example");
    assertThat(
            ChaosAgentBootstrap.listSetting(
                "",
                environment,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ARG,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_PROPERTY,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ENV))
        .containsExactly("env.example");
    assertThat(ChaosAgentBootstrap.clockCallSites("clockCallSites=com.example", Map.of()))
        .containsExactly("com.example");
  }

  @Test
  @DisplayName("dynamic attach is opted into with any standard true literal")
  void dynamicAttachLiterals() {
    assertThat(
            ChaosAgentBootstrap.dynamicAttachAllowed(
                new java.util.Properties(),
                Map.of(ChaosAgentBootstrap.ALLOW_DYNAMIC_ATTACH_ENV, "yes")))
        .isTrue();
    final java.util.Properties properties = new java.util.Properties();
    properties.setProperty(ChaosAgentBootstrap.ALLOW_DYNAMIC_ATTACH_PROPERTY, "1");
    assertThat(ChaosAgentBootstrap.dynamicAttachAllowed(properties, Map.of())).isTrue();
    assertThat(ChaosAgentBootstrap.dynamicAttachAllowed(new java.util.Properties(), Map.of()))
        .isFalse();
  }
}

package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosManagementEndpoint (Micronaut)")
class ChaosManagementEndpointTest {

  private static final String PLAN =
      """
      {"metadata":{"name":"mn-mgmt"},"scenarios":[{"id":"mn-mgmt-delay","scope":"JVM",
       "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
       "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
      """;

  @Test
  @DisplayName("is off unless macstab.chaos.management.enabled=true")
  void offByDefault() {
    try (ApplicationContext context = ApplicationContext.run()) {
      assertThat(context.containsBean(ChaosManagementEndpoint.class)).isFalse();
    }
  }

  @Test
  @DisplayName("activates, lists and stops a plan; closing the context stops what it activated")
  void operations() {
    final ChaosControlPlane controlPlane;
    try (ApplicationContext context =
        ApplicationContext.run(Map.of("macstab.chaos.management.enabled", "true"))) {
      controlPlane = context.getBean(ChaosControlPlane.class);
      final ChaosManagementEndpoint endpoint = context.getBean(ChaosManagementEndpoint.class);

      assertThat(endpoint.activate(PLAN)).contains("\"status\":\"activated\"");
      assertThat(endpoint.snapshot()).contains("\"id\":\"mn-mgmt-delay\"");
      assertThat(endpoint.stop("mn-mgmt")).contains("\"status\":\"stopped\"");
      assertThat(endpoint.stop("mn-mgmt")).contains("\"status\":\"not-found\"");
      assertThat(endpoint.activate(PLAN)).contains("activated");
    }
    assertThat(controlPlane.diagnostics().scenario("mn-mgmt-delay")).isEmpty();
  }
}

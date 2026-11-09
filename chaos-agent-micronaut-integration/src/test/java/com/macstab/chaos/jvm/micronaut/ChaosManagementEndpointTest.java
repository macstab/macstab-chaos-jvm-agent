package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.http.HttpResponse;
import io.micronaut.runtime.server.EmbeddedServer;
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
  @DisplayName("stays off when the switch comes from a remote source (consul)")
  void offFromRemoteSource() {
    try (ApplicationContext context =
        ApplicationContext.builder()
            .propertySources(
                PropertySource.of("consul", Map.of("macstab.chaos.management.enabled", "true")))
            .start()) {
      assertThat(context.containsBean(ChaosManagementEndpoint.class)).isFalse();
    }
  }

  @Test
  @DisplayName("answers with the operation's status; closing the context stops its plans")
  void operations() {
    final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
    try (ApplicationContext context =
        ApplicationContext.run(Map.of("macstab.chaos.management.enabled", "true"))) {
      final ChaosManagementEndpoint endpoint = context.getBean(ChaosManagementEndpoint.class);

      final HttpResponse<String> activated = endpoint.activate(PLAN);
      assertThat(activated.code()).isEqualTo(200);
      assertThat(activated.body()).contains("\"status\":\"activated\"");
      assertThat(endpoint.activate(PLAN.replace("mn-mgmt\"}", "x\"}")).code()).isEqualTo(409);
      assertThat(endpoint.activate("{ broken").code()).isEqualTo(400);
      assertThat(endpoint.snapshot().body()).contains("\"id\":\"mn-mgmt-delay\"");
      assertThat(endpoint.stop("mn-mgmt").code()).isEqualTo(200);
      assertThat(endpoint.stop("mn-mgmt").code()).isEqualTo(404);
      assertThat(endpoint.activate(PLAN).code()).isEqualTo(200);
      assertThat(endpoint.stopAll().code()).isEqualTo(200);
      endpoint.activate(PLAN);
    }
    assertThat(controlPlane.diagnostics().scenario("mn-mgmt-delay")).isEmpty();
  }

  @Test
  @DisplayName("stops over HTTP a plan whose name holds characters a URL escapes")
  void stopsAnEscapedName() throws Exception {
    ChaosPlatform.installLocally();
    try (EmbeddedServer server =
        ApplicationContext.run(
            EmbeddedServer.class,
            Map.of(
                "macstab.chaos.management.enabled", "true",
                "endpoints.chaos.sensitive", "false",
                "micronaut.server.port", "-1"))) {
      final ChaosManagementEndpoint endpoint =
          server.getApplicationContext().getBean(ChaosManagementEndpoint.class);
      assertThat(endpoint.activate(PLAN.replace("\"mn-mgmt\"", "\"50%+off\"")).code())
          .isEqualTo(200);

      // The client escapes '%' and '+'; Micronaut decodes the path before the endpoint sees it.
      final java.net.http.HttpResponse<String> stopped;
      try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
        stopped =
            client.send(
                java.net.http.HttpRequest.newBuilder(server.getURI().resolve("/chaos/50%25%2Boff"))
                    .DELETE()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
      }

      assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
      assertThat(stopped.body())
          .contains("\"status\":\"stopped\"")
          .contains("\"scenarioId\":\"50%+off\"");
    }
  }

  @Test
  @DisplayName("stops a plan whose name holds a space")
  void stopsANameWithASpace() {
    ChaosPlatform.installLocally();
    try (ApplicationContext context =
        ApplicationContext.run(Map.of("macstab.chaos.management.enabled", "true"))) {
      final ChaosManagementEndpoint endpoint = context.getBean(ChaosManagementEndpoint.class);
      assertThat(endpoint.activate(PLAN.replace("\"mn-mgmt\"", "\"a b\"")).code()).isEqualTo(200);

      assertThat(endpoint.stop("a b").code()).isEqualTo(200);
    }
  }

  @Test
  @DisplayName("answers 503 instead of installing the agent")
  void neverInstallsTheAgent() {
    final ChaosManagementEndpoint endpoint = new ChaosManagementEndpoint(java.util.Optional::empty);
    assertThat(endpoint.snapshot().code()).isEqualTo(503);
    endpoint.close();
  }

  @Test
  @DisplayName("lets an IllegalStateException of an installed control plane through, not as 503")
  void installedPlaneFailureIsNotNotInstalled() {
    final ChaosControlPlane closed =
        (ChaosControlPlane)
            java.lang.reflect.Proxy.newProxyInstance(
                ChaosControlPlane.class.getClassLoader(),
                new Class<?>[] {ChaosControlPlane.class},
                (proxy, method, args) -> {
                  throw new IllegalStateException("control plane is closed");
                });
    final ChaosManagementEndpoint endpoint =
        new ChaosManagementEndpoint(() -> java.util.Optional.of(closed));

    assertThatThrownBy(endpoint::snapshot)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("control plane is closed");
  }
}

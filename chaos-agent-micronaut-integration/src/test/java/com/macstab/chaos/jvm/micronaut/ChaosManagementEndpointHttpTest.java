package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code /chaos} on a real Micronaut server, called over HTTP as an operator would: refused while
 * the endpoint keeps its sensitive default, and with {@code sensitive=false} a POSTed plan rejects
 * a real {@code Socket.connect}, GET lists it, DELETE lifts it, and an oversized plan is refused.
 */
@DisplayName("ChaosManagementEndpoint over HTTP (Micronaut)")
class ChaosManagementEndpointHttpTest {

  private static final String SCENARIO_ID = "mn-http-reject";

  private final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();

  @Test
  @DisplayName("is sensitive by default: an anonymous POST and GET are refused with 401")
  void sensitiveByDefault() throws Exception {
    try (EmbeddedServer server = start(Map.of());
        HttpClient client = HttpClient.newHttpClient()) {
      final HttpResponse<String> posted = post(client, server.getURI(), plan(9));
      final HttpResponse<String> read = get(client, server.getURI());

      assertThat(posted.statusCode()).as(posted.body()).isEqualTo(401);
      assertThat(read.statusCode()).as(read.body()).isEqualTo(401);
    }
    assertThat(controlPlane.diagnostics().scenario(SCENARIO_ID)).isEmpty();
  }

  @Test
  @DisplayName("POST activates a plan that rejects a real connect; GET lists it; DELETE lifts it")
  void activatesListsAndStops() throws Exception {
    try (EmbeddedServer server = start(Map.of("endpoints.chaos.sensitive", "false"));
        HttpClient client = HttpClient.newHttpClient();
        ServerSocket target = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      final URI base = server.getURI();
      connect(target);

      final HttpResponse<String> activated = post(client, base, plan(target.getLocalPort()));
      assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
      assertThat(activated.body())
          .contains("\"status\":\"activated\"")
          .contains("\"handleId\":\"plan:mn-http\"");

      assertThatThrownBy(() -> connect(target)).isInstanceOf(ConnectException.class);
      assertThat(controlPlane.diagnostics().scenario(SCENARIO_ID).orElseThrow().appliedCount())
          .as("the scenario activated over HTTP rejected the connect")
          .isPositive();

      final HttpResponse<String> listed = get(client, base);
      assertThat(listed.statusCode()).isEqualTo(200);
      assertThat(listed.body()).contains("\"id\":\"" + SCENARIO_ID + "\"").contains("ACTIVE");

      final HttpResponse<String> stopped =
          client.send(
              HttpRequest.newBuilder(base.resolve("/chaos/plan:mn-http")).DELETE().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
      connect(target);
      assertThat(controlPlane.diagnostics().scenario(SCENARIO_ID)).isEmpty();

      assertThat(post(client, base, plan(target.getLocalPort())).statusCode()).isEqualTo(200);
      final HttpResponse<String> stoppedAll =
          client.send(
              HttpRequest.newBuilder(base.resolve("/chaos")).DELETE().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(stoppedAll.statusCode()).as(stoppedAll.body()).isEqualTo(200);
      assertThat(stoppedAll.body()).contains("\"stoppedCount\":1");
      connect(target);
    }
  }

  @Test
  @DisplayName("an oversized plan is refused and activates nothing")
  void oversizedPlanIsRefused() throws Exception {
    try (EmbeddedServer server = start(Map.of("endpoints.chaos.sensitive", "false"));
        HttpClient client = HttpClient.newHttpClient()) {
      // A valid plan padded past the 1 MiB plan cap with a long description.
      final String padded =
          plan(9)
              .replace(
                  "\"scope\"", "\"description\":\"" + "x".repeat(1024 * 1024 + 1) + "\",\"scope\"");

      final HttpResponse<String> refused = post(client, server.getURI(), padded);

      assertThat(refused.statusCode()).as(refused.body()).isIn(400, 413);
    }
    assertThat(controlPlane.diagnostics().scenario(SCENARIO_ID)).isEmpty();
  }

  private static EmbeddedServer start(final Map<String, Object> properties) {
    final Map<String, Object> all = new HashMap<>(properties);
    all.put("macstab.chaos.management.enabled", "true");
    all.put("micronaut.server.port", "-1");
    return ApplicationContext.run(EmbeddedServer.class, all);
  }

  /** A plan rejecting connects to {@code port} only: the test's own requests connect too. */
  private static String plan(final int port) {
    return """
        {"metadata":{"name":"mn-http"},"scenarios":[{"id":"%s","scope":"JVM",
         "selector":{"type":"network","operations":["SOCKET_CONNECT"],"remotePort":%d},
         "effect":{"type":"reject","message":"chaos: target unreachable"}}]}
        """
        .formatted(SCENARIO_ID, port);
  }

  private static HttpResponse<String> post(
      final HttpClient client, final URI base, final String planJson) throws Exception {
    final String body =
        "{\"planJson\": \""
            + planJson.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
            + "\"}";
    return client.send(
        HttpRequest.newBuilder(base.resolve("/chaos"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(final HttpClient client, final URI base)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(base.resolve("/chaos")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** A plain blocking connect to the target; the backlog completes it without an accept. */
  private static void connect(final ServerSocket target) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(target.getInetAddress(), target.getLocalPort()), 2_000);
    }
  }
}

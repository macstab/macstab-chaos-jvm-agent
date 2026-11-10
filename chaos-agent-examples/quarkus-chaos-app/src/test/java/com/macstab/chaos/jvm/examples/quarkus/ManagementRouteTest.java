package com.macstab.chaos.jvm.examples.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code /q/chaos} in a booted Quarkus application (the extension's build steps, the agent attached
 * at runtime-init, the route on the real HTTP server): a POSTed plan rejects a real {@code
 * Socket.connect}, GET lists it, DELETE by plan id lifts it, and DELETE of the route stops
 * everything activated through it.
 */
@QuarkusTest
@DisplayName("/q/chaos in a booted Quarkus application")
class ManagementRouteTest {

  private static final String SCENARIO_ID = "qk-route-reject";

  @TestHTTPResource("/q/chaos")
  URI route;

  @Inject ChaosControlPlane controlPlane;

  @Test
  @DisplayName("POST activates a plan that rejects a real connect; GET lists it; DELETE lifts it")
  void activatesListsAndStops() throws Exception {
    try (HttpClient client = HttpClient.newHttpClient();
        ServerSocket target = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      connect(target);

      final HttpResponse<String> activated =
          client.send(
              HttpRequest.newBuilder(route)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(plan(target.getLocalPort())))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
      assertThat(activated.body()).contains("\"handleId\":\"plan:qk-route\"");

      assertThatThrownBy(() -> connect(target)).isInstanceOf(ConnectException.class);
      assertThat(controlPlane.diagnostics().scenario(SCENARIO_ID).orElseThrow().appliedCount())
          .as("the scenario activated over HTTP rejected the connect")
          .isPositive();

      final HttpResponse<String> listed =
          client.send(
              HttpRequest.newBuilder(route).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertThat(listed.statusCode()).isEqualTo(200);
      assertThat(listed.body()).contains("\"id\":\"" + SCENARIO_ID + "\"").contains("ACTIVE");

      final HttpResponse<String> stopped =
          client.send(
              HttpRequest.newBuilder(URI.create(route + "/plan%3Aqk-route")).DELETE().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
      connect(target);

      client.send(
          HttpRequest.newBuilder(route)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(plan(target.getLocalPort())))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      final HttpResponse<String> stoppedAll =
          client.send(
              HttpRequest.newBuilder(route).DELETE().build(), HttpResponse.BodyHandlers.ofString());
      assertThat(stoppedAll.statusCode()).as(stoppedAll.body()).isEqualTo(200);
      assertThat(stoppedAll.body()).contains("\"stoppedCount\":1");
      connect(target);
    }
  }

  /** A plan rejecting connects to {@code port} only: the test's own requests connect too. */
  private static String plan(final int port) {
    return """
        {"metadata":{"name":"qk-route"},"scenarios":[{"id":"%s","scope":"JVM",
         "selector":{"type":"network","operations":["SOCKET_CONNECT"],"remotePort":%d},
         "effect":{"type":"reject","message":"chaos: target unreachable"}}]}
        """
        .formatted(SCENARIO_ID, port);
  }

  /** A plain blocking connect to the target; the backlog completes it without an accept. */
  private static void connect(final ServerSocket target) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(target.getInetAddress(), target.getLocalPort()), 2_000);
    }
  }
}

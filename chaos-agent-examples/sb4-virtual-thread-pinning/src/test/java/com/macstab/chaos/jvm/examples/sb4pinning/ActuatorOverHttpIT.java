package com.macstab.chaos.jvm.examples.sb4pinning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import java.io.ByteArrayInputStream;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code /actuator/chaos} on a real Spring Boot 4 server (Tomcat, the Boot 4 MVC actuator): a plan
 * POSTed over HTTP rejects a real {@code Socket.connect}, GET lists it, DELETE by plan id lifts it,
 * DELETE of the endpoint stops everything activated through it, and a body above the cap is refused
 * with 413 before it is bound, with and without {@code Content-Length}.
 */
@ChaosTest(
    classes = VirtualThreadPinningApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("/actuator/chaos over HTTP (Spring Boot 4)")
class ActuatorOverHttpIT {

  private static final String SCENARIO_ID = "reject-target-connects";

  /**
   * Just above ChaosActuatorBodyLimit.MAX_BODY_BYTES (2 MiB + 64 bytes) with the JSON around it.
   */
  private static final int OVERSIZED = 2 * 1024 * 1024 + 64;

  private final HttpClient client = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  @Test
  @DisplayName("POST activates a plan that rejects a real connect; GET lists it; DELETE lifts it")
  void activatesListsAndStops(final ChaosControlPlane chaos) throws Exception {
    try (ServerSocket target = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      connect(target);

      final HttpResponse<String> activated = post(plan(target.getLocalPort()));
      assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
      assertThat(activated.body())
          .contains("\"status\":\"activated\"")
          .contains("\"handleId\":\"plan:target-down\"");

      assertThatThrownBy(() -> connect(target)).isInstanceOf(ConnectException.class);
      assertThat(chaos.diagnostics().scenario(SCENARIO_ID).orElseThrow().appliedCount())
          .as("the scenario activated over HTTP rejected the connect")
          .isPositive();

      final HttpResponse<String> listed = send(request("").GET());
      assertThat(listed.statusCode()).isEqualTo(200);
      assertThat(listed.body()).contains("\"id\":\"" + SCENARIO_ID + "\"").contains("ACTIVE");

      final HttpResponse<String> stopped = send(request("/plan:target-down").DELETE());
      assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
      assertThat(stopped.body()).contains("\"status\":\"stopped\"");
      connect(target);
      assertThat(chaos.diagnostics().scenario(SCENARIO_ID)).isEmpty();

      assertThat(post(plan(target.getLocalPort())).statusCode()).isEqualTo(200);
      final HttpResponse<String> stoppedAll = send(request("").DELETE());
      assertThat(stoppedAll.statusCode()).as(stoppedAll.body()).isEqualTo(200);
      assertThat(stoppedAll.body()).contains("\"stoppedCount\":1");
      connect(target);
    }
  }

  @Test
  @DisplayName("a body above the cap is refused with 413, with and without Content-Length")
  void oversizedBodyIsRefused(final ChaosControlPlane chaos) throws Exception {
    final int before = chaos.diagnostics().snapshot().scenarios().size();
    final byte[] body = oversizedBody();

    // Decided from Content-Length before the body is read; the body itself is not sent.
    final String sizedStatus = statusLineForAnnouncedLength(body.length);
    // ofInputStream has no known length: the body is sent chunked, without Content-Length.
    final HttpResponse<String> chunked =
        send(
            request("")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(body))));

    assertThat(sizedStatus).startsWith("HTTP/1.1 413");
    assertThat(chunked.statusCode()).isEqualTo(413);
    assertThat(chaos.diagnostics().snapshot().scenarios()).hasSize(before);
  }

  private static String plan(final int targetPort) {
    // The target's port only: the test's own requests to the application connect too.
    return """
        {"metadata": {"name": "target-down"}, "scenarios": [{"id": "%s", "scope": "JVM",
          "selector": {"type": "network", "operations": ["SOCKET_CONNECT"], "remotePort": %d},
          "effect": {"type": "reject", "message": "chaos: target unreachable"}}]}
        """
        .formatted(SCENARIO_ID, targetPort);
  }

  /** {@code {"planJson": "xxx…"}}, larger than the endpoint accepts. */
  private static byte[] oversizedBody() {
    final char[] filler = new char[OVERSIZED];
    Arrays.fill(filler, 'x');
    return ("{\"planJson\": \"" + new String(filler) + "\"}").getBytes(StandardCharsets.UTF_8);
  }

  private HttpResponse<String> post(final String planJson) throws Exception {
    final String body =
        "{\"planJson\": \""
            + planJson.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
            + "\"}";
    return send(
        request("")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private HttpRequest.Builder request(final String path) {
    return HttpRequest.newBuilder(
        URI.create("http://localhost:" + port + "/actuator/chaos" + path));
  }

  private HttpResponse<String> send(final HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Sends the head of a POST and the first byte of its body, announcing {@code contentLength}
   * bytes, and returns the status line of the answer.
   */
  private String statusLineForAnnouncedLength(final int contentLength) throws IOException {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout(10_000);
      final String head =
          "POST /actuator/chaos HTTP/1.1\r\n"
              + "Host: localhost:"
              + port
              + "\r\n"
              + "Content-Type: application/json\r\n"
              + "Content-Length: "
              + contentLength
              + "\r\n"
              + "Connection: close\r\n\r\n"
              // Spring reads one byte to tell an empty body from a present one before the cap
              // is checked; the rest is never sent.
              + "{";
      socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      final java.io.BufferedReader reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      final String statusLine = reader.readLine();
      return statusLine == null ? "" : statusLine;
    }
  }

  /** A plain blocking connect to the target; the backlog completes it without an accept. */
  private static void connect(final ServerSocket target) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(target.getInetAddress(), target.getLocalPort()), 2_000);
    }
  }
}

package com.macstab.chaos.jvm.quarkus.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The handler behind a real Vert.x HTTP server, as Quarkus mounts it: a router without a body
 * handler, requests sent byte by byte over a socket.
 */
@DisplayName("ChaosManagementHandler over HTTP")
class ChaosManagementHttpTest {

  private final AtomicBoolean allowAnonymous = new AtomicBoolean(false);
  private final ChaosManagementHandler handler =
      new ChaosManagementHandler(
          "/q/chaos", () -> true, allowAnonymous::get, java.util.Optional::empty);
  private final Vertx vertx = Vertx.vertx();
  private int port;

  @BeforeEach
  void startServer() throws Exception {
    final Router router = Router.router(vertx);
    router.route("/q/chaos").blockingHandler(handler, false);
    router.route("/q/chaos/*").blockingHandler(handler, false);
    final HttpServer server =
        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    port = server.actualPort();
  }

  @AfterEach
  void stopServer() throws Exception {
    handler.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("answers a refused POST before its body is sent: the body is unread when it runs")
  void bodyUnreadWhenHandled() throws IOException {
    try (RawHttp http = new RawHttp(port)) {
      // The headers declare a body that is never sent: an answer proves nobody waited for it.
      http.send(
          RawHttp.head(
              "POST", "/q/chaos", "Content-Type: application/json", "Content-Length: 100000"));

      final RawHttp.Response response = http.read();

      assertThat(response.status()).isEqualTo(401);
      assertThat(response.body()).contains("authentication required");
    }
  }

  @Test
  @DisplayName("keeps the connection usable after a 401 on a POST with a body")
  void keepAliveAfterRefusedPost() throws IOException {
    final String plan = "{\"scenarios\":[]}";
    try (RawHttp http = new RawHttp(port)) {
      http.send(
          RawHttp.head(
                  "POST",
                  "/q/chaos",
                  "Content-Type: application/json",
                  "Content-Length: " + plan.length())
              + plan);
      assertThat(http.read().status()).isEqualTo(401);

      // The discarded body does not leak into the next request on the same connection.
      http.send(RawHttp.head("GET", "/q/chaos"));
      final RawHttp.Response second = http.read();

      assertThat(second.status()).isEqualTo(401);
      assertThat(second.body()).contains("authentication required");
    }
  }

  @Test
  @DisplayName("refuses a chunked plan that grows above the size cap with 413")
  void chunkedOversizeUpload() throws Exception {
    allowAnonymous.set(true);
    try (RawHttp http = new RawHttp(port)) {
      http.send(
          RawHttp.head(
              "POST", "/q/chaos", "Content-Type: application/json", "Transfer-Encoding: chunked"));
      final byte[] chunk = new byte[64 * 1024];
      Arrays.fill(chunk, (byte) ' ');
      final byte[] header =
          (Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.US_ASCII);
      final byte[] end = "\r\n".getBytes(StandardCharsets.US_ASCII);
      // The body is sent while the answer is read: the server closes the connection after it.
      final CompletableFuture<Void> upload =
          CompletableFuture.runAsync(
              () -> {
                try {
                  // 2 MiB in 64 KiB chunks, never finished: the cap is 1 MiB.
                  for (int i = 0; i < 32; i++) {
                    http.send(header);
                    http.send(chunk);
                    http.send(end);
                  }
                } catch (final IOException closedByServer) {
                  // expected once the server has answered and closed the connection
                }
              });

      final RawHttp.Response response = http.read();

      assertThat(response.status()).isEqualTo(413);
      assertThat(response.body()).contains("exceeds");
      upload.cancel(true);
    }
  }
}

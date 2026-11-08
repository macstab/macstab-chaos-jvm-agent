package com.macstab.chaos.jvm.quarkus.management;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosManagementHandler (/q/chaos)")
class ChaosManagementHandlerTest {

  private static final String PLAN =
      """
      {"metadata":{"name":"qk-mgmt"},"scenarios":[{"id":"qk-mgmt-delay","scope":"JVM",
       "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
       "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
      """;

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final AtomicBoolean enabled = new AtomicBoolean(true);
  private final AtomicBoolean allowAnonymous = new AtomicBoolean(false);
  private final AtomicReference<io.vertx.ext.auth.User> user =
      new AtomicReference<>(new QuarkusHttpUser(identity(false)));
  private final AtomicReference<Uni<SecurityIdentity>> deferredIdentity = new AtomicReference<>();
  private final Vertx vertx = Vertx.vertx();
  private final ChaosManagementHandler handler =
      new ChaosManagementHandler(
          "/q/chaos", enabled::get, allowAnonymous::get, () -> java.util.Optional.of(runtime));

  @AfterEach
  void tearDown() throws Exception {
    handler.close();
    runtime.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static SecurityIdentity identity(final boolean anonymous) {
    return (SecurityIdentity)
        Proxy.newProxyInstance(
            SecurityIdentity.class.getClassLoader(),
            new Class<?>[] {SecurityIdentity.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "isAnonymous" -> anonymous;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  /** What the handler did with one request. */
  private static final class Exchange {
    final CountDownLatch answered = new CountDownLatch(1);
    final CountDownLatch settled = new CountDownLatch(1);
    final AtomicInteger status = new AtomicInteger();
    final AtomicReference<String> json = new AtomicReference<>();
    final AtomicBoolean bodyRead = new AtomicBoolean();
    final AtomicBoolean discarded = new AtomicBoolean();
    final AtomicBoolean closed = new AtomicBoolean();

    int status() {
      return status.get();
    }

    String body() {
      return json.get();
    }

    /** Waits until the unread body was discarded or the connection closed. */
    Exchange settle() throws InterruptedException {
      assertThat(settled.await(5, TimeUnit.SECONDS))
          .as("the unread body is discarded or the connection is closed")
          .isTrue();
      return this;
    }
  }

  private static Map<String, String> json() {
    return Map.of("content-type", "application/json");
  }

  /** A request whose body a body handler already read, or none. */
  private Exchange call(final HttpMethod method, final String path, final String preReadBody)
      throws InterruptedException {
    return exchange(method, path, json(), preReadBody, List.of(), false);
  }

  /** A request whose body still has to be read from the connection, in the given chunks. */
  private Exchange stream(
      final String path, final Map<String, String> headers, final List<String> chunks)
      throws InterruptedException {
    return exchange(HttpMethod.POST, path, headers, null, chunks, false);
  }

  @SuppressWarnings("unchecked")
  private Exchange exchange(
      final HttpMethod method,
      final String path,
      final Map<String, String> headers,
      final String preReadBody,
      final List<String> chunks,
      final boolean failMidBody)
      throws InterruptedException {
    final Exchange exchange = new Exchange();
    final AtomicReference<Handler<Buffer>> data = new AtomicReference<>();
    final AtomicReference<Handler<Void>> end = new AtomicReference<>();
    final AtomicReference<Handler<Throwable>> failure = new AtomicReference<>();
    final HttpServerResponse response =
        (HttpServerResponse)
            Proxy.newProxyInstance(
                HttpServerResponse.class.getClassLoader(),
                new Class<?>[] {HttpServerResponse.class},
                (self, invoked, args) ->
                    switch (invoked.getName()) {
                      case "setStatusCode" -> {
                        exchange.status.set((Integer) args[0]);
                        yield self;
                      }
                      case "putHeader" -> self;
                      case "end" -> {
                        exchange.json.set((String) args[0]);
                        exchange.answered.countDown();
                        yield Future.succeededFuture();
                      }
                      default -> throw new UnsupportedOperationException(invoked.getName());
                    });
    final HttpConnection connection =
        (HttpConnection)
            Proxy.newProxyInstance(
                HttpConnection.class.getClassLoader(),
                new Class<?>[] {HttpConnection.class},
                (self, invoked, args) -> {
                  if (!invoked.getName().equals("close") || args != null) {
                    throw new UnsupportedOperationException(invoked.getName());
                  }
                  exchange.closed.set(true);
                  exchange.settled.countDown();
                  // Closing the connection fails a body still being read.
                  final Handler<Throwable> reading = failure.get();
                  if (reading != null) {
                    reading.handle(new IllegalStateException("connection closed"));
                  }
                  return Future.succeededFuture();
                });
    final HttpServerRequest request =
        (HttpServerRequest)
            Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[] {HttpServerRequest.class},
                (self, invoked, args) ->
                    switch (invoked.getName()) {
                      case "method" -> method;
                      case "getHeader" -> headers.get(args[0].toString().toLowerCase(Locale.ROOT));
                      case "isEnded" -> preReadBody != null || chunks.isEmpty();
                      case "pause" -> self;
                      case "connection" -> connection;
                      case "handler" -> {
                        exchange.bodyRead.set(args[0] != null);
                        data.set((Handler<Buffer>) args[0]);
                        yield self;
                      }
                      case "endHandler" -> {
                        end.set((Handler<Void>) args[0]);
                        yield self;
                      }
                      case "exceptionHandler" -> {
                        failure.set((Handler<Throwable>) args[0]);
                        yield self;
                      }
                      case "resume" -> {
                        final Handler<Buffer> reader = data.get();
                        if (reader == null) {
                          exchange.discarded.set(true);
                          exchange.settled.countDown();
                        } else {
                          for (final String chunk : chunks) {
                            reader.handle(Buffer.buffer(chunk));
                          }
                          if (failMidBody) {
                            failure.get().handle(new IllegalStateException("connection reset"));
                          } else {
                            end.get().handle(null);
                          }
                        }
                        yield self;
                      }
                      case "body" -> {
                        exchange.bodyRead.set(true);
                        yield Future.succeededFuture(Buffer.buffer(String.join("", chunks)));
                      }
                      default -> throw new UnsupportedOperationException(invoked.getName());
                    });
    final RequestBody body =
        (RequestBody)
            Proxy.newProxyInstance(
                RequestBody.class.getClassLoader(),
                new Class<?>[] {RequestBody.class},
                (self, invoked, args) ->
                    switch (invoked.getName()) {
                      case "buffer" -> preReadBody == null ? null : Buffer.buffer(preReadBody);
                      case "asString" -> preReadBody;
                      default -> throw new UnsupportedOperationException(invoked.getName());
                    });
    final RoutingContext context =
        (RoutingContext)
            Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (self, invoked, args) ->
                    switch (invoked.getName()) {
                      case "normalizedPath" -> path;
                      case "request" -> request;
                      case "response" -> response;
                      // Without a body handler there is no request body object at all.
                      case "body" -> chunks.isEmpty() ? body : null;
                      case "user" -> user.get();
                      case "get" ->
                          QuarkusHttpUser.DEFERRED_IDENTITY_KEY.equals(args[0])
                              ? deferredIdentity.get()
                              : null;
                      case "vertx" -> vertx;
                      default -> throw new UnsupportedOperationException(invoked.getName());
                    });
    handler.handle(context);
    assertThat(exchange.answered.await(30, TimeUnit.SECONDS)).as("answered").isTrue();
    return exchange;
  }

  @Test
  @DisplayName("activates, lists and stops a plan, with the id URL-decoded")
  void operations() throws Exception {
    final Exchange activated = call(HttpMethod.POST, "/q/chaos", PLAN);
    assertThat(activated.status()).isEqualTo(200);
    assertThat(activated.body()).contains("\"handleId\":\"plan:qk-mgmt\"");
    assertThat(call(HttpMethod.GET, "/q/chaos", null).body()).contains("qk-mgmt-delay");
    assertThat(call(HttpMethod.POST, "/q/chaos", PLAN.replace("qk-mgmt\"}", "x\"}")).status())
        .isEqualTo(409);
    assertThat(call(HttpMethod.DELETE, "/q/chaos/plan%3Aqk-mgmt", null).status()).isEqualTo(200);
    assertThat(call(HttpMethod.DELETE, "/q/chaos/qk-mgmt", null).status()).isEqualTo(404);
  }

  @Test
  @DisplayName("refuses a request without an authenticated user with 401, and activates nothing")
  void anonymousRequestRefused() throws Exception {
    user.set(null);

    final Exchange refused = call(HttpMethod.POST, "/q/chaos", PLAN);

    assertThat(refused.status()).isEqualTo(401);
    assertThat(refused.body()).contains("authentication required");
    assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
  }

  @Test
  @DisplayName("refuses the anonymous identity proactive authentication sets with 401")
  void anonymousIdentityRefused() throws Exception {
    user.set(new QuarkusHttpUser(identity(true)));

    final Exchange refused = call(HttpMethod.POST, "/q/chaos", PLAN);

    assertThat(refused.status()).isEqualTo(401);
    assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
  }

  @Test
  @DisplayName("serves an identity lazy authentication defers until the route asks for it")
  void deferredIdentityServed() throws Exception {
    user.set(null);
    deferredIdentity.set(Uni.createFrom().item(identity(false)));

    assertThat(call(HttpMethod.POST, "/q/chaos", PLAN).status()).isEqualTo(200);
  }

  @Test
  @DisplayName("refuses a user that carries no Quarkus security identity with 401")
  void foreignUserRefused() throws Exception {
    user.set(io.vertx.ext.auth.User.fromName("operator"));

    assertThat(call(HttpMethod.POST, "/q/chaos", PLAN).status()).isEqualTo(401);
  }

  @Test
  @DisplayName("serves an anonymous request only when anonymous access is allowed explicitly")
  void anonymousRequestWhenAllowed() throws Exception {
    user.set(null);
    allowAnonymous.set(true);

    assertThat(call(HttpMethod.POST, "/q/chaos", PLAN).status()).isEqualTo(200);
  }

  @Test
  @DisplayName("reads no body before the switch, the path and the caller admit the request")
  void noBodyReadBeforeAdmission() throws Exception {
    enabled.set(false);
    final Exchange disabled = stream("/q/chaos", json(), List.of(PLAN));
    enabled.set(true);
    final Exchange outside = stream("/q/chaosx", json(), List.of(PLAN));
    user.set(null);
    final Exchange anonymous = stream("/q/chaos", json(), List.of(PLAN));

    assertThat(disabled.status()).isEqualTo(404);
    assertThat(outside.status()).isEqualTo(404);
    assertThat(anonymous.status()).isEqualTo(401);
    assertThat(List.of(disabled, outside, anonymous))
        .allSatisfy(refused -> assertThat(refused.bodyRead).isFalse());
  }

  @Test
  @DisplayName("refuses a declared body above the plan size cap with 413 and closes the connection")
  void oversizedContentLengthRefused() throws Exception {
    final Exchange refused =
        stream(
            "/q/chaos",
            Map.of("content-type", "application/json", "content-length", "2097152"),
            List.of(PLAN));

    assertThat(refused.status()).isEqualTo(413);
    assertThat(refused.bodyRead).isFalse();
    assertThat(refused.settle().closed).isTrue();
  }

  @Test
  @DisplayName("refuses a body without a declared length once it grows above the cap, with 413")
  void oversizedStreamRefused() throws Exception {
    final String half = " ".repeat(ChaosPlanMapper.MAX_JSON_LENGTH / 2 + 1);

    final Exchange refused = stream("/q/chaos", json(), List.of(half, half, half));

    assertThat(refused.status()).isEqualTo(413);
    assertThat(refused.settle().closed).isTrue();
  }

  @Test
  @DisplayName("refuses a POST that is not application/json with 415")
  void wrongMediaTypeRefused() throws Exception {
    final Exchange refused =
        stream("/q/chaos", Map.of("content-type", "text/plain"), List.of(PLAN));

    assertThat(refused.status()).isEqualTo(415);
    assertThat(refused.bodyRead).isFalse();
    assertThat(stream("/q/chaos", Map.of(), List.of(PLAN)).status()).isEqualTo(415);
    assertThat(
            stream(
                    "/q/chaos",
                    Map.of(
                        "content-type",
                        "Application/JSON; charset=utf-8",
                        "content-length",
                        String.valueOf(PLAN.length())),
                    List.of(PLAN))
                .status())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("refuses a negative or unparsable Content-Length with 400")
  void invalidContentLengthRefused() throws Exception {
    for (final String length : List.of("-1", "many")) {
      assertThat(
              stream(
                      "/q/chaos",
                      Map.of("content-type", "application/json", "content-length", length),
                      List.of(PLAN))
                  .status())
          .as(length)
          .isEqualTo(400);
    }
  }

  @Test
  @DisplayName("discards the unread body of a POST it refuses early, keeping the connection usable")
  void earlyRefusalDiscardsBody() throws Exception {
    final Exchange wrongType =
        stream("/q/chaos", Map.of("content-type", "text/plain"), List.of(PLAN));
    user.set(null);
    final Exchange anonymous = stream("/q/chaos", json(), List.of(PLAN));

    assertThat(wrongType.settle().discarded).isTrue();
    assertThat(anonymous.settle().discarded).isTrue();
  }

  @Test
  @DisplayName("answers 404 while disabled or outside its root, 503 without the agent")
  void neverLeavesARequestUnanswered() throws Exception {
    enabled.set(false);
    assertThat(call(HttpMethod.GET, "/q/chaos", null).status()).isEqualTo(404);
    enabled.set(true);
    assertThat(call(HttpMethod.DELETE, "/q/chaosx", null).status()).isEqualTo(404);
    final ChaosManagementHandler withoutAgent =
        new ChaosManagementHandler("/q/chaos", () -> true, () -> true, java.util.Optional::empty);
    final AtomicInteger status = new AtomicInteger();
    final HttpServerRequest get =
        (HttpServerRequest)
            Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[] {HttpServerRequest.class},
                (self, method, args) -> method.getName().equals("method") ? HttpMethod.GET : null);
    final RoutingContext context =
        (RoutingContext)
            Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "normalizedPath" -> "/q/chaos";
                      case "request" -> get;
                      case "response" ->
                          Proxy.newProxyInstance(
                              HttpServerResponse.class.getClassLoader(),
                              new Class<?>[] {HttpServerResponse.class},
                              (r, m, a) -> {
                                if (m.getName().equals("setStatusCode")) {
                                  status.set((Integer) a[0]);
                                }
                                return m.getName().equals("end") ? Future.succeededFuture() : r;
                              });
                      default -> null;
                    });
    withoutAgent.serve(context, null);
    assertThat(status.get()).isEqualTo(503);
    withoutAgent.close();
  }

  @Test
  @DisplayName("answers 500 when the control plane fails unexpectedly")
  void unexpectedFailureIs500() throws Exception {
    final ChaosManagementHandler failing =
        new ChaosManagementHandler(
            "/q/chaos",
            () -> true,
            () -> true,
            () -> {
              throw new UnsupportedOperationException("broken");
            });
    final HttpServerRequest get =
        (HttpServerRequest)
            Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[] {HttpServerRequest.class},
                (self, method, args) -> method.getName().equals("method") ? HttpMethod.GET : null);
    final AtomicInteger status = new AtomicInteger();
    final RoutingContext context =
        (RoutingContext)
            Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "normalizedPath" -> "/q/chaos";
                      case "request" -> get;
                      case "response" ->
                          Proxy.newProxyInstance(
                              HttpServerResponse.class.getClassLoader(),
                              new Class<?>[] {HttpServerResponse.class},
                              (r, m, a) -> {
                                if (m.getName().equals("setStatusCode")) {
                                  status.set((Integer) a[0]);
                                }
                                return m.getName().equals("end") ? Future.succeededFuture() : r;
                              });
                      default -> null;
                    });

    failing.serve(context, null);

    assertThat(status.get()).isEqualTo(500);
    failing.close();
  }

  @Test
  @DisplayName("the recorder closes the handler, and so its plans, on shutdown")
  void recorderRegistersShutdown() {
    final java.util.List<Runnable> tasks = new java.util.ArrayList<>();
    final io.quarkus.runtime.ShutdownContext shutdown =
        new io.quarkus.runtime.ShutdownContext() {
          @Override
          public void addShutdownTask(final Runnable task) {
            tasks.add(task);
          }

          @Override
          public void addLastShutdownTask(final Runnable task) {
            tasks.add(task);
          }
        };
    assertThat(new ChaosManagementRecorder().handler("/q/chaos", shutdown))
        .isInstanceOf(ChaosManagementHandler.class);
    assertThat(tasks).hasSize(1);
    tasks.get(0).run();
  }

  @Test
  @DisplayName("the recorder's handler is off unless macstab.chaos.enabled is configured")
  void recorderHandlerDisabledByDefault() {
    final io.quarkus.runtime.ShutdownContext shutdown =
        new io.quarkus.runtime.ShutdownContext() {
          @Override
          public void addShutdownTask(final Runnable task) {}

          @Override
          public void addLastShutdownTask(final Runnable task) {}
        };
    final ChaosManagementHandler recorded =
        (ChaosManagementHandler) new ChaosManagementRecorder().handler("/q/chaos", shutdown);
    final AtomicInteger status = new AtomicInteger();
    final AtomicReference<String> sent = new AtomicReference<>();
    final RoutingContext context =
        (RoutingContext)
            Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "normalizedPath" -> "/q/chaos";
                      case "response" ->
                          Proxy.newProxyInstance(
                              HttpServerResponse.class.getClassLoader(),
                              new Class<?>[] {HttpServerResponse.class},
                              (r, m, a) -> {
                                if (m.getName().equals("setStatusCode")) {
                                  status.set((Integer) a[0]);
                                }
                                if (m.getName().equals("end")) {
                                  sent.set((String) a[0]);
                                  return Future.succeededFuture();
                                }
                                return r;
                              });
                      default -> null;
                    });

    recorded.serve(context, null);

    assertThat(status.get()).isEqualTo(404);
    assertThat(sent.get()).contains("chaos management is disabled");
    recorded.close();
  }

  @Test
  @DisplayName("the recorder's handler refuses anonymous requests unless allow-anonymous is set")
  void recorderHandlerRefusesAnonymousByDefault() {
    final io.quarkus.runtime.ShutdownContext shutdown =
        new io.quarkus.runtime.ShutdownContext() {
          @Override
          public void addShutdownTask(final Runnable task) {}

          @Override
          public void addLastShutdownTask(final Runnable task) {}
        };
    final AtomicInteger status = new AtomicInteger();
    final RoutingContext anonymous =
        (RoutingContext)
            Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "normalizedPath" -> "/q/chaos";
                      case "response" ->
                          Proxy.newProxyInstance(
                              HttpServerResponse.class.getClassLoader(),
                              new Class<?>[] {HttpServerResponse.class},
                              (r, m, a) -> {
                                if (m.getName().equals("setStatusCode")) {
                                  status.set((Integer) a[0]);
                                }
                                return m.getName().equals("end") ? Future.succeededFuture() : r;
                              });
                      default -> null;
                    });
    final String previous = System.getProperty(ChaosManagementRecorder.ENABLED_PROPERTY);
    System.setProperty(ChaosManagementRecorder.ENABLED_PROPERTY, "true");
    try {
      final ChaosManagementHandler recorded =
          (ChaosManagementHandler) new ChaosManagementRecorder().handler("/q/chaos", shutdown);

      recorded.serve(anonymous, null);

      assertThat(status.get()).isEqualTo(401);
      recorded.close();
    } finally {
      if (previous == null) {
        System.clearProperty(ChaosManagementRecorder.ENABLED_PROPERTY);
      } else {
        System.setProperty(ChaosManagementRecorder.ENABLED_PROPERTY, previous);
      }
    }
  }

  @Test
  @DisplayName(
      "a body handler without a buffer, and a body the client stops sending, reach the route as"
          + " none")
  void unreadableBodyIsNoBody() throws Exception {
    // A body handler ran and kept no buffer: the request has ended, there is nothing to read.
    final Exchange ended = exchange(HttpMethod.POST, "/q/chaos", json(), null, List.of(), false);
    final Exchange reset =
        exchange(HttpMethod.POST, "/q/chaos", json(), null, List.of("{\"meta"), true);

    assertThat(ended.status()).as("a POST without a plan is invalid").isEqualTo(400);
    assertThat(reset.status()).as("a POST without a plan is invalid").isEqualTo(400);
  }

  @Test
  @DisplayName("reads an unread body asynchronously and works off the event loop; failures are 500")
  void asynchronousBodyAndFailure() throws Exception {
    final int split = PLAN.length() / 2;
    assertThat(
            stream("/q/chaos", json(), List.of(PLAN.substring(0, split), PLAN.substring(split)))
                .status())
        .isEqualTo(200);

    final AtomicInteger failedStatus = new AtomicInteger();
    final RoutingContext broken =
        (RoutingContext)
            Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "normalizedPath" -> throw new IllegalArgumentException("bad path");
                      case "response" ->
                          Proxy.newProxyInstance(
                              HttpServerResponse.class.getClassLoader(),
                              new Class<?>[] {HttpServerResponse.class},
                              (r, m, a) -> {
                                if (m.getName().equals("setStatusCode")) {
                                  failedStatus.set((Integer) a[0]);
                                }
                                return m.getName().equals("end") ? Future.succeededFuture() : r;
                              });
                      default -> null;
                    });
    handler.serve(broken, null);
    assertThat(failedStatus.get()).isEqualTo(500);
  }
}

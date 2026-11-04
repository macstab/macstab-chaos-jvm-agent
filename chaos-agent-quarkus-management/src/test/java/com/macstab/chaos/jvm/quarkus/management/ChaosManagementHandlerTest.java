package com.macstab.chaos.jvm.quarkus.management;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Proxy;
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
  private final ChaosManagementHandler handler =
      new ChaosManagementHandler("/q/chaos", enabled::get, () -> runtime);

  @AfterEach
  void tearDown() {
    handler.close();
    runtime.close();
  }

  private record Answer(int status, String body) {}

  @SuppressWarnings("unchecked")
  private static <T> T proxy(
      final Class<T> type, final java.util.function.Function<String, Object> answers) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (self, method, args) -> {
              final Object answer = answers.apply(method.getName());
              if (answer == SELF) {
                return self;
              }
              if (answer == null && !method.getName().equals("body")) {
                throw new UnsupportedOperationException(method.getName());
              }
              return answer;
            });
  }

  private static final Object SELF = new Object();

  private Answer call(final HttpMethod method, final String path, final String preReadBody) {
    final AtomicInteger status = new AtomicInteger();
    final AtomicReference<String> sent = new AtomicReference<>();
    final HttpServerResponse response =
        proxy(
            HttpServerResponse.class,
            name ->
                switch (name) {
                  case "setStatusCode", "putHeader" -> SELF;
                  case "end" -> Future.succeededFuture();
                  default -> null;
                });
    final HttpServerResponse recording =
        (HttpServerResponse)
            Proxy.newProxyInstance(
                HttpServerResponse.class.getClassLoader(),
                new Class<?>[] {HttpServerResponse.class},
                (self, invoked, args) -> {
                  if (invoked.getName().equals("setStatusCode")) {
                    status.set((Integer) args[0]);
                  }
                  if (invoked.getName().equals("end")) {
                    sent.set((String) args[0]);
                  }
                  final Object delegated = invoked.invoke(response, args);
                  return delegated == response ? self : delegated;
                });
    final HttpServerRequest request =
        proxy(HttpServerRequest.class, name -> name.equals("method") ? method : null);
    final RequestBody body =
        proxy(
            RequestBody.class,
            name ->
                switch (name) {
                  case "buffer" -> Buffer.buffer(preReadBody == null ? "" : preReadBody);
                  case "asString" -> preReadBody == null ? "" : preReadBody;
                  default -> null;
                });
    final RoutingContext context =
        proxy(
            RoutingContext.class,
            name ->
                switch (name) {
                  case "normalizedPath" -> path;
                  case "request" -> request;
                  case "response" -> recording;
                  case "body" -> body;
                  default -> null;
                });
    handler.handle(context);
    return new Answer(status.get(), sent.get());
  }

  @Test
  @DisplayName("activates, lists and stops a plan, with the id URL-decoded")
  void operations() {
    final Answer activated = call(HttpMethod.POST, "/q/chaos", PLAN);
    assertThat(activated.status()).isEqualTo(200);
    assertThat(activated.body()).contains("\"handleId\":\"plan:qk-mgmt\"");
    assertThat(call(HttpMethod.GET, "/q/chaos", null).body()).contains("qk-mgmt-delay");
    assertThat(call(HttpMethod.POST, "/q/chaos", PLAN.replace("qk-mgmt\"}", "x\"}")).status())
        .isEqualTo(409);
    assertThat(call(HttpMethod.DELETE, "/q/chaos/plan%3Aqk-mgmt", null).status()).isEqualTo(200);
    assertThat(call(HttpMethod.DELETE, "/q/chaos/qk-mgmt", null).status()).isEqualTo(404);
  }

  @Test
  @DisplayName("answers 404 while disabled or outside its root, 503 without the agent")
  void neverLeavesARequestUnanswered() {
    enabled.set(false);
    assertThat(call(HttpMethod.GET, "/q/chaos", null).status()).isEqualTo(404);
    enabled.set(true);
    assertThat(call(HttpMethod.DELETE, "/q/chaosx", null).status()).isEqualTo(404);
    final ChaosManagementHandler withoutAgent =
        new ChaosManagementHandler(
            "/q/chaos",
            () -> true,
            () -> {
              throw new IllegalStateException("not installed");
            });
    final AtomicInteger status = new AtomicInteger();
    final RoutingContext context =
        (RoutingContext)
            Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "normalizedPath" -> "/q/chaos";
                      case "request" ->
                          proxy(
                              HttpServerRequest.class,
                              n -> n.equals("method") ? HttpMethod.GET : null);
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
  @DisplayName("reads an unread body asynchronously and works off the event loop; failures are 500")
  void asynchronousBodyAndFailure() throws Exception {
    final io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
    try {
      final AtomicInteger status = new AtomicInteger();
      final java.util.concurrent.CountDownLatch answered =
          new java.util.concurrent.CountDownLatch(1);
      final HttpServerResponse response =
          (HttpServerResponse)
              Proxy.newProxyInstance(
                  HttpServerResponse.class.getClassLoader(),
                  new Class<?>[] {HttpServerResponse.class},
                  (self, method, args) -> {
                    if (method.getName().equals("setStatusCode")) {
                      status.set((Integer) args[0]);
                    }
                    if (method.getName().equals("end")) {
                      answered.countDown();
                      return Future.succeededFuture();
                    }
                    return self;
                  });
      final HttpServerRequest request =
          (HttpServerRequest)
              Proxy.newProxyInstance(
                  HttpServerRequest.class.getClassLoader(),
                  new Class<?>[] {HttpServerRequest.class},
                  (self, method, args) ->
                      switch (method.getName()) {
                        case "method" -> HttpMethod.POST;
                        case "body" -> Future.succeededFuture(Buffer.buffer(PLAN));
                        default -> throw new UnsupportedOperationException(method.getName());
                      });
      final RoutingContext context =
          (RoutingContext)
              Proxy.newProxyInstance(
                  RoutingContext.class.getClassLoader(),
                  new Class<?>[] {RoutingContext.class},
                  (self, method, args) ->
                      switch (method.getName()) {
                        case "normalizedPath" -> "/q/chaos";
                        case "request" -> request;
                        case "response" -> response;
                        case "vertx" -> vertx;
                        case "body" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                      });
      handler.handle(context);
      assertThat(answered.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(status.get()).isEqualTo(200);
    } finally {
      vertx
          .close()
          .toCompletionStage()
          .toCompletableFuture()
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

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

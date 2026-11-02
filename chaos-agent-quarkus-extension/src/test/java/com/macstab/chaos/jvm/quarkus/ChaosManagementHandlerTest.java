package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  private record Answer(int status, String body) {}

  private static Answer call(
      final ChaosManagementHandler handler,
      final HttpMethod method,
      final String path,
      final String body) {
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> sent = new AtomicReference<>();
    final HttpServerResponse response =
        (HttpServerResponse)
            Proxy.newProxyInstance(
                HttpServerResponse.class.getClassLoader(),
                new Class<?>[] {HttpServerResponse.class},
                (proxy, invoked, args) ->
                    switch (invoked.getName()) {
                      case "setStatusCode" -> {
                        status.set((Integer) args[0]);
                        yield proxy;
                      }
                      case "putHeader" -> proxy;
                      case "end" -> {
                        sent.set((String) args[0]);
                        yield Future.succeededFuture();
                      }
                      default -> throw new UnsupportedOperationException(invoked.getName());
                    });
    final HttpServerRequest request =
        (HttpServerRequest)
            Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[] {HttpServerRequest.class},
                (proxy, invoked, args) ->
                    switch (invoked.getName()) {
                      case "method" -> method;
                      case "body" ->
                          Future.succeededFuture(body == null ? null : Buffer.buffer(body));
                      default -> throw new UnsupportedOperationException(invoked.getName());
                    });
    final RoutingContext context =
        (RoutingContext)
            Proxy.newProxyInstance(
                RoutingContext.class.getClassLoader(),
                new Class<?>[] {RoutingContext.class},
                (proxy, invoked, args) ->
                    switch (invoked.getName()) {
                      case "normalizedPath" -> path;
                      case "request" -> request;
                      case "response" -> response;
                      default -> throw new UnsupportedOperationException(invoked.getName());
                    });
    handler.handle(context);
    return new Answer(status.get(), sent.get());
  }

  @Test
  @DisplayName("activates, lists and stops a plan through the route")
  void route() {
    ChaosPlatform.installLocally();
    final ChaosManagementHandler handler = new ChaosManagementHandler("/q/chaos");

    final Answer activated = call(handler, HttpMethod.POST, "/q/chaos", PLAN);
    assertThat(activated.status()).isEqualTo(200);
    assertThat(activated.body()).contains("\"handleId\":\"plan:qk-mgmt\"");
    assertThat(call(handler, HttpMethod.GET, "/q/chaos", null).body()).contains("qk-mgmt-delay");
    assertThat(call(handler, HttpMethod.DELETE, "/q/chaos/qk-mgmt", null).body())
        .contains("\"status\":\"stopped\"");
    assertThat(call(handler, HttpMethod.DELETE, "/q/chaos/qk-mgmt", null).status()).isEqualTo(404);
    assertThat(call(handler, HttpMethod.PUT, "/q/chaos", null).status()).isEqualTo(405);
  }
}

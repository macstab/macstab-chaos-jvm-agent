package com.macstab.chaos.jvm.bootstrap;

import java.net.URI;
import java.time.Duration;

/**
 * Probe main for HTTP client libraries (OkHttp, Apache HttpClient 5, Reactor Netty). Kept apart
 * from {@link DefectProbeMain} because the JVM verifier loads library types referenced by these
 * methods when the class is linked: this class only runs in child JVMs whose classpath carries the
 * libraries ({@code chaos.test.httpClasspath}).
 */
public final class HttpLibraryProbeMain {

  private HttpLibraryProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "okhttp-execute" -> DefectProbeMain.withHttpServer(url -> okHttpExecute(url, null));
      case "okhttp-execute-reflection-reject" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::okHttpExecuteBesideReflectionReject);
      case "okhttp-client-under-reflection-reject" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::okHttpClientUnderReflectionReject);
      case "okhttp-enqueue-reject" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::okHttpEnqueueReject);
      case "okhttp-interceptor-nested-request" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::okHttpInterceptorNestedRequest);
      case "hc5-execute" -> DefectProbeMain.withHttpServer(HttpLibraryProbeMain::hc5Execute);
      case "hc5-execute-host" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::hc5ExecuteWithHost);
      case "hc5-execute-open-null-host" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::hc5ExecuteOpenNullHost);
      case "hc5-handler-nested-request" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::hc5HandlerNestedRequest);
      case "reactor-netty-get" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::reactorNettyGet);
      case "reactor-retry-reject" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::reactorRetryReject);
      case "reactor-base-url-deferred-uri" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::reactorBaseUrlDeferredUri);
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    DefectProbeMain.printCounts();
  }

  /**
   * {@link #okHttpExecute} with a REFLECTION_INVOKE reject active for the call's execution only:
   * activated once the client and the call exist (the client's TLS setup reflects, and loading
   * {@code RealCall} weaves it), it prints how often it matched as {@code reflection.matched}.
   */
  private static void okHttpExecuteBesideReflectionReject(final String url) throws Exception {
    final com.macstab.chaos.jvm.api.ChaosActivationHandle[] reflection =
        new com.macstab.chaos.jvm.api.ChaosActivationHandle[1];
    try {
      okHttpExecute(
          url,
          () ->
              reflection[0] =
                  ChaosPlatform.current()
                      .activate(
                          com.macstab.chaos.jvm.api.ChaosScenario.builder("reflection")
                              .scope(com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope.JVM)
                              .selector(
                                  com.macstab.chaos.jvm.api.ChaosSelector.jvmRuntime(
                                      java.util.Set.of(
                                          com.macstab.chaos.jvm.api.OperationType
                                              .REFLECTION_INVOKE)))
                              .effect(com.macstab.chaos.jvm.api.ChaosEffect.reject("reflection"))
                              .activationPolicy(com.macstab.chaos.jvm.api.ActivationPolicy.always())
                              .build()));
    } finally {
      if (reflection[0] != null) {
        System.out.println("reflection.matched=" + reflection[0].matchedCount());
        reflection[0].close();
      }
    }
  }

  /**
   * {@link #okHttpExecute} with a REFLECTION_INVOKE reject active from the start: the client's TLS
   * set-up (the JDK's own reflection) and the weaving of {@code RealCall} when it first loads (the
   * agent's own work) are not targets, so the call is rejected by the plan's OkHttp scenario.
   */
  private static void okHttpClientUnderReflectionReject(final String url) throws Exception {
    try (com.macstab.chaos.jvm.api.ChaosActivationHandle reflection =
        ChaosPlatform.current()
            .activate(
                com.macstab.chaos.jvm.api.ChaosScenario.builder("reflection")
                    .scope(com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope.JVM)
                    .selector(
                        com.macstab.chaos.jvm.api.ChaosSelector.jvmRuntime(
                            java.util.Set.of(
                                com.macstab.chaos.jvm.api.OperationType.REFLECTION_INVOKE)))
                    .effect(com.macstab.chaos.jvm.api.ChaosEffect.reject("reflection"))
                    .activationPolicy(com.macstab.chaos.jvm.api.ActivationPolicy.always())
                    .build())) {
      try {
        okHttpExecute(url, null);
      } catch (final RuntimeException | Error failure) {
        System.out.println("failure=" + failure);
      }
      System.out.println(
          "hookFailures="
              + ChaosPlatform.current()
                  .diagnostics()
                  .snapshot()
                  .runtimeDetails()
                  .get("hookFailures"));
    }
  }

  private static void okHttpExecute(final String url, final Runnable beforeCall) throws Exception {
    final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    final okhttp3.OkHttpClient client = recordingClient(events);
    final okhttp3.Call call = client.newCall(new okhttp3.Request.Builder().url(url).build());
    if (beforeCall != null) {
      beforeCall.run();
    }
    try (okhttp3.Response response = call.execute()) {
      System.out.println("status=" + response.code());
    } catch (final java.io.IOException rejected) {
      System.out.println("rejected=" + rejected.getClass().getName());
    } finally {
      System.out.println("events=" + String.join(",", events));
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
  }

  /** A client whose {@code EventListener} records the call's start and how it ended. */
  private static okhttp3.OkHttpClient recordingClient(final java.util.List<String> events) {
    return new okhttp3.OkHttpClient.Builder()
        .eventListener(
            new okhttp3.EventListener() {
              @Override
              public void callStart(final okhttp3.Call call) {
                events.add("callStart");
              }

              @Override
              public void callEnd(final okhttp3.Call call) {
                events.add("callEnd");
              }

              @Override
              public void callFailed(final okhttp3.Call call, final java.io.IOException failure) {
                events.add("callFailed");
              }
            })
        .build();
  }

  /** An enqueued call: whether {@code enqueue} threw, and what its callback received where. */
  private static void okHttpEnqueueReject(final String url) throws Exception {
    final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    final okhttp3.OkHttpClient client = recordingClient(events);
    final java.util.concurrent.CompletableFuture<String> outcome =
        new java.util.concurrent.CompletableFuture<>();
    try {
      client
          .newCall(new okhttp3.Request.Builder().url(url).build())
          .enqueue(
              new okhttp3.Callback() {
                @Override
                public void onFailure(final okhttp3.Call call, final java.io.IOException failure) {
                  System.out.println("callback.type=" + failure.getClass().getName());
                  System.out.println("callback.message=" + failure.getMessage());
                  System.out.println("callback.thread=" + Thread.currentThread().getName());
                  outcome.complete("failure");
                }

                @Override
                public void onResponse(final okhttp3.Call call, final okhttp3.Response response) {
                  response.close();
                  outcome.complete("response " + response.code());
                }
              });
      System.out.println("callback=" + outcome.get(10, java.util.concurrent.TimeUnit.SECONDS));
      System.out.println("events=" + String.join(",", events));
    } catch (final Exception failure) {
      System.out.println("call.type=" + failure.getClass().getName());
    } finally {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
  }

  /** An application interceptor that sends a request of its own before the call proceeds. */
  private static void okHttpInterceptorNestedRequest(final String url) throws Exception {
    final okhttp3.OkHttpClient nestedClient = new okhttp3.OkHttpClient();
    final okhttp3.OkHttpClient client =
        new okhttp3.OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  try (okhttp3.Response nested =
                      nestedClient
                          .newCall(new okhttp3.Request.Builder().url(url).build())
                          .execute()) {
                    System.out.println("nested.status=" + nested.code());
                  }
                  return chain.proceed(chain.request());
                })
            .build();
    try (okhttp3.Response response =
        client.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
      System.out.println("status=" + response.code());
    } finally {
      for (final okhttp3.OkHttpClient each : java.util.List.of(client, nestedClient)) {
        each.dispatcher().executorService().shutdown();
        each.connectionPool().evictAll();
      }
    }
  }

  @SuppressWarnings("deprecation")
  private static void hc5Execute(final String url) throws Exception {
    try (org.apache.hc.client5.http.impl.classic.CloseableHttpClient client =
            org.apache.hc.client5.http.impl.classic.HttpClients.createDefault();
        org.apache.hc.client5.http.impl.classic.CloseableHttpResponse response =
            client.execute(new org.apache.hc.client5.http.classic.methods.HttpGet(url))) {
      System.out.println("status=" + response.getCode());
    }
  }

  @SuppressWarnings("deprecation")
  private static void hc5ExecuteWithHost(final String url) throws Exception {
    final URI uri = URI.create(url);
    try (org.apache.hc.client5.http.impl.classic.CloseableHttpClient client =
            org.apache.hc.client5.http.impl.classic.HttpClients.createDefault();
        org.apache.hc.core5.http.ClassicHttpResponse response =
            client.execute(
                new org.apache.hc.core5.http.HttpHost(
                    uri.getScheme(), uri.getHost(), uri.getPort()),
                new org.apache.hc.client5.http.classic.methods.HttpGet(uri.getPath()))) {
      System.out.println("status=" + response.getCode());
    }
  }

  /**
   * {@code executeOpen(null, request, null)}: what Spring's HttpComponents request factory calls.
   */
  private static void hc5ExecuteOpenNullHost(final String url) throws Exception {
    try (org.apache.hc.client5.http.impl.classic.CloseableHttpClient client =
            org.apache.hc.client5.http.impl.classic.HttpClients.createDefault();
        org.apache.hc.core5.http.ClassicHttpResponse response =
            client.executeOpen(
                null, new org.apache.hc.client5.http.classic.methods.HttpGet(url), null)) {
      System.out.println("status=" + response.getCode());
    }
  }

  /** A response handler that sends a second request: two calls. */
  private static void hc5HandlerNestedRequest(final String url) throws Exception {
    try (org.apache.hc.client5.http.impl.classic.CloseableHttpClient client =
        org.apache.hc.client5.http.impl.classic.HttpClients.createDefault()) {
      final int nestedStatus =
          client.execute(
              new org.apache.hc.client5.http.classic.methods.HttpGet(url),
              outer -> {
                org.apache.hc.core5.http.io.entity.EntityUtils.consume(outer.getEntity());
                return client.execute(
                    new org.apache.hc.client5.http.classic.methods.HttpGet(url),
                    inner -> {
                      org.apache.hc.core5.http.io.entity.EntityUtils.consume(inner.getEntity());
                      return inner.getCode();
                    });
              });
      System.out.println("status=" + nestedStatus);
    }
  }

  /** A request retried twice: each subscription is a request, and how the last one failed. */
  private static void reactorRetryReject(final String url) {
    try {
      final String body =
          reactor.netty.http.client.HttpClient.create()
              .get()
              .uri(url)
              .responseContent()
              .aggregate()
              .asString()
              .retry(2)
              .block(Duration.ofSeconds(10L));
      System.out.println("body=" + body);
    } catch (final RuntimeException failure) {
      final Throwable cause = reactor.core.Exceptions.unwrap(failure);
      System.out.println("error.type=" + cause.getClass().getName());
      System.out.println("error.message=" + cause.getMessage());
    }
  }

  /** A {@code baseUrl} and a path given as a {@code Mono}: known only when subscribed. */
  private static void reactorBaseUrlDeferredUri(final String url) {
    final URI uri = URI.create(url);
    final String body =
        reactor.netty.http.client.HttpClient.create()
            .baseUrl(uri.getScheme() + "://" + uri.getAuthority())
            .get()
            .uri(reactor.core.publisher.Mono.just(uri.getPath()))
            .responseContent()
            .aggregate()
            .asString()
            .block(Duration.ofSeconds(10L));
    System.out.println("body=" + body);
  }

  private static void reactorNettyGet(final String url) {
    final String body =
        reactor.netty.http.client.HttpClient.create()
            .get()
            .uri(url)
            .responseContent()
            .aggregate()
            .asString()
            .block(Duration.ofSeconds(10L));
    System.out.println("body=" + body);
  }
}

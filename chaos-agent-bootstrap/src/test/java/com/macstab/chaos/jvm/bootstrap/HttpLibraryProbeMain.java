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
      case "okhttp-execute" -> DefectProbeMain.withHttpServer(HttpLibraryProbeMain::okHttpExecute);
      case "hc5-execute" -> DefectProbeMain.withHttpServer(HttpLibraryProbeMain::hc5Execute);
      case "hc5-execute-host" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::hc5ExecuteWithHost);
      case "reactor-netty-get" ->
          DefectProbeMain.withHttpServer(HttpLibraryProbeMain::reactorNettyGet);
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    DefectProbeMain.printCounts();
  }

  private static void okHttpExecute(final String url) throws Exception {
    final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
    try (okhttp3.Response response =
        client.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
      System.out.println("status=" + response.code());
    } catch (final java.io.IOException rejected) {
      System.out.println("rejected=" + rejected.getClass().getName());
    } finally {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
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

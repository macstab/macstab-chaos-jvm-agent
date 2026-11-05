package com.macstab.chaos.jvm.bootstrap;

import java.net.URI;

/**
 * Probe main for Apache HttpClient 4. Apart from {@link HttpLibraryProbeMain} because linking a
 * class loads the library types it references: this one runs only in child JVMs whose classpath
 * carries HttpClient 4 ({@code chaos.test.coverageClasspath}).
 */
public final class Hc4ProbeMain {

  private Hc4ProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "hc4-execute" -> DefectProbeMain.withHttpServer(Hc4ProbeMain::hc4Execute);
      case "hc4-execute-host" -> DefectProbeMain.withHttpServer(Hc4ProbeMain::hc4ExecuteWithHost);
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    DefectProbeMain.printCounts();
  }

  private static void hc4Execute(final String url) throws Exception {
    try (org.apache.http.impl.client.CloseableHttpClient client =
            org.apache.http.impl.client.HttpClients.createDefault();
        org.apache.http.client.methods.CloseableHttpResponse response =
            client.execute(new org.apache.http.client.methods.HttpGet(url))) {
      System.out.println("status=" + response.getStatusLine().getStatusCode());
    }
  }

  private static void hc4ExecuteWithHost(final String url) throws Exception {
    final URI uri = URI.create(url);
    try (org.apache.http.impl.client.CloseableHttpClient client =
            org.apache.http.impl.client.HttpClients.createDefault();
        org.apache.http.client.methods.CloseableHttpResponse response =
            client.execute(
                new org.apache.http.HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()),
                new org.apache.http.client.methods.HttpGet(uri.getPath()))) {
      System.out.println("status=" + response.getStatusLine().getStatusCode());
    }
  }
}

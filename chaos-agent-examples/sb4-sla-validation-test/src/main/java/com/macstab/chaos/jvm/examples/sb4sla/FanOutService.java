package com.macstab.chaos.jvm.examples.sb4sla;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Service that issues concurrent requests to three downstream services and aggregates results. */
@Service
public class FanOutService {

  private final HttpClient httpClient;
  private final String urlA;
  private final String urlB;
  private final String urlC;

  /**
   * Creates a new FanOutService.
   *
   * @param httpClient HTTP client (uses NIO {@code SocketChannelImpl}, which the chaos agent
   *     instruments)
   * @param urlA base URL of downstream service A
   * @param urlB base URL of downstream service B
   * @param urlC base URL of downstream service C
   */
  public FanOutService(
      final HttpClient httpClient,
      @Value("${downstream.a.url}") final String urlA,
      @Value("${downstream.b.url}") final String urlB,
      @Value("${downstream.c.url}") final String urlC) {
    this.httpClient = httpClient;
    this.urlA = urlA;
    this.urlB = urlB;
    this.urlC = urlC;
  }

  /**
   * Calls all three downstream services concurrently and returns the aggregated result.
   *
   * @return the combined responses from services A, B, and C
   */
  public FanOutResult call() {
    try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final CompletableFuture<String> futureA =
          CompletableFuture.supplyAsync(() -> get(urlA), executor);
      final CompletableFuture<String> futureB =
          CompletableFuture.supplyAsync(() -> get(urlB), executor);
      final CompletableFuture<String> futureC =
          CompletableFuture.supplyAsync(() -> get(urlC), executor);

      CompletableFuture.allOf(futureA, futureB, futureC).join();

      return new FanOutResult(futureA.join(), futureB.join(), futureC.join());
    }
  }

  private String get(final String url) {
    final HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return response.body();
    } catch (final IOException e) {
      throw new IllegalStateException("HTTP GET " + url + " failed", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("HTTP GET " + url + " interrupted", e);
    }
  }
}

package com.macstab.chaos.jvm.examples.sb4sla;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/** Service that issues concurrent requests to three downstream services and aggregates results. */
@Service
public class FanOutService {

  private final RestTemplate restTemplate;
  private final String urlA;
  private final String urlB;
  private final String urlC;

  /**
   * Creates a new FanOutService.
   *
   * @param restTemplate HTTP client
   * @param urlA base URL of downstream service A
   * @param urlB base URL of downstream service B
   * @param urlC base URL of downstream service C
   */
  public FanOutService(
      final RestTemplate restTemplate,
      @Value("${downstream.a.url}") final String urlA,
      @Value("${downstream.b.url}") final String urlB,
      @Value("${downstream.c.url}") final String urlC) {
    this.restTemplate = restTemplate;
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
          CompletableFuture.supplyAsync(
              () -> restTemplate.getForObject(urlA, String.class), executor);
      final CompletableFuture<String> futureB =
          CompletableFuture.supplyAsync(
              () -> restTemplate.getForObject(urlB, String.class), executor);
      final CompletableFuture<String> futureC =
          CompletableFuture.supplyAsync(
              () -> restTemplate.getForObject(urlC, String.class), executor);

      CompletableFuture.allOf(futureA, futureB, futureC).join();

      return new FanOutResult(futureA.join(), futureB.join(), futureC.join());
    }
  }
}

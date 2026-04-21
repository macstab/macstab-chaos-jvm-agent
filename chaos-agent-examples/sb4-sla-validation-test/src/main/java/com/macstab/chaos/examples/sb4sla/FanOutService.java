package com.macstab.chaos.examples.sb4sla;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class FanOutService {

  private final RestTemplate restTemplate;
  private final String urlA;
  private final String urlB;
  private final String urlC;

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

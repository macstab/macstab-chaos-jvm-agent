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
      RestTemplate restTemplate,
      @Value("${downstream.a.url}") String urlA,
      @Value("${downstream.b.url}") String urlB,
      @Value("${downstream.c.url}") String urlC) {
    this.restTemplate = restTemplate;
    this.urlA = urlA;
    this.urlB = urlB;
    this.urlC = urlC;
  }

  public FanOutResult call() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<String> futureA =
          CompletableFuture.supplyAsync(
              () -> restTemplate.getForObject(urlA, String.class), executor);
      CompletableFuture<String> futureB =
          CompletableFuture.supplyAsync(
              () -> restTemplate.getForObject(urlB, String.class), executor);
      CompletableFuture<String> futureC =
          CompletableFuture.supplyAsync(
              () -> restTemplate.getForObject(urlC, String.class), executor);

      CompletableFuture.allOf(futureA, futureB, futureC).join();

      return new FanOutResult(futureA.join(), futureB.join(), futureC.join());
    }
  }
}

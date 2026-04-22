package com.macstab.chaos.jvm.examples.sb4pinning;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@ChaosTest(
    classes = VirtualThreadPinningApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "macstab.chaos.contention.enabled=true")
class VirtualThreadPinningIT {

  @LocalServerPort private int port;

  @Test
  void hundredConcurrentVirtualThreadRecordCallsAllSucceed() throws Exception {
    final RestClient client = RestClient.create("http://localhost:" + port);
    int concurrency = 100;
    List<Future<ResponseEntity<Void>>> futures = new ArrayList<>(concurrency);

    long wallStart = System.nanoTime();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrency; i++) {
        futures.add(
            executor.submit(
                () -> client.post().uri("/metrics?name=test").retrieve().toBodilessEntity()));
      }
    }

    long wallMillis = (System.nanoTime() - wallStart) / 1_000_000;

    long errorCount =
        futures.stream()
            .map(
                f -> {
                  try {
                    return f.get();
                  } catch (Exception e) {
                    return null;
                  }
                })
            .filter(r -> r == null || r.getStatusCode() != HttpStatus.NO_CONTENT)
            .count();

    assertThat(errorCount)
        .as("All 100 concurrent virtual-thread requests must succeed (no 5xx)")
        .isZero();

    System.out.printf(
        "[VirtualThreadPinningIT] 100 concurrent POST /metrics under MonitorContention: %d ms wall-clock%n",
        wallMillis);

    ResponseEntity<Map<String, Long>> snapshot =
        client
            .get()
            .uri("/metrics/snapshot")
            .retrieve()
            .toEntity(new ParameterizedTypeReference<>() {});

    assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(snapshot.getBody())
        .as("Snapshot must contain the 'test' metric key")
        .containsKey("test");
    assertThat(snapshot.getBody().get("test"))
        .as("Recorded count must equal the number of successful requests")
        .isEqualTo((long) concurrency);
  }
}

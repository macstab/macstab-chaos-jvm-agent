package com.macstab.chaos.examples;

import com.macstab.chaos.bootstrap.ChaosPlatform;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Example service demonstrating chaos platform integration with thread pools and queues. */
public final class ExampleServiceMain {
  private ExampleServiceMain() {}

  /**
   * Starts the example service.
   *
   * @param args command-line arguments
   * @throws Exception if the service fails to start or complete within the timeout
   */
  public static void main(String[] args) throws Exception {
    if (Boolean.getBoolean("macstab.chaos.install.local")) {
      ChaosPlatform.installLocally();
    }
    BlockingQueue<String> queue = new LinkedBlockingQueue<>(16);
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    queue.put("payload");
    CompletableFuture<String> future = new CompletableFuture<>();
    executor.submit(
        () -> {
          try {
            String item = queue.take();
            future.complete(item + ":" + loadResource());
          } catch (Exception exception) {
            future.completeExceptionally(exception);
          }
        });
    scheduler.scheduleAtFixedRate(
        () -> System.out.println("heartbeat"), 10, 50, TimeUnit.MILLISECONDS);
    System.out.println(future.get(2, TimeUnit.SECONDS));
    scheduler.shutdownNow();
    executor.shutdownNow();
  }

  private static String loadResource() throws Exception {
    try (InputStream inputStream =
        ExampleServiceMain.class.getClassLoader().getResourceAsStream("example-resource.txt")) {
      if (inputStream == null) {
        return "missing";
      }
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }
}

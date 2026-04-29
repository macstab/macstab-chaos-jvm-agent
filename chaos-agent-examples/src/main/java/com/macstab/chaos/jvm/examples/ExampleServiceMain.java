package com.macstab.chaos.jvm.examples;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Example service demonstrating chaos platform integration with virtual threads and work pools. */
public final class ExampleServiceMain {

  private static final int WORKER_THREAD_COUNT = 2;
  private static final int QUEUE_CAPACITY = 16;
  private static final long KEEPALIVE_MS = 0L;
  private static final long HEARTBEAT_INITIAL_DELAY_MS = 10;
  private static final long HEARTBEAT_PERIOD_MS = 50;
  private static final long RESULT_TIMEOUT_SECONDS = 2;
  private static final String EXAMPLE_RESOURCE_PATH = "example-resource.txt";

  private ExampleServiceMain() {}

  /**
   * Starts the example service.
   *
   * @param args command-line arguments
   * @throws Exception if the service fails to start or complete within the timeout
   */
  public static void main(final String[] args) throws Exception {
    if (Boolean.getBoolean("macstab.chaos.install.local")) {
      ChaosPlatform.installLocally();
    }
    final ThreadPoolExecutor workerPool = createWorkerPool();
    final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1);
    try {
      final String result = processPayload(workerPool, heartbeatScheduler);
      System.out.println(result);
    } finally {
      heartbeatScheduler.shutdownNow();
      workerPool.shutdownNow();
    }
  }

  private static ThreadPoolExecutor createWorkerPool() {
    return new ThreadPoolExecutor(
        WORKER_THREAD_COUNT,
        WORKER_THREAD_COUNT,
        KEEPALIVE_MS,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>());
  }

  private static String processPayload(
      final ThreadPoolExecutor workerPool, final ScheduledExecutorService heartbeatScheduler)
      throws Exception {
    final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    queue.put("payload");

    final CompletableFuture<String> resultFuture = new CompletableFuture<>();
    workerPool.submit(() -> consumeQueueAndComplete(queue, resultFuture));

    heartbeatScheduler.scheduleAtFixedRate(
        () -> System.out.println("heartbeat"),
        HEARTBEAT_INITIAL_DELAY_MS,
        HEARTBEAT_PERIOD_MS,
        TimeUnit.MILLISECONDS);

    return resultFuture.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static void consumeQueueAndComplete(
      final BlockingQueue<String> queue, final CompletableFuture<String> resultFuture) {
    try {
      final String item = queue.take();
      resultFuture.complete(item + ":" + loadResource());
    } catch (final Exception exception) {
      resultFuture.completeExceptionally(exception);
    }
  }

  private static String loadResource() throws Exception {
    try (final InputStream resourceStream =
        ExampleServiceMain.class.getClassLoader().getResourceAsStream(EXAMPLE_RESOURCE_PATH)) {
      if (resourceStream == null) {
        return "missing";
      }
      return new String(resourceStream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }
}

package io.macstab.chaos.bootstrap;

import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class AgentProcessProbeMain {
  private AgentProcessProbeMain() {}

  public static void main(String[] args) throws Exception {
    switch (args[0]) {
      case "executor" -> executor();
      case "resource" -> resource();
      default -> throw new IllegalArgumentException("unknown mode " + args[0]);
    }
  }

  private static void executor() throws Exception {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    try {
      long start = System.nanoTime();
      executor.execute(() -> {});
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      System.out.println("elapsedMillis=" + elapsed);
    } finally {
      executor.shutdownNow();
    }
  }

  private static void resource() {
    URL url = AgentProcessProbeMain.class.getClassLoader().getResource("probe-resource.txt");
    System.out.println("resource=" + (url == null ? "missing" : "present"));
  }
}

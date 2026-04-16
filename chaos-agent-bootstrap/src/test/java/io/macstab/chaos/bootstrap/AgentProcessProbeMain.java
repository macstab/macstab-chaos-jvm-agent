package io.macstab.chaos.bootstrap;

import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class AgentProcessProbeMain {
  private AgentProcessProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "executor" -> executor();
      case "resource" -> resource();
      case "thread" -> thread();
      case "classload" -> classload();
      default -> throw new IllegalArgumentException("unknown mode " + args[0]);
    }
  }

  private static void executor() throws Exception {
    final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    try {
      final long start = System.nanoTime();
      executor.execute(() -> {});
      final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      System.out.println("elapsedMillis=" + elapsed);
    } finally {
      executor.shutdownNow();
    }
  }

  private static void resource() {
    final URL url = AgentProcessProbeMain.class.getClassLoader().getResource("probe-resource.txt");
    System.out.println("resource=" + (url == null ? "missing" : "present"));
  }

  private static void thread() {
    try {
      final Thread t = new Thread(() -> {}, "chaos-probe-thread");
      t.start();
      t.join();
      System.out.println("thread=started");
    } catch (final RejectedExecutionException e) {
      System.out.println("thread=rejected");
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void classload() {
    try {
      Class.forName(
          "org.assertj.core.api.Assertions", false, AgentProcessProbeMain.class.getClassLoader());
      System.out.println("classload=allowed");
    } catch (final ClassNotFoundException e) {
      System.out.println("classload=rejected");
    }
  }
}

package com.macstab.chaos.jvm.bootstrap;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
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
      case "gc" -> gc();
      case "exit" -> exit();
      case "sleep-suppress" -> sleepSuppress();
      case "sleep-delay" -> sleepDelay();
      case "fileread-delay" -> fileReadDelay();
      case "filewrite-delay" -> fileWriteDelay();
      case "dns-delay" -> dnsDelay();
      case "ssl-delay" -> sslDelay();
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

  private static void gc() {
    System.gc();
    System.out.println("gc=returned");
  }

  private static void exit() {
    try {
      System.exit(42);
      System.out.println("exit=unreachable");
    } catch (final SecurityException e) {
      System.out.println("exit=suppressed");
    }
  }

  private static void sleepSuppress() {
    final long start = System.nanoTime();
    try {
      Thread.sleep(2000);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void sleepDelay() {
    final long start = System.nanoTime();
    try {
      Thread.sleep(10);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void fileReadDelay() throws Exception {
    final String targetFile = System.getProperty("probe.file");
    final long start = System.nanoTime();
    try (final FileInputStream fis = new FileInputStream(targetFile)) {
      fis.read();
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void fileWriteDelay() throws Exception {
    final String targetFile = System.getProperty("probe.file");
    final long start = System.nanoTime();
    try (final FileOutputStream fos = new FileOutputStream(targetFile, true)) {
      fos.write(0);
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void dnsDelay() {
    final long start = System.nanoTime();
    try {
      InetAddress.getByName("127.0.0.1");
    } catch (final Exception ignored) {
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void sslDelay() throws Exception {
    final javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getDefault();
    final javax.net.ssl.SSLEngine engine = ctx.createSSLEngine();
    engine.setUseClientMode(true);
    final long start = System.nanoTime();
    engine.beginHandshake();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }
}

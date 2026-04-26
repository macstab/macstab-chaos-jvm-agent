package com.macstab.chaos.jvm.bootstrap;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

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
      case "executor-submit-delay" -> executorSubmitDelay();
      case "executor-worker-delay" -> executorWorkerDelay();
      case "queue-put-delay" -> queuePutDelay();
      case "queue-take-delay" -> queueTakeDelay();
      case "async-complete-delay" -> asyncCompleteDelay();
      case "reflection-invoke-delay" -> reflectionInvokeDelay();
      case "zip-deflate-delay" -> zipDeflateDelay();
      case "zip-inflate-delay" -> zipInflateDelay();
      case "thread-start-delay" -> threadStartDelay();
      case "monitor-enter-delay" -> monitorEnterDelay();
      case "nio-read-delay" -> nioReadDelay();
      case "nio-write-delay" -> nioWriteDelay();
      case "thread-local-get-delay" -> threadLocalGetDelay();
      case "schedule-submit-delay" -> scheduleSubmitDelay();
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

  // ── New probes ────────────────────────────────────────────────────────────

  private static void executorSubmitDelay() throws Exception {
    final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    try {
      final long start = System.nanoTime();
      executor.execute(() -> {});
      System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      executor.shutdownNow();
    }
  }

  private static void executorWorkerDelay() throws Exception {
    final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    try {
      final CountDownLatch latch = new CountDownLatch(1);
      final long start = System.nanoTime();
      executor.execute(latch::countDown);
      latch.await(30, TimeUnit.SECONDS);
      System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      executor.shutdownNow();
    }
  }

  private static void queuePutDelay() throws Exception {
    final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    final long start = System.nanoTime();
    queue.put("item");
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void queueTakeDelay() throws Exception {
    final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    queue.put("item");
    final long start = System.nanoTime();
    queue.take();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void asyncCompleteDelay() {
    final CompletableFuture<String> future = new CompletableFuture<>();
    final long start = System.nanoTime();
    future.complete("done");
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void reflectionInvokeDelay() throws Exception {
    final Method method = String.class.getMethod("length");
    final long start = System.nanoTime();
    method.invoke("hello");
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void zipDeflateDelay() {
    final byte[] input = new byte[1024];
    final byte[] output = new byte[2048];
    final Deflater deflater = new Deflater();
    deflater.setInput(input);
    deflater.finish();
    final long start = System.nanoTime();
    deflater.deflate(output);
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    deflater.end();
  }

  private static void zipInflateDelay() throws Exception {
    final byte[] raw =
        "chaos probe data chaos probe data chaos probe data".repeat(5).getBytes();
    final byte[] compressed = new byte[4096];
    final Deflater deflater = new Deflater();
    deflater.setInput(raw);
    deflater.finish();
    final int compressedLen = deflater.deflate(compressed);
    deflater.end();

    final byte[] output = new byte[4096];
    final Inflater inflater = new Inflater();
    inflater.setInput(compressed, 0, compressedLen);
    final long start = System.nanoTime();
    inflater.inflate(output);
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    inflater.end();
  }

  private static void threadStartDelay() throws Exception {
    final Thread t = new Thread(() -> {}, "chaos-probe-start");
    final long start = System.nanoTime();
    t.start();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    t.join();
  }

  private static void monitorEnterDelay() {
    // MONITOR_ENTER instrumentation targets AbstractQueuedSynchronizer.acquire(),
    // which is the hotpath for ReentrantLock — not raw synchronized bytecode.
    final ReentrantLock lock = new ReentrantLock();
    final long start = System.nanoTime();
    lock.lock();
    try {
      // chaos fires on AQS.acquire() inside lock()
    } finally {
      lock.unlock();
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void nioReadDelay() throws Exception {
    // NIO_CHANNEL_READ instrumentation targets subtypes of SocketChannel, not Pipe.
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      try (final SocketChannel client = SocketChannel.open(server.getLocalAddress());
          final SocketChannel peer = server.accept()) {
        peer.write(ByteBuffer.wrap(new byte[] {42}));
        final ByteBuffer readBuf = ByteBuffer.allocate(1);
        final long start = System.nanoTime();
        client.read(readBuf);
        System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
    }
  }

  private static void nioWriteDelay() throws Exception {
    // NIO_CHANNEL_WRITE instrumentation targets subtypes of SocketChannel, not Pipe.
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      try (final SocketChannel client = SocketChannel.open(server.getLocalAddress());
          final SocketChannel peer = server.accept()) {
        final long start = System.nanoTime();
        client.write(ByteBuffer.wrap(new byte[] {42}));
        System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        peer.read(ByteBuffer.allocate(1)); // drain so close doesn't RST
      }
    }
  }

  private static void threadLocalGetDelay() {
    final ThreadLocal<String> local = new ThreadLocal<>();
    local.set("probe-value");
    final long start = System.nanoTime();
    local.get();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void scheduleSubmitDelay() throws Exception {
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      final long start = System.nanoTime();
      scheduler.schedule(() -> {}, 0L, TimeUnit.MILLISECONDS);
      System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      scheduler.shutdownNow();
    }
  }
}

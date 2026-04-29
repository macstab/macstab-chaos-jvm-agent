package com.macstab.chaos.jvm.bootstrap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;

public final class AgentProcessProbeMain {
  private AgentProcessProbeMain() {}

  // Expose defineClass for the CLASS_DEFINE probe.
  private static final class DefineTestLoader extends ClassLoader {
    DefineTestLoader() {
      super(null);
    }

    Class<?> define(final String name, final byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  // Simple serializable value for OBJECT_SERIALIZE / OBJECT_DESERIALIZE probes.
  private static final class ProbeValue implements Serializable {
    private static final long serialVersionUID = 1L;
    final String v = "chaos";
  }

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      // ── legacy / non-timing probes ──────────────────────────────────────────
      case "executor" -> executor();
      case "resource" -> resource();
      case "thread" -> thread();
      case "classload" -> classload();
      case "gc" -> gc();
      case "exit" -> exit();
      // ── Thread.sleep ────────────────────────────────────────────────────────
      case "sleep-suppress" -> sleepSuppress();
      case "sleep-delay" -> sleepDelay();
      // ── File I/O ────────────────────────────────────────────────────────────
      case "fileread-delay" -> fileReadDelay();
      case "filewrite-delay" -> fileWriteDelay();
      // ── DNS / SSL ───────────────────────────────────────────────────────────
      case "dns-delay" -> dnsDelay();
      case "ssl-delay" -> sslDelay();
      // ── Executor ────────────────────────────────────────────────────────────
      case "executor-submit-delay" -> executorSubmitDelay();
      case "executor-worker-delay" -> executorWorkerDelay();
      case "executor-shutdown-delay" -> executorShutdownDelay();
      case "executor-await-termination-delay" -> executorAwaitTerminationDelay();
      // ── ForkJoin ────────────────────────────────────────────────────────────
      case "fork-join-delay" -> forkJoinDelay();
      // ── Queue ───────────────────────────────────────────────────────────────
      case "queue-put-delay" -> queuePutDelay();
      case "queue-take-delay" -> queueTakeDelay();
      case "queue-offer-delay" -> queueOfferDelay();
      case "queue-poll-delay" -> queuePollDelay();
      // ── Async ───────────────────────────────────────────────────────────────
      case "async-complete-delay" -> asyncCompleteDelay();
      case "async-complete-exceptionally-delay" -> asyncCompleteExceptionallyDelay();
      case "async-cancel-delay" -> asyncCancelDelay();
      // ── JVM runtime (reflection, zip, buffers, clocks …) ───────────────────
      case "reflection-invoke-delay" -> reflectionInvokeDelay();
      case "zip-deflate-delay" -> zipDeflateDelay();
      case "zip-inflate-delay" -> zipInflateDelay();
      case "instant-now-delay" -> instantNowDelay();
      case "local-datetime-now-delay" -> localDateTimeNowDelay();
      case "zoned-datetime-now-delay" -> zonedDateTimeNowDelay();
      case "date-new-delay" -> dateNewDelay();
      case "system-gc-delay" -> systemGcDelay();
      case "system-exit-suppress" -> systemExitSuppress();
      case "direct-buffer-delay" -> directBufferDelay();
      case "object-serialize-delay" -> objectSerializeDelay();
      case "object-deserialize-delay" -> objectDeserializeDelay();
      case "native-lib-load-delay" -> nativeLibLoadDelay();
      case "jndi-lookup-delay" -> jndiLookupDelay();
      case "jmx-get-attr-delay" -> jmxGetAttrDelay();
      case "jmx-invoke-delay" -> jmxInvokeDelay();
      // ── Threading ───────────────────────────────────────────────────────────
      case "thread-start-delay" -> threadStartDelay();
      case "virtual-thread-start-delay" -> virtualThreadStartDelay();
      case "monitor-enter-delay" -> monitorEnterDelay();
      case "thread-park-delay" -> threadParkDelay();
      case "thread-local-get-delay" -> threadLocalGetDelay();
      case "thread-local-set-delay" -> threadLocalSetDelay();
      // ── Shutdown / class-loading ─────────────────────────────────────────────
      case "shutdown-hook-delay" -> shutdownHookDelay();
      case "class-load-delay" -> classLoadDelay();
      case "class-define-delay" -> classDefineDelay();
      case "resource-load-delay" -> resourceLoadDelay();
      // ── Scheduling ──────────────────────────────────────────────────────────
      case "schedule-submit-delay" -> scheduleSubmitDelay();
      case "schedule-tick-delay" -> scheduleTickDelay();
      // ── NIO ─────────────────────────────────────────────────────────────────
      case "nio-read-delay" -> nioReadDelay();
      case "nio-write-delay" -> nioWriteDelay();
      case "nio-selector-select-delay" -> nioSelectorSelectDelay();
      case "nio-channel-connect-delay" -> nioChannelConnectDelay();
      case "nio-channel-accept-delay" -> nioChannelAcceptDelay();
      // ── Sockets ─────────────────────────────────────────────────────────────
      case "socket-connect-delay" -> socketConnectDelay();
      case "socket-accept-delay" -> socketAcceptDelay();
      case "socket-read-delay" -> socketReadDelay();
      case "socket-write-delay" -> socketWriteDelay();
      case "socket-close-delay" -> socketCloseDelay();
      // ── HTTP client ──────────────────────────────────────────────────────────
      case "http-client-send-delay" -> httpClientSendDelay();
      case "http-client-send-async-delay" -> httpClientSendAsyncDelay();
      // ── JDBC ─────────────────────────────────────────────────────────────────
      case "jdbc-statement-delay" -> jdbcStatementDelay();
      case "jdbc-prepared-statement-delay" -> jdbcPreparedStatementDelay();
      case "jdbc-commit-delay" -> jdbcCommitDelay();
      case "jdbc-rollback-delay" -> jdbcRollbackDelay();
      case "jdbc-connection-acquire-delay" -> jdbcConnectionAcquireDelay();
      default -> throw new IllegalArgumentException("unknown mode " + args[0]);
    }
  }

  // ── legacy / non-timing probes ──────────────────────────────────────────────

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

  // ── Thread.sleep ─────────────────────────────────────────────────────────────

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

  // ── File I/O ──────────────────────────────────────────────────────────────────

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

  // ── DNS / SSL ─────────────────────────────────────────────────────────────────

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

  // ── Executor ──────────────────────────────────────────────────────────────────

  private static void executorSubmitDelay() throws Exception {
    final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    try {
      final long start = System.nanoTime();
      executor.execute(() -> {});
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
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
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      executor.shutdownNow();
    }
  }

  private static void executorShutdownDelay() throws Exception {
    final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    final long start = System.nanoTime();
    executor.shutdown();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  private static void executorAwaitTerminationDelay() throws Exception {
    final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    executor.shutdown();
    final long start = System.nanoTime();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  // ── ForkJoin ──────────────────────────────────────────────────────────────────

  private static void forkJoinDelay() {
    final ForkJoinPool pool = new ForkJoinPool(1);
    try {
      final long start = System.nanoTime();
      pool.invoke(
          new RecursiveAction() {
            @Override
            protected void compute() {}
          });
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      pool.shutdown();
    }
  }

  // ── Queue ─────────────────────────────────────────────────────────────────────

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

  private static void queueOfferDelay() {
    final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    final long start = System.nanoTime();
    queue.offer("item");
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void queuePollDelay() {
    final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    queue.offer("item");
    final long start = System.nanoTime();
    queue.poll();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  // ── Async ─────────────────────────────────────────────────────────────────────

  private static void asyncCompleteDelay() {
    final CompletableFuture<String> future = new CompletableFuture<>();
    final long start = System.nanoTime();
    future.complete("done");
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void asyncCompleteExceptionallyDelay() {
    final CompletableFuture<String> future = new CompletableFuture<>();
    final long start = System.nanoTime();
    future.completeExceptionally(new RuntimeException("probe"));
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void asyncCancelDelay() {
    final CompletableFuture<String> future = new CompletableFuture<>();
    final long start = System.nanoTime();
    future.cancel(false);
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  // ── JVM runtime ───────────────────────────────────────────────────────────────

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
    final byte[] raw = "chaos probe data chaos probe data chaos probe data".repeat(5).getBytes();
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

  private static void instantNowDelay() {
    // Clock skew is applied to the returned Instant value, not wall-clock time.
    final long realMillis = System.currentTimeMillis();
    final long returnedMillis = Instant.now().toEpochMilli();
    System.out.println("skewSeconds=" + ((returnedMillis - realMillis) / 1000));
  }

  private static void localDateTimeNowDelay() {
    // LocalDateTime has no zone; compare against the same zone as System.currentTimeMillis.
    final java.time.Instant realInstant =
        java.time.Instant.ofEpochMilli(System.currentTimeMillis());
    final LocalDateTime returned = LocalDateTime.now();
    final LocalDateTime real =
        LocalDateTime.ofInstant(realInstant, java.time.ZoneId.systemDefault());
    System.out.println(
        "skewSeconds=" + java.time.temporal.ChronoUnit.SECONDS.between(real, returned));
  }

  private static void zonedDateTimeNowDelay() {
    final long realMillis = System.currentTimeMillis();
    final long returnedMillis = ZonedDateTime.now().toInstant().toEpochMilli();
    System.out.println("skewSeconds=" + ((returnedMillis - realMillis) / 1000));
  }

  private static void dateNewDelay() {
    final long realMillis = System.currentTimeMillis();
    final long returnedMillis = new Date().getTime();
    System.out.println("skewSeconds=" + ((returnedMillis - realMillis) / 1000));
  }

  private static void systemGcDelay() {
    final long start = System.nanoTime();
    System.gc();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void systemExitSuppress() {
    // Chaos suppress effect makes System.exit() throw SecurityException instead of exiting.
    try {
      System.exit(42);
    } catch (final SecurityException ignored) {
    }
    System.out.println("exit=suppressed");
  }

  private static void directBufferDelay() {
    final long start = System.nanoTime();
    ByteBuffer.allocateDirect(64);
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void objectSerializeDelay() throws Exception {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final ObjectOutputStream oos = new ObjectOutputStream(baos);
    final long start = System.nanoTime();
    oos.writeObject(new ProbeValue());
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    oos.close();
  }

  private static void objectDeserializeDelay() throws Exception {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (final ObjectOutputStream oos = new ObjectOutputStream(baos)) {
      oos.writeObject(new ProbeValue());
    }
    final byte[] bytes = baos.toByteArray();
    final ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
    final long start = System.nanoTime();
    ois.readObject();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    ois.close();
  }

  private static void nativeLibLoadDelay() {
    // Chaos fires at NativeLibraries.load() entry before the actual native load attempt.
    // The load will fail (nonexistent path), which is expected.
    final long start = System.nanoTime();
    try {
      System.load("/nonexistent_chaos_probe_lib_" + System.nanoTime() + ".so");
    } catch (final UnsatisfiedLinkError ignored) {
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void jndiLookupDelay() {
    // Chaos fires at InitialContext.lookup() entry; NamingException is expected without a provider.
    final long start = System.nanoTime();
    try {
      new InitialContext().lookup("java:comp/env/probe");
    } catch (final Exception ignored) {
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void jmxGetAttrDelay() throws Exception {
    final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    final ObjectName name = new ObjectName("java.lang:type=Runtime");
    final long start = System.nanoTime();
    mbs.getAttribute(name, "Uptime");
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void jmxInvokeDelay() throws Exception {
    final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    final ObjectName name = new ObjectName("java.lang:type=Memory");
    final long start = System.nanoTime();
    mbs.invoke(name, "gc", new Object[0], new String[0]);
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  // ── Threading ─────────────────────────────────────────────────────────────────

  private static void threadStartDelay() throws Exception {
    final Thread t = new Thread(() -> {}, "chaos-probe-start");
    final long start = System.nanoTime();
    t.start();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    t.join();
  }

  private static void virtualThreadStartDelay() throws Exception {
    // Virtual threads share Thread.start0 with platform threads; the dispatcher distinguishes
    // them via Thread.isVirtual() and emits VIRTUAL_THREAD_START vs THREAD_START accordingly.
    final Thread vt = Thread.ofVirtual().name("chaos-probe-virtual-start").unstarted(() -> {});
    final long start = System.nanoTime();
    vt.start();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    vt.join();
  }

  private static void monitorEnterDelay() throws Exception {
    // AQS.acquire(int) is the MONITOR_ENTER hook target. It is only called on the
    // contended (slow) path — when the lock is already held by another thread.
    // Hold the lock from a background thread to force the main thread onto that path.
    final ReentrantLock lock = new ReentrantLock(false);
    final CountDownLatch held = new CountDownLatch(1);
    final Thread holder =
        new Thread(
            () -> {
              lock.lock();
              try {
                held.countDown();
                Thread.sleep(50); // hold briefly; chaos delay (200ms) covers this window
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            });
    holder.setDaemon(true);
    holder.start();
    held.await(); // ensure the holder actually has the lock before we try to acquire
    final long start = System.nanoTime();
    lock.lock(); // contested → initialTryLock() fails → AQS.acquire(1) called → chaos fires
    try {
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      lock.unlock();
    }
    holder.join(5000);
  }

  private static void threadParkDelay() {
    // LockSupport.parkNanos() is hooked for THREAD_PARK. The 1ms timeout ensures the
    // probe does not block indefinitely after the chaos delay fires and park() runs.
    final long start = System.nanoTime();
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void threadLocalGetDelay() {
    final ThreadLocal<String> local = new ThreadLocal<>();
    local.set("probe-value");
    final long start = System.nanoTime();
    local.get();
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void threadLocalSetDelay() {
    final ThreadLocal<String> local = new ThreadLocal<>();
    final long start = System.nanoTime();
    local.set("probe-value");
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  // ── Shutdown / class-loading ──────────────────────────────────────────────────

  private static void shutdownHookDelay() throws Exception {
    final Thread hook = new Thread(() -> {}, "chaos-probe-hook");
    final long start = System.nanoTime();
    Runtime.getRuntime().addShutdownHook(hook);
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    Runtime.getRuntime().removeShutdownHook(hook);
  }

  private static void classLoadDelay() throws Exception {
    // loadClass() fires the hook at method entry even for already-cached classes.
    // Use this class itself so the chaos NamePattern can be kept tight.
    final long start = System.nanoTime();
    ClassLoader.getSystemClassLoader().loadClass(AgentProcessProbeMain.class.getName());
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void classDefineDelay() throws Exception {
    // Read this class's own bytecode to feed into defineClass in an isolated loader.
    // defineClass() is the CLASS_DEFINE hook target; the class may fail verification in
    // the isolated loader but the chaos advice fires at method entry regardless.
    final byte[] bytecode;
    try (final InputStream is =
        AgentProcessProbeMain.class.getResourceAsStream("AgentProcessProbeMain.class")) {
      bytecode = is.readAllBytes();
    }
    final DefineTestLoader loader = new DefineTestLoader();
    final long start = System.nanoTime();
    try {
      loader.define(AgentProcessProbeMain.class.getName(), bytecode);
    } catch (final LinkageError ignored) {
    }
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static void resourceLoadDelay() {
    // getResource() fires the RESOURCE_LOAD hook even when the resource is not found.
    final long start = System.nanoTime();
    ClassLoader.getSystemClassLoader().getResource("probe-chaos-resource-nonexistent.txt");
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  // ── Scheduling ────────────────────────────────────────────────────────────────

  private static void scheduleSubmitDelay() throws Exception {
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      final long start = System.nanoTime();
      scheduler.schedule(() -> {}, 0L, TimeUnit.MILLISECONDS);
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static void scheduleTickDelay() throws Exception {
    // SCHEDULE_TICK fires inside ScheduledRunnableWrapper.run() — the per-tick hook applied to
    // every execution of a scheduled task. Measure elapsed wall-clock time inside the task body
    // (relative to its scheduled start), so the delay added by the chaos hook is observable.
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      final CountDownLatch ran = new CountDownLatch(1);
      final long[] elapsedNanos = new long[1];
      final long scheduledAt = System.nanoTime();
      scheduler.schedule(
          () -> {
            elapsedNanos[0] = System.nanoTime() - scheduledAt;
            ran.countDown();
          },
          0L,
          TimeUnit.MILLISECONDS);
      if (!ran.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("scheduled tick never fired");
      }
      System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(elapsedNanos[0]));
    } finally {
      scheduler.shutdownNow();
    }
  }

  // ── NIO ───────────────────────────────────────────────────────────────────────

  private static void nioReadDelay() throws Exception {
    // NIO_CHANNEL_READ targets SocketChannel subtypes only (not Pipe).
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      try (final SocketChannel client = SocketChannel.open(server.getLocalAddress());
          final SocketChannel peer = server.accept()) {
        peer.write(ByteBuffer.wrap(new byte[] {42}));
        final ByteBuffer readBuf = ByteBuffer.allocate(1);
        final long start = System.nanoTime();
        client.read(readBuf);
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
    }
  }

  private static void nioWriteDelay() throws Exception {
    // NIO_CHANNEL_WRITE targets SocketChannel subtypes only (not Pipe).
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      try (final SocketChannel client = SocketChannel.open(server.getLocalAddress());
          final SocketChannel peer = server.accept()) {
        final long start = System.nanoTime();
        client.write(ByteBuffer.wrap(new byte[] {42}));
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        peer.read(ByteBuffer.allocate(1));
      }
    }
  }

  private static void nioSelectorSelectDelay() throws Exception {
    // select(long timeout) fires NIO_SELECTOR_SELECT; no channels registered so it returns
    // after the timeout. Chaos delay fires before the actual select.
    try (final Selector selector = Selector.open()) {
      final long start = System.nanoTime();
      selector.select(1L);
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
  }

  private static void nioChannelConnectDelay() throws Exception {
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      final Thread acceptor =
          new Thread(
              () -> {
                try {
                  server.accept().close();
                } catch (final Exception ignored) {
                }
              });
      acceptor.setDaemon(true);
      acceptor.start();
      try (final SocketChannel client = SocketChannel.open()) {
        final long start = System.nanoTime();
        client.connect(server.getLocalAddress()); // NIO_CHANNEL_CONNECT fires here
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
      acceptor.join(5000);
    }
  }

  private static void nioChannelAcceptDelay() throws Exception {
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      final Thread connector =
          new Thread(
              () -> {
                try {
                  Thread.sleep(30);
                  SocketChannel.open(server.getLocalAddress()).close();
                } catch (final Exception ignored) {
                }
              });
      connector.setDaemon(true);
      connector.start();
      final long start = System.nanoTime();
      try (final SocketChannel accepted = server.accept()) { // NIO_CHANNEL_ACCEPT fires here
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
      connector.join(5000);
    }
  }

  // ── Sockets ───────────────────────────────────────────────────────────────────

  private static void socketConnectDelay() throws Exception {
    try (final ServerSocket server = new ServerSocket(0)) {
      final Thread acceptor =
          new Thread(
              () -> {
                try {
                  server.accept().close();
                } catch (final Exception ignored) {
                }
              });
      acceptor.setDaemon(true);
      acceptor.start();
      try (final Socket socket = new Socket()) {
        final long start = System.nanoTime();
        socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 5000);
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
      acceptor.join(5000);
    }
  }

  private static void socketAcceptDelay() throws Exception {
    try (final ServerSocket server = new ServerSocket(0)) {
      final Thread connector =
          new Thread(
              () -> {
                try {
                  Thread.sleep(30);
                  new Socket("127.0.0.1", server.getLocalPort()).close();
                } catch (final Exception ignored) {
                }
              });
      connector.setDaemon(true);
      connector.start();
      final long start = System.nanoTime();
      try (final Socket accepted = server.accept()) { // SOCKET_ACCEPT fires here
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
      connector.join(5000);
    }
  }

  private static void socketReadDelay() throws Exception {
    try (final ServerSocket server = new ServerSocket(0)) {
      final Thread sender =
          new Thread(
              () -> {
                try {
                  Thread.sleep(10);
                  try (final Socket s = new Socket("127.0.0.1", server.getLocalPort())) {
                    s.getOutputStream().write(new byte[] {42, 43, 44});
                  }
                } catch (final Exception ignored) {
                }
              });
      sender.setDaemon(true);
      sender.start();
      try (final Socket accepted = server.accept()) {
        final byte[] buf = new byte[3];
        final long start = System.nanoTime();
        accepted.getInputStream().read(buf, 0, buf.length); // SOCKET_READ fires here
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
      sender.join(5000);
    }
  }

  private static void socketWriteDelay() throws Exception {
    try (final ServerSocket server = new ServerSocket(0)) {
      final Thread acceptor =
          new Thread(
              () -> {
                try {
                  final Socket s = server.accept();
                  s.getInputStream().read(new byte[1]);
                  s.close();
                } catch (final Exception ignored) {
                }
              });
      acceptor.setDaemon(true);
      acceptor.start();
      try (final Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
        final long start = System.nanoTime();
        socket.getOutputStream().write(new byte[] {42}, 0, 1); // SOCKET_WRITE fires here
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
      acceptor.join(5000);
    }
  }

  private static void socketCloseDelay() throws Exception {
    try (final ServerSocket server = new ServerSocket(0)) {
      final Thread acceptor =
          new Thread(
              () -> {
                try {
                  server.accept().close();
                } catch (final Exception ignored) {
                }
              });
      acceptor.setDaemon(true);
      acceptor.start();
      final Socket socket = new Socket("127.0.0.1", server.getLocalPort());
      final long start = System.nanoTime();
      socket.close(); // SOCKET_CLOSE fires here
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      acceptor.join(5000);
    }
  }

  // ── HTTP client ───────────────────────────────────────────────────────────────

  private static void httpClientSendDelay() throws Exception {
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      final int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
      final Thread handler =
          new Thread(
              () -> {
                try {
                  final SocketChannel ch = server.accept();
                  final ByteBuffer req = ByteBuffer.allocate(4096);
                  ch.read(req);
                  ch.write(
                      ByteBuffer.wrap(
                          "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                              .getBytes()));
                  ch.close();
                } catch (final Exception ignored) {
                }
              });
      handler.setDaemon(true);
      handler.start();

      final HttpClient client = HttpClient.newHttpClient();
      final HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/probe")).build();
      final long start = System.nanoTime();
      client.send(request, HttpResponse.BodyHandlers.discarding());
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      handler.join(5000);
    }
  }

  private static void httpClientSendAsyncDelay() throws Exception {
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      final int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
      final Thread handler =
          new Thread(
              () -> {
                try {
                  final SocketChannel ch = server.accept();
                  final ByteBuffer req = ByteBuffer.allocate(4096);
                  ch.read(req);
                  ch.write(
                      ByteBuffer.wrap(
                          "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                              .getBytes()));
                  ch.close();
                } catch (final Exception ignored) {
                }
              });
      handler.setDaemon(true);
      handler.start();

      final HttpClient client = HttpClient.newHttpClient();
      final HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/probe")).build();
      final long start = System.nanoTime();
      client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).join();
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      handler.join(5000);
    }
  }

  // ── JDBC ──────────────────────────────────────────────────────────────────────

  private static void jdbcStatementDelay() throws Exception {
    try (final java.sql.Connection conn =
        DriverManager.getConnection("jdbc:h2:mem:probe_stmt;DB_CLOSE_DELAY=-1")) {
      conn.createStatement().execute("CREATE TABLE IF NOT EXISTS t (id INT)");
      final long start = System.nanoTime();
      conn.createStatement().execute("SELECT 1"); // JDBC_STATEMENT_EXECUTE fires here
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
  }

  private static void jdbcPreparedStatementDelay() throws Exception {
    try (final java.sql.Connection conn =
        DriverManager.getConnection("jdbc:h2:mem:probe_ps;DB_CLOSE_DELAY=-1")) {
      final long start = System.nanoTime();
      conn.prepareStatement("SELECT ?"); // JDBC_PREPARED_STATEMENT fires here
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
  }

  private static void jdbcCommitDelay() throws Exception {
    try (final java.sql.Connection conn =
        DriverManager.getConnection("jdbc:h2:mem:probe_commit;DB_CLOSE_DELAY=-1")) {
      conn.setAutoCommit(false);
      conn.createStatement().execute("CREATE TABLE IF NOT EXISTS t (id INT)");
      final long start = System.nanoTime();
      conn.commit(); // JDBC_TRANSACTION_COMMIT fires here
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
  }

  private static void jdbcRollbackDelay() throws Exception {
    try (final java.sql.Connection conn =
        DriverManager.getConnection("jdbc:h2:mem:probe_rollback;DB_CLOSE_DELAY=-1")) {
      conn.setAutoCommit(false);
      conn.createStatement().execute("CREATE TABLE IF NOT EXISTS t (id INT)");
      final long start = System.nanoTime();
      conn.rollback(); // JDBC_TRANSACTION_ROLLBACK fires here
      System.out.println(
          "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
  }

  private static void jdbcConnectionAcquireDelay() throws Exception {
    final HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:probe_hikari;DB_CLOSE_DELAY=-1");
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    try (final HikariDataSource ds = new HikariDataSource(config)) {
      final long start = System.nanoTime();
      try (final java.sql.Connection conn = ds.getConnection()) { // HikariPool.getConnection fires
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
    }
  }
}

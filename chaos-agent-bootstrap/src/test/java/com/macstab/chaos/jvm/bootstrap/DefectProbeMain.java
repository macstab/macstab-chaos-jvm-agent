package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosHttpSuppressException;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPOutputStream;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Probe main for {@link DefectRegressionIntegrationTest}. Each mode exercises one instrumented call
 * path inside a JVM started with {@code -javaagent:} and prints {@code key=value} lines that the
 * test asserts on. Every mode ends with {@link #printCounts()}, which prints each scenario's
 * matched and applied counters as {@code matched.<id>} / {@code applied.<id>}.
 */
public final class DefectProbeMain {

  private DefectProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "schedule-sub-millisecond-delay" -> scheduleSubMillisecondDelay();
      case "schedule-sub-millisecond-period" -> scheduleSubMillisecondPeriod();
      case "schedule-submit-delay" -> scheduleSubmitDelay();
      case "dns-get-by-name" -> InetAddress.getByName("localhost");
      case "gzip-write" -> gzipWrite();
      case "jmx-get-attribute" -> jmxGetAttribute();
      case "delay-queue-put" -> delayQueuePut();
      case "priority-queue-put" -> new PriorityBlockingQueue<String>().put("item");
      case "url-class-loader-load" -> urlClassLoaderLoad();
      case "get-resource" -> getResource();
      case "park-until-in-session" -> parkUntilInSession();
      case "jdbc-hikari-execute" -> jdbcHikariExecute();
      case "jdbc-prepared-execute" -> jdbcPreparedExecute();
      case "virtual-thread-executor" -> virtualThreadExecutor();
      case "initialize-retry" -> initializeRetry();
      case "second-agent-copy" -> secondAgentCopy();
      case "clock-now-in-zone" -> clockNowInZone();
      case "socket-read-loopback" -> socketReadLoopback();
      case "direct-buffer-release" -> directBufferRelease();
      case "bridge-jars-on-disk" -> bridgeJarsOnDisk();
      case "woven-groups" -> printWovenGroups();
      case "woven-groups-after-thread-local" -> wovenGroupsAfterThreadLocal();
      case "virtual-thread-start" -> Thread.ofVirtual().start(() -> {}).join();
      case "runtime-exit" -> runtimeExit();
      case "select-consumer" -> selectConsumer();
      case "sleep-duration" -> sleepDuration();
      case "jdk-send-async" -> withHttpServer(DefectProbeMain::jdkSendAsync);
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    printCounts();
  }

  /** Prints the remaining delay of a 1900 µs schedule right after submission. */
  private static void scheduleSubMillisecondDelay() throws Exception {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    try {
      // Warm up the schedule path (class loading, dispatch) so the measured call is not dominated
      // by first-call overhead.
      executor.schedule(() -> {}, 0L, TimeUnit.MILLISECONDS).get(5L, TimeUnit.SECONDS);
      final ScheduledFuture<?> future = executor.schedule(() -> {}, 1900L, TimeUnit.MICROSECONDS);
      System.out.println("remainingNanos=" + future.getDelay(TimeUnit.NANOSECONDS));
      future.get(5L, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  /** Counts the ticks of a 300 µs fixed-rate schedule over 300 ms. */
  private static void scheduleSubMillisecondPeriod() throws Exception {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final AtomicLong ticks = new AtomicLong();
    try {
      executor.scheduleAtFixedRate(ticks::incrementAndGet, 0L, 300L, TimeUnit.MICROSECONDS);
      Thread.sleep(300L);
      System.out.println("ticks=" + ticks.get());
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Schedules one task with no delay under a SCHEDULE_SUBMIT delay scenario and prints how long the
   * submitting thread was blocked and how long the task took to start.
   */
  private static void scheduleSubmitDelay() throws Exception {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final CountDownLatch ran = new CountDownLatch(1);
    final AtomicLong startedAtNanos = new AtomicLong();
    try {
      final long submitStart = System.nanoTime();
      executor.schedule(
          () -> {
            startedAtNanos.set(System.nanoTime());
            ran.countDown();
          },
          0L,
          TimeUnit.MILLISECONDS);
      final long submitReturned = System.nanoTime();
      if (!ran.await(10L, TimeUnit.SECONDS)) {
        throw new IllegalStateException("scheduled task never ran");
      }
      System.out.println(
          "submitBlockedMillis=" + TimeUnit.NANOSECONDS.toMillis(submitReturned - submitStart));
      System.out.println(
          "taskStartMillis=" + TimeUnit.NANOSECONDS.toMillis(startedAtNanos.get() - submitStart));
    } finally {
      executor.shutdownNow();
    }
  }

  private static void gzipWrite() throws Exception {
    try (final GZIPOutputStream gzip = new GZIPOutputStream(new ByteArrayOutputStream())) {
      gzip.write(new byte[] {1, 2, 3});
    }
  }

  private static void jmxGetAttribute() throws Exception {
    final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    System.out.println(
        "uptime=" + server.getAttribute(new ObjectName("java.lang:type=Runtime"), "Uptime"));
  }

  /** Puts one element through the BlockingQueue interface, i.e. via the generic bridge. */
  private static void delayQueuePut() throws Exception {
    final BlockingQueue<Delayed> queue = new DelayQueue<>();
    queue.put(new ImmediateDelayed());
    System.out.println("queueSize=" + queue.size());
  }

  private static void urlClassLoaderLoad() throws Exception {
    try (final URLClassLoader loader =
        new URLClassLoader(new URL[0], DefectProbeMain.class.getClassLoader())) {
      loader.loadClass("java.util.ArrayList");
    }
  }

  private static void getResource() {
    System.out.println(
        "resourceFound="
            + (DefectProbeMain.class.getClassLoader().getResource("java/lang/Object.class")
                != null));
  }

  /**
   * Activates a session-scoped THREAD_PARK scenario and calls {@code LockSupport.parkUntil(Object,
   * long)} once on the bound thread. The session keeps parks of JVM background threads out of the
   * count.
   */
  private static void parkUntilInSession() {
    try (ChaosSession session = ChaosPlatform.current().openSession("park")) {
      session.activate(
          ChaosScenario.builder("park-delay")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.monitor(Set.of(OperationType.THREAD_PARK)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1L)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      try (ChaosSession.ScopeBinding binding = session.bind()) {
        LockSupport.parkUntil(new Object(), System.currentTimeMillis() + 1L);
      }
      printCounts();
    }
  }

  private static void jdbcHikariExecute() throws Exception {
    final HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:defects;DB_CLOSE_DELAY=-1");
    config.setPoolName("defect-pool");
    config.setMaximumPoolSize(1);
    try (final HikariDataSource dataSource = new HikariDataSource(config);
        final Connection connection = dataSource.getConnection();
        final Statement statement = connection.createStatement()) {
      statement.execute("SELECT 1");
    }
  }

  private static void jdbcPreparedExecute() throws Exception {
    try (final Connection connection = DriverManager.getConnection("jdbc:h2:mem:prepared");
        final PreparedStatement statement = connection.prepareStatement("SELECT 1");
        final ResultSet resultSet = statement.executeQuery()) {
      System.out.println("row=" + resultSet.next());
    }
  }

  /**
   * Attaches the agent with a startup plan that fails to activate, then again with a valid plan.
   * The second installation must be the only one in effect: one set of transformers (a sleep delay
   * applied once, not twice) and the diagnostics MBean reporting the second runtime.
   */
  private static void initializeRetry() throws Exception {
    final java.lang.instrument.Instrumentation instrumentation =
        net.bytebuddy.agent.ByteBuddyAgent.install();
    final String failingPlan =
        "{\"metadata\":{\"name\":\"failing\"},\"scenarios\":[{\"id\":\"deadlock\","
            + "\"scope\":\"JVM\",\"selector\":{\"type\":\"stress\",\"target\":\"DEADLOCK\"},"
            + "\"effect\":{\"type\":\"deadlock\",\"participantCount\":2}}]}";
    try {
      ChaosAgentBootstrap.initialize("configJson=" + failingPlan, instrumentation, Map.of());
      System.out.println("firstFailed=false");
    } catch (final RuntimeException expected) {
      System.out.println("firstFailed=true");
    }
    final String sleepPlan =
        "{\"metadata\":{\"name\":\"retry\"},\"scenarios\":[{\"id\":\"sleep-delay\","
            + "\"scope\":\"JVM\",\"selector\":{\"type\":\"thread\","
            + "\"operations\":[\"THREAD_SLEEP\"],\"kind\":\"ANY\"},"
            + "\"effect\":{\"type\":\"delay\",\"minDelay\":\"PT0.3S\",\"maxDelay\":\"PT0.3S\"}}]}";
    ChaosAgentBootstrap.initialize("configJson=" + sleepPlan, instrumentation, Map.of());
    final long start = System.nanoTime();
    Thread.sleep(1L);
    System.out.println("sleepMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    final Object dump =
        ManagementFactory.getPlatformMBeanServer()
            .invoke(
                new ObjectName("com.macstab.chaos.jvm:type=ChaosDiagnostics"),
                "debugDump",
                new Object[0],
                new String[0]);
    System.out.println("mbeanReportsRetry=" + String.valueOf(dump).contains("sleep-delay"));
  }

  /**
   * Installs the agent, then loads a second copy of the agent classes in an isolated class loader
   * (as a child-first web application would) and lets it try to install. The second copy must be
   * refused instead of replacing the first copy's dispatcher delegate.
   */
  private static void secondAgentCopy() throws Exception {
    final ChaosControlPlane first = ChaosPlatform.installLocally();
    first.activate(
        ChaosScenario.builder("first-copy-sleep")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.thread(
                    Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY))
            .effect(ChaosEffect.delay(Duration.ofMillis(300L)))
            .activationPolicy(ActivationPolicy.always())
            .build());
    final URL[] classpath =
        Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .map(
                entry -> {
                  try {
                    return Path.of(entry).toUri().toURL();
                  } catch (final java.net.MalformedURLException exception) {
                    throw new IllegalStateException(exception);
                  }
                })
            .toArray(URL[]::new);
    try (URLClassLoader isolated =
        new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader())) {
      final Class<?> secondBootstrap =
          Class.forName(ChaosAgentBootstrap.class.getName(), true, isolated);
      try {
        secondBootstrap.getMethod("installForLocalTests").invoke(null);
        System.out.println("secondRefused=false");
      } catch (final java.lang.reflect.InvocationTargetException refused) {
        System.out.println("secondRefused=true");
        System.out.println(
            "refusalMentionsClassLoader="
                + String.valueOf(refused.getCause().getMessage()).contains("another class loader"));
      }
    }
    final long start = System.nanoTime();
    Thread.sleep(1L);
    System.out.println(
        "firstCopySleepMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  /**
   * Activates a 64 MiB direct-buffer stressor, stops it, and prints the direct-pool usage before,
   * during and right after stop, without requesting a GC.
   */
  private static void directBufferRelease() {
    final java.lang.management.BufferPoolMXBean direct =
        ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
            .filter(pool -> "direct".equals(pool.getName()))
            .findFirst()
            .orElseThrow();
    final long before = direct.getMemoryUsed();
    final com.macstab.chaos.jvm.api.ChaosActivationHandle handle =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("direct")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
                    .effect(
                        new ChaosEffect.DirectBufferPressureEffect(
                            64L * 1024 * 1024, 1024 * 1024, false))
                    .activationPolicy(ActivationPolicy.always())
                    .build());
    final long during = direct.getMemoryUsed();
    handle.close();
    final long after = direct.getMemoryUsed();
    System.out.println("directDuringMiB=" + (during - before) / (1024 * 1024));
    System.out.println("directAfterMiB=" + (after - before) / (1024 * 1024));
  }

  /** Prints the hook groups the agent has woven so far. */
  private static void printWovenGroups() {
    System.out.println(
        "woven="
            + ChaosPlatform.current()
                .diagnostics()
                .snapshot()
                .runtimeDetails()
                .get("wovenHookGroups"));
  }

  /** Activates a ThreadLocal scenario at runtime and prints the woven groups before and after. */
  private static void wovenGroupsAfterThreadLocal() {
    System.out.println(
        "before="
            + ChaosPlatform.current()
                .diagnostics()
                .snapshot()
                .runtimeDetails()
                .get("wovenHookGroups"));
    ChaosPlatform.current()
        .activate(
            ChaosScenario.builder("thread-local")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(
                    new ChaosSelector.ThreadLocalSelector(
                        Set.of(OperationType.THREAD_LOCAL_GET), null))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());
    printWovenGroups();
  }

  /** Prints how many bridge jars are left in java.io.tmpdir while the agent is running. */
  private static void bridgeJarsOnDisk() throws Exception {
    try (java.nio.file.DirectoryStream<Path> jars =
        java.nio.file.Files.newDirectoryStream(
            Path.of(System.getProperty("java.io.tmpdir")), "macstab-chaos-bootstrap-bridge*")) {
      long count = 0;
      for (final Path ignored : jars) {
        count++;
      }
      System.out.println("bridgeJars=" + count);
    }
    // The bridge must still work after its file is gone.
    final long start = System.nanoTime();
    Thread.sleep(1L);
    System.out.println("sleepMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  /** Reads one byte from a loopback server; the read's peer is 127.0.0.1. */
  private static void socketReadLoopback() throws Exception {
    try (java.net.ServerSocket server =
            new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        java.net.Socket client = new java.net.Socket("127.0.0.1", server.getLocalPort());
        java.net.Socket accepted = server.accept()) {
      accepted.getOutputStream().write(42);
      accepted.getOutputStream().flush();
      System.out.println("byte=" + client.getInputStream().read());
    }
  }

  /** Prints how far now(ZoneId) is from the unskewed Instant.now(), in seconds. */
  private static void clockNowInZone() {
    final java.time.ZoneId tokyo = java.time.ZoneId.of("Asia/Tokyo");
    final java.time.Instant real = java.time.Instant.now();
    System.out.println(
        "localSkewSeconds="
            + Duration.between(
                    java.time.LocalDateTime.ofInstant(real, tokyo),
                    java.time.LocalDateTime.now(tokyo))
                .toSeconds());
    System.out.println(
        "zonedSkewSeconds="
            + Duration.between(real, java.time.ZonedDateTime.now(tokyo).toInstant()).toSeconds());
  }

  private static void virtualThreadExecutor() throws Exception {
    try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {}).get(5L, TimeUnit.SECONDS);
    }
  }

  /** Calls {@code Runtime.exit} under a SUPPRESS scenario, which must veto the exit. */
  private static void runtimeExit() {
    try {
      Runtime.getRuntime().exit(3);
      System.out.println("exitSuppressed=false");
    } catch (final SecurityException expected) {
      System.out.println("exitSuppressed=true");
    }
  }

  private static void selectConsumer() throws Exception {
    try (final Selector selector = Selector.open()) {
      selector.select(key -> {}, 5L);
    }
  }

  private static void sleepDuration() throws Exception {
    final long start = System.nanoTime();
    Thread.sleep(Duration.ofSeconds(2L));
    System.out.println("elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  /** Runs {@code client} against a local HTTP server that answers every request with 200 "ok". */
  static void withHttpServer(final HttpProbe client) throws Exception {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          final byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      client.call("http://127.0.0.1:" + server.getAddress().getPort() + "/probe");
    } catch (final ChaosHttpSuppressException suppressed) {
      System.out.println("suppressed=true");
    } finally {
      server.stop(0);
    }
  }

  @FunctionalInterface
  interface HttpProbe {
    void call(String url) throws Exception;
  }

  private static void jdkSendAsync(final String url) throws Exception {
    final HttpClient client = HttpClient.newHttpClient();
    System.out.println(
        "status="
            + client
                .sendAsync(
                    HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofString())
                .get(10L, TimeUnit.SECONDS)
                .statusCode());
  }

  /** A {@link Delayed} that is due immediately. */
  private static final class ImmediateDelayed implements Delayed {
    @Override
    public long getDelay(final TimeUnit unit) {
      return 0L;
    }

    @Override
    public int compareTo(final Delayed other) {
      return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
    }
  }

  static void printCounts() {
    final ChaosControlPlane controlPlane = ChaosPlatform.current();
    for (final ChaosDiagnostics.ScenarioReport report :
        controlPlane.diagnostics().snapshot().scenarios()) {
      System.out.println("matched." + report.id() + "=" + report.matchedCount());
      System.out.println("applied." + report.id() + "=" + report.appliedCount());
    }
  }
}

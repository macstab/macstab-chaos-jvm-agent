package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.NamePattern;
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
import java.nio.file.Files;
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
 * Probe main for {@link AgentEndToEndTest}. Each mode exercises one instrumented call path inside a
 * JVM started with {@code -javaagent:} and prints {@code key=value} lines that the test asserts on.
 * Every mode ends with {@link #printCounts()}, which prints each scenario's matched and applied
 * counters as {@code matched.<id>} / {@code applied.<id>}.
 */
public final class DefectProbeMain {

  /** The agent's logger, held while probes that check what the agent logs configure it. */
  private static final java.util.logging.Logger AGENT_LOGGER =
      java.util.logging.Logger.getLogger("com.macstab.chaos.jvm");

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
      case "kafka-isolated-loader" -> kafkaIsolatedLoader();
      case "child-first-loader" -> childFirstLoader();
      case "park-until-in-session" -> parkUntilInSession();
      case "jdbc-hikari-execute" -> jdbcHikariExecute();
      case "jdbc-prepared-execute" -> jdbcPreparedExecute();
      case "jdbc-statement-reject" -> jdbcStatementReject();
      case "virtual-thread-executor" -> virtualThreadExecutor();
      case "initialize-retry" -> initializeRetry();
      case "second-agent-copy" -> secondAgentCopy();
      case "clock-now-in-zone" -> clockNowInZone();
      case "clock-reads" -> clockReads();
      case "skewed-time-bounds" -> skewedTimeBounds();
      case "socket-read-loopback" -> socketReadLoopback();
      case "direct-buffer-release" -> directBufferRelease();
      case "bridge-jars-on-disk" -> bridgeJarsOnDisk();
      case "socket-write-sizes" -> socketWriteSizes();
      case "socket-read-effects" ->
          socketReadEffects(System.getProperty("chaos.test.soTimeout", "200"));
      case "socket-reset" -> socketReset();
      case "socket-connect-timeout" -> socketConnectTimeout();
      case "file-corrupt-read" -> fileCorruptRead();
      case "file-channel-write" -> fileChannelWrite();
      case "dns-lookups" -> dnsLookups();
      case "local-mode-session-delay" -> localModeSessionDelay();
      case "session-handoff" -> sessionHandoff();
      case "random-access-write" -> randomAccessWrite();
      case "file-writes-by-path" -> fileWritesByPath();
      case "file-ops" -> fileOps();
      case "process-start" -> processStart();
      case "clock-call-sites" -> clockCallSites();
      case "session-header" -> sessionHeader();
      case "pool-worker-session" -> poolWorkerSession();
      case "session-priority-pool" -> sessionPriorityPool();
      case "session-schedule-tick" -> sessionScheduleTick();
      case "socket-torn-write" -> socketTornWrite();
      case "socket-channel" -> socketChannel();
      case "file-open" -> fileOpen();
      case "hook-coverage" -> hookCoverage();
      case "close-shutdown-wait" -> closeShutdownAndWait();
      case "redirect" -> redirect();
      case "udp-dns" -> udpAndReverseDns();
      case "pool-queue-reject" -> poolQueueReject();
      case "queue-poll-suppress" -> queuePollSuppress();
      case "scheduled-offer-reject" -> scheduledOfferReject();
      case "file-map-transfer" -> fileMapAndTransfer();
      case "socket-bind" -> socketBind();
      case "thread-eagain" -> threadEagain();
      case "reject-types" -> rejectTypes();
      case "method-weaving" -> methodWeaving();
      case "method-weaving-jdk" -> methodWeavingJdk();
      case "method-kinds" -> methodKinds();
      case "woven-groups" -> printWovenGroups();
      case "woven-groups-after-thread-local" -> wovenGroupsAfterThreadLocal();
      case "virtual-thread-start" -> Thread.ofVirtual().start(() -> {}).join();
      case "runtime-exit" -> runtimeExit();
      case "select-consumer" -> selectConsumer();
      case "sleep-duration" -> sleepDuration();
      case "jdk-send-async" -> withHttpServer(DefectProbeMain::jdkSendAsync);
      case "jdk-send-async-reject" -> withHttpServer(DefectProbeMain::jdkSendAsyncReject);
      case "http-url-connection" -> withHttpServer(DefectProbeMain::httpUrlConnection);
      case "agent-owned-stressors" -> agentOwnedStressors();
      case "thread-local-leak-fork-join-gate" -> threadLocalLeakForkJoinGate();
      case "thread-local-leak-set-delay" -> threadLocalLeakSetDelay();
      case "session-thread-local-suppress" -> sessionThreadLocalSuppress();
      case "direct-buffer-above-limit" -> directBufferAboveLimit();
      case "completion-dependents" -> completionDependents();
      case "virtual-thread-resumes" -> virtualThreadResumes();
      case "parallel-stream" -> parallelStream();
      case "files-new-input-stream" -> filesNewInputStream();
      case "seekable-truncate" -> seekableTruncate();
      case "serialize-list" -> serializeList();
      case "fis-read-all-bytes" -> fisReadAllBytes();
      case "fis-read-n-bytes" -> fisReadNBytes();
      case "raf-read-line" -> rafReadLine();
      case "delayed-work-queue-put" -> delayedWorkQueuePut();
      case "dns-literal" -> dnsLiteral();
      case "dns-literal-answers" -> dnsLiteralAnswers();
      case "socket-double-close" -> socketDoubleClose();
      case "socket-adaptor" -> socketAdaptor();
      case "ssl-server-accept" -> sslServerAccept();
      case "adaptor-accept-timeout" -> adaptorAcceptTimeout();
      case "jdbc-batch" -> jdbcBatch();
      case "executor-submit-kinds" -> executorSubmitKinds();
      case "executor-submit-suppress" -> executorSubmitSuppress();
      case "session-tick-stpe-submit" -> sessionTickStpeSubmit();
      case "schedule-periodic-only" -> schedulePeriodicOnly();
      case "executor-scheduled-only" -> executorScheduledOnly();
      case "skewed-window-manual-read" -> skewedWindowManualRead();
      case "startup-plan-error-status" -> startupPlanErrorStatus();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    printCounts();
  }

  /**
   * Prints the remaining delay of a 1900 µs schedule right after submission, plus the time elapsed
   * since the submission started: their sum is the delay the executor was given, however long the
   * submission took on a loaded machine.
   */
  private static void scheduleSubMillisecondDelay() throws Exception {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    try {
      // Warm up the schedule path (class loading, dispatch) so the measured call is not dominated
      // by first-call overhead.
      executor.schedule(() -> {}, 0L, TimeUnit.MILLISECONDS).get(5L, TimeUnit.SECONDS);
      final long submitted = System.nanoTime();
      final ScheduledFuture<?> future = executor.schedule(() -> {}, 1900L, TimeUnit.MICROSECONDS);
      final long remaining = future.getDelay(TimeUnit.NANOSECONDS);
      System.out.println("scheduledDelayNanos=" + (remaining + System.nanoTime() - submitted));
      future.get(5L, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Runs a 300 µs fixed-rate schedule and prints its ticks and the milliseconds elapsed. A
   * fixed-rate executor catches up on runs it missed, so the ratio converges to the real rate
   * (about 3.3 per ms; at most 1 if the period were rewritten to 1 ms) even on a starved machine:
   * the probe waits, up to 5 s, until it has.
   */
  private static void scheduleSubMillisecondPeriod() throws Exception {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final AtomicLong ticks = new AtomicLong();
    try {
      final long start = System.nanoTime();
      executor.scheduleAtFixedRate(ticks::incrementAndGet, 0L, 300L, TimeUnit.MICROSECONDS);
      long elapsedMillis;
      long count;
      do {
        Thread.sleep(50L);
        count = ticks.get();
        elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      } while ((elapsedMillis < 300L || count < 2L * elapsedMillis) && elapsedMillis < 5_000L);
      System.out.println("ticks=" + count);
      System.out.println("elapsedMillis=" + elapsedMillis);
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

  /** The file {@code name} under {@code chaos.test.dir}, holding {@code content}. */
  private static java.nio.file.Path testFile(final String name, final byte[] content)
      throws Exception {
    final java.nio.file.Path file =
        java.nio.file.Path.of(System.getProperty("chaos.test.dir")).resolve(name);
    java.nio.file.Files.write(file, content);
    return file;
  }

  /**
   * {@code Files.newInputStream}: on JDK 24+ the provider's newByteChannel calls newFileChannel.
   */
  private static void filesNewInputStream() throws Exception {
    final java.nio.file.Path file =
        java.nio.file.Path.of(System.getProperty("chaos.test.dir")).resolve("open");
    // Created through File.createNewFile, which is not an open.
    file.toFile().createNewFile();
    try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
      System.out.println("read=" + in.read());
    }
  }

  /** A truncate through {@code SeekableByteChannel}, the type the bridge method returns. */
  private static void seekableTruncate() throws Exception {
    final java.nio.file.Path file = testFile("truncate", new byte[16]);
    try (java.nio.channels.SeekableByteChannel channel =
        java.nio.file.Files.newByteChannel(file, java.nio.file.StandardOpenOption.WRITE)) {
      channel.truncate(4L);
    }
    System.out.println("size=" + java.nio.file.Files.size(file));
  }

  /** One writeObject and one readObject of a list of 100 elements. */
  private static void serializeList() throws Exception {
    final java.util.List<Integer> list = new java.util.ArrayList<>();
    for (int i = 0; i < 100; i++) {
      list.add(i);
    }
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
      out.writeObject(list);
    }
    try (java.io.ObjectInputStream in =
        new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
      System.out.println("size=" + ((java.util.List<?>) in.readObject()).size());
    }
  }

  /** {@code FileInputStream.readAllBytes} of a file larger than one internal read. */
  private static void fisReadAllBytes() throws Exception {
    final java.nio.file.Path file = testFile("all", new byte[20_000]);
    try (java.io.FileInputStream in = new java.io.FileInputStream(file.toFile())) {
      System.out.println("length=" + in.readAllBytes().length);
    }
  }

  /** {@code FileInputStream.readNBytes(int)} of more than one internal read. */
  private static void fisReadNBytes() throws Exception {
    final java.nio.file.Path file = testFile("n", new byte[20_000]);
    try (java.io.FileInputStream in = new java.io.FileInputStream(file.toFile())) {
      System.out.println("length=" + in.readNBytes(15_000).length);
    }
  }

  /** {@code RandomAccessFile.readLine} of a ten-character line. */
  private static void rafReadLine() throws Exception {
    final java.nio.file.Path file =
        testFile("line", "0123456789\nrest".getBytes(StandardCharsets.ISO_8859_1));
    try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file.toFile(), "r")) {
      System.out.println("line=" + in.readLine());
    }
  }

  /**
   * One {@code put} into a {@code ScheduledThreadPoolExecutor}'s queue through the {@code
   * BlockingQueue} interface: the generic bridge {@code put(Object)} of {@code DelayedWorkQueue}
   * calls its {@code put(Runnable)}.
   */
  @SuppressWarnings("unchecked")
  private static void delayedWorkQueuePut() throws Exception {
    final java.util.concurrent.ScheduledThreadPoolExecutor executor =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    try {
      final ScheduledFuture<?> future = executor.schedule(() -> {}, 1L, TimeUnit.HOURS);
      final BlockingQueue<Object> queue =
          (BlockingQueue<Object>) (BlockingQueue<?>) executor.getQueue();
      queue.remove(future);
      queue.put(future);
      System.out.println("queueSize=" + queue.size());
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Resolves IP literals, which the JDK never looks up, and names it does look up; connects to a
   * loopback server by its literal. Prints each outcome: {@code ok} or the exception class.
   */
  private static void dnsLiteral() throws Exception {
    for (final String name :
        new String[] {
          "127.0.0.1", "::1", "[::1]", "localhost", "1.2.3.4.5", "999.1.1.1", "0x7f.0.0.1"
        }) {
      try {
        InetAddress.getByName(name);
        System.out.println("resolve." + name + "=ok");
      } catch (final java.net.UnknownHostException failed) {
        System.out.println("resolve." + name + "=" + failed.getClass().getName());
      }
    }
    try (java.net.ServerSocket server =
            new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        java.net.Socket client = new java.net.Socket("127.0.0.1", server.getLocalPort())) {
      System.out.println("connect=" + client.isConnected());
    }
  }

  /** Resolves only IP literals: no answer is looked up, so none is reshaped. */
  private static void dnsLiteralAnswers() throws Exception {
    for (final String name : new String[] {"127.0.0.1", "::1"}) {
      System.out.println(
          "answer." + name + "=" + InetAddress.getAllByName(name)[0].getHostAddress());
    }
  }

  /** Closes a connected socket twice: the second close closes nothing. */
  private static void socketDoubleClose() throws Exception {
    try (java.net.ServerSocket server =
        new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      final java.net.Socket client =
          new java.net.Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      client.close();
      client.close();
      System.out.println("closed=" + client.isClosed());
    }
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

  /** A {@code Statement.execute} on H2; prints the failure the woven advice raised, if any. */
  private static void jdbcStatementReject() throws Exception {
    try (final Connection connection = DriverManager.getConnection("jdbc:h2:mem:reject");
        final Statement statement = connection.createStatement()) {
      statement.execute("SELECT 1");
      System.out.println("failure=none");
    } catch (final java.sql.SQLException rejected) {
      System.out.println("failure=" + rejected.getClass().getName());
      System.out.println("sqlException=true");
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
    // A plan that does not parse fails first, without the parser's cause (it quotes the input).
    try {
      ChaosAgentBootstrap.initialize("configJson={\"scenarios\": [", instrumentation, Map.of());
      System.out.println("malformedFailed=false");
    } catch (final com.macstab.chaos.jvm.startup.ConfigLoadException malformed) {
      System.out.println("malformedFailed=" + (malformed.getCause() == null));
    }
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
      // The refused copy's uninstall must leave the first copy's delegate in place.
      final Object uninstalled =
          Class.forName(
                  com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.class.getName(),
                  true,
                  isolated)
              .getMethod("uninstall", java.lang.instrument.Instrumentation.class)
              .invoke(null, net.bytebuddy.agent.ByteBuddyAgent.install());
      System.out.println("secondUninstalled=" + uninstalled);
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

  /**
   * Method selectors that name a JDK class ({@code java.util.HashMap.get}) and an application class
   * ({@code com.example.chaostarget.UserMap.get}): only the application's method is woven.
   */
  private static void methodWeavingJdk() {
    final ChaosControlPlane plane = ChaosPlatform.current();
    final java.util.Map<String, String> map = new java.util.HashMap<>(Map.of("k", "v"));
    try (ChaosActivationHandle jdk =
            plane.activate(
                ChaosScenario.builder("jdk")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(
                        new ChaosSelector.MethodSelector(
                            Set.of(OperationType.METHOD_ENTER),
                            NamePattern.exact("java.util.HashMap"),
                            NamePattern.exact("get"),
                            null))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build());
        ChaosActivationHandle user =
            plane.activate(
                ChaosScenario.builder("user")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(
                        new ChaosSelector.MethodSelector(
                            Set.of(OperationType.METHOD_ENTER),
                            NamePattern.exact("com.example.chaostarget.UserMap"),
                            NamePattern.exact("get"),
                            null))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      System.out.println("map=" + map.get("k"));
      System.out.println("userMap=" + new com.example.chaostarget.UserMap().get());
      System.out.println("matched.jdk=" + jdk.matchedCount());
      System.out.println("matched.user=" + user.matchedCount());
    }
  }

  /**
   * Activates method scenarios at runtime against {@link com.example.chaostarget.Greeter}, calls
   * it, closes them and calls it again.
   */
  private static void methodWeaving() {
    final com.example.chaostarget.Greeter greeter = new com.example.chaostarget.Greeter();
    System.out.println("before=" + greeter.greet("a") + "|" + greeter.count());
    final ChaosControlPlane plane = ChaosPlatform.current();
    final ChaosActivationHandle inject =
        plane.activate(
            ChaosScenario.builder("inject-greet-one-arg")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(
                    new ChaosSelector.MethodSelector(
                        Set.of(OperationType.METHOD_ENTER),
                        NamePattern.exact("com.example.chaostarget.Greeter"),
                        NamePattern.exact("greet"),
                        NamePattern.prefix("(Ljava/lang/String;)")))
                .effect(
                    new ChaosEffect.ExceptionInjectionEffect(
                        "java.lang.IllegalStateException", "injected", false))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final ChaosActivationHandle corrupt =
        plane.activate(
            ChaosScenario.builder("zero-count")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(
                    new ChaosSelector.MethodSelector(
                        Set.of(OperationType.METHOD_EXIT),
                        NamePattern.prefix("com.example.chaostarget."),
                        NamePattern.exact("count"),
                        null))
                .effect(ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.ZERO))
                .activationPolicy(ActivationPolicy.always())
                .build());
    try {
      greeter.greet("a");
      System.out.println("during.greet=returned");
    } catch (final IllegalStateException injected) {
      System.out.println("during.greet=" + injected.getMessage());
    }
    System.out.println("during.overload=" + greeter.greet("a", 2));
    System.out.println("during.count=" + greeter.count());
    System.out.println("during.lateCount=" + new com.example.chaostarget.LateCounter().count());
    System.out.println(
        "during.woven=" + plane.diagnostics().snapshot().runtimeDetails().get("wovenHookGroups"));
    inject.close();
    corrupt.close();
    System.out.println("after=" + greeter.greet("a") + "|" + greeter.count());
    System.out.println(
        "after.woven=" + plane.diagnostics().snapshot().runtimeDetails().get("wovenHookGroups"));
  }

  /**
   * Activates a method scenario on every method of {@link com.example.chaostarget.Shape} and {@link
   * com.example.chaostarget.Square}, then compares two squares through {@link Comparable} (its
   * bridge method) and reads a label through a lambda. Only the methods with a body of their own
   * may be evaluated: not the bridge, not the lambda's synthetic body. Also prints the agent's FINE
   * report of the weaving.
   */
  private static void methodKinds() {
    AGENT_LOGGER.setLevel(java.util.logging.Level.FINE);
    AGENT_LOGGER.addHandler(
        new java.util.logging.Handler() {
          @Override
          public void publish(final java.util.logging.LogRecord logRecord) {
            final String message = String.valueOf(logRecord.getMessage());
            if (message.startsWith("chaos method hooks woven")) {
              System.out.println("wovenLog=" + message);
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        });
    try (ChaosActivationHandle ignored =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("shape-methods")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(
                        new ChaosSelector.MethodSelector(
                            Set.of(OperationType.METHOD_ENTER),
                            NamePattern.regex("com\\.example\\.chaostarget\\.(Shape|Square)"),
                            NamePattern.any(),
                            null))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      final Comparable<com.example.chaostarget.Shape> square =
          new com.example.chaostarget.Square(2);
      System.out.println(
          "compared=" + Integer.signum(square.compareTo(new com.example.chaostarget.Square(3))));
      System.out.println("label=" + new com.example.chaostarget.Square(2).label().get());
      printCounts();
    }
  }

  /**
   * Sends 64 known bytes over loopback and reads them with one read on a socket whose SO_TIMEOUT is
   * {@code timeoutMillis}; prints how many bytes the read returned, how many bits differ, and any
   * timeout.
   */
  private static void socketReadEffects(final String timeoutMillis) throws Exception {
    final byte[] sent = new byte[64];
    for (int i = 0; i < sent.length; i++) {
      sent[i] = (byte) i;
    }
    try (java.net.ServerSocket server =
            new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        java.net.Socket client = new java.net.Socket("127.0.0.1", server.getLocalPort());
        java.net.Socket accepted = server.accept()) {
      accepted.getOutputStream().write(sent);
      accepted.getOutputStream().flush();
      Thread.sleep(100L);
      client.setSoTimeout(Integer.parseInt(timeoutMillis));
      final byte[] received = new byte[64];
      final long start = System.nanoTime();
      try {
        final int read = client.getInputStream().read(received, 0, received.length);
        System.out.println("read=" + read);
        int differingBits = 0;
        for (int i = 0; i < read; i++) {
          differingBits += Integer.bitCount((received[i] ^ sent[i]) & 0xFF);
        }
        System.out.println("differingBits=" + differingBits);
      } catch (final java.net.SocketTimeoutException timedOut) {
        System.out.println("timeout=" + timedOut.getMessage());
        System.out.println("waitedMillis=" + (System.nanoTime() - start) / 1_000_000L);
      }
    }
  }

  /** Connects to a listening loopback port with a 200 ms connect timeout. */
  private static void socketConnectTimeout() throws Exception {
    try (java.net.ServerSocket server =
            new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        java.net.Socket client = new java.net.Socket()) {
      final long start = System.nanoTime();
      try {
        client.connect(new java.net.InetSocketAddress("127.0.0.1", server.getLocalPort()), 200);
        System.out.println("connected=" + client.isConnected());
      } catch (final java.net.SocketTimeoutException timedOut) {
        System.out.println("timeout=" + timedOut.getMessage());
        System.out.println("waitedMillis=" + (System.nanoTime() - start) / 1_000_000L);
      }
    }
  }

  /** Closes the accepted side of a loopback connection and reports what the client's read sees. */
  private static void socketReset() throws Exception {
    try (java.net.ServerSocket server =
            new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        java.net.Socket client = new java.net.Socket("127.0.0.1", server.getLocalPort())) {
      final java.net.Socket accepted = server.accept();
      accepted.close();
      client.setSoTimeout(2000);
      try {
        System.out.println("read=" + client.getInputStream().read());
      } catch (final java.net.SocketException reset) {
        System.out.println("clientSaw=" + reset.getMessage());
      }
    }
  }

  /**
   * Writes 100 bytes through a FileChannel: reports the first write's count, writes the rest,
   * forces the channel and reports the file's final size or the sync failure.
   */
  private static void fileChannelWrite() throws Exception {
    final java.nio.file.Path file = java.nio.file.Files.createTempFile("chaos-channel", ".bin");
    try (java.nio.channels.FileChannel channel =
        java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
      final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(100);
      System.out.println("firstWrite=" + channel.write(buffer));
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      try {
        channel.force(true);
        System.out.println("force=ok");
      } catch (final java.io.IOException failed) {
        System.out.println("force=" + failed.getClass().getName() + ": " + failed.getMessage());
      }
    }
    System.out.println("size=" + java.nio.file.Files.size(file));
    java.nio.file.Files.deleteIfExists(file);
  }

  /**
   * Inside a session whose method scenario injects an exception into Greeter.greet, calls it from a
   * ForkJoin task, a started thread, a virtual thread and a parallel stream; prints what each saw.
   */
  private static void sessionHandoff() throws Exception {
    final com.example.chaostarget.Greeter greeter = new com.example.chaostarget.Greeter();
    final java.util.function.Supplier<String> attempt =
        () -> {
          try {
            greeter.greet("x");
            return "ok";
          } catch (final IllegalStateException injected) {
            return "injected";
          }
        };
    try (ChaosSession session = ChaosPlatform.current().openSession("handoff")) {
      session.activate(
          ChaosScenario.builder("inject-in-session")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.METHOD_ENTER),
                      NamePattern.exact("com.example.chaostarget.Greeter"),
                      NamePattern.exact("greet"),
                      null))
              .effect(
                  new ChaosEffect.ExceptionInjectionEffect(
                      "java.lang.IllegalStateException", "injected", false))
              .activationPolicy(ActivationPolicy.always())
              .build());
      System.out.println("caller=" + attempt.get());
      System.out.println(
          "forkJoin=" + java.util.concurrent.CompletableFuture.supplyAsync(attempt).get());
      final java.util.concurrent.atomic.AtomicReference<String> seen =
          new java.util.concurrent.atomic.AtomicReference<>();
      final Thread thread = new Thread(() -> seen.set(attempt.get()));
      thread.start();
      thread.join();
      System.out.println("thread=" + seen.get());
      Thread.ofVirtual().start(() -> seen.set(attempt.get())).join();
      System.out.println("virtual=" + seen.get());
      System.out.println(
          "parallel="
              + java.util.stream.IntStream.range(0, 16)
                  .parallel()
                  .mapToObj(i -> attempt.get())
                  .distinct()
                  .sorted()
                  .collect(java.util.stream.Collectors.joining(",")));
    }
    final java.util.concurrent.atomic.AtomicReference<String> after =
        new java.util.concurrent.atomic.AtomicReference<>();
    final Thread outside = new Thread(() -> after.set(attempt.get()));
    outside.start();
    outside.join();
    System.out.println("outside=" + after.get());
  }

  /** Installs locally, delays EXECUTOR_SUBMIT in a session, and times one execute(). */
  private static void localModeSessionDelay() throws Exception {
    final com.macstab.chaos.jvm.api.ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
    try (com.macstab.chaos.jvm.api.ChaosSession session = controlPlane.openSession("local-mode")) {
      final ChaosActivationHandle handle =
          session.activate(
              ChaosScenario.builder("local-delay")
                  .scope(ChaosScenario.ScenarioScope.SESSION)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(60)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final java.util.concurrent.ThreadPoolExecutor executor =
          new java.util.concurrent.ThreadPoolExecutor(
              1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
      try {
        final long start = System.nanoTime();
        executor.execute(() -> {});
        System.out.println(
            "elapsedMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        System.out.println("applied.local-delay=" + handle.appliedCount());
      } finally {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Resolves an unresolvable name, localhost and dual.test (from a test's hosts file), reporting
   * answers or failures.
   */
  private static void dnsLookups() {
    for (final String host : new String[] {"chaos-probe.invalid", "localhost", "dual.test"}) {
      try {
        final StringBuilder addresses = new StringBuilder();
        for (final InetAddress address : InetAddress.getAllByName(host)) {
          addresses.append(addresses.length() == 0 ? "" : ",").append(address.getHostAddress());
        }
        System.out.println(host + "=" + addresses);
      } catch (final java.net.UnknownHostException unknown) {
        System.out.println(host + "=unknown");
      }
    }
  }

  /** Writes through a RandomAccessFile and reports the failure, if any. */
  private static void randomAccessWrite() throws Exception {
    final java.nio.file.Path file = java.nio.file.Files.createTempFile("chaos-raf", ".bin");
    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
      raf.write(new byte[] {1, 2, 3});
      System.out.println("raf=ok");
    } catch (final java.io.IOException failed) {
      System.out.println("raf.type=" + failed.getClass().getName());
      System.out.println("raf.message=" + failed.getMessage());
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  /**
   * Writes 64 known bytes to a temporary file and reads them back with read(byte[]), read() and a
   * FileChannel read.
   */
  private static void fileCorruptRead() throws Exception {
    final java.nio.file.Path file = java.nio.file.Files.createTempFile("chaos-corrupt", ".bin");
    try {
      final byte[] sent = new byte[64];
      for (int i = 0; i < sent.length; i++) {
        sent[i] = (byte) i;
      }
      java.nio.file.Files.write(file, sent);
      final byte[] received = new byte[64];
      try (java.io.FileInputStream in = new java.io.FileInputStream(file.toFile())) {
        final int read = in.read(received);
        int differingBits = 0;
        for (int i = 0; i < read; i++) {
          differingBits += Integer.bitCount((received[i] ^ sent[i]) & 0xFF);
        }
        System.out.println("read=" + read);
        System.out.println("differingBits=" + differingBits);
      }
      try (java.io.FileInputStream in = new java.io.FileInputStream(file.toFile())) {
        System.out.println(
            "singleByteDifferingBits=" + Integer.bitCount((in.read() ^ sent[0]) & 0xFF));
      }
      try (java.io.FileInputStream in = new java.io.FileInputStream(file.toFile())) {
        final byte[] all = in.readAllBytes();
        int differingBits = 0;
        for (int i = 0; i < all.length; i++) {
          differingBits += Integer.bitCount((all[i] ^ sent[i]) & 0xFF);
        }
        System.out.println("readAllBytesDifferingBits=" + differingBits);
      }
      try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file.toFile(), "r")) {
        final String line = in.readLine();
        int differingBits = 0;
        for (int i = 0; i < line.length(); i++) {
          differingBits += Integer.bitCount((line.charAt(i) ^ sent[i]) & 0xFF);
        }
        System.out.println("readLineDifferingBits=" + differingBits);
      }
      try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file)) {
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64);
        final int read = channel.read(buffer);
        int differingBits = 0;
        for (int i = 0; i < read; i++) {
          differingBits += Integer.bitCount((buffer.get(i) ^ sent[i]) & 0xFF);
        }
        System.out.println("channelRead=" + read);
        System.out.println("channelDifferingBits=" + differingBits);
      }
      try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file)) {
        // The JDK refuses a read into a read-only buffer: nothing is corrupted, nothing counts.
        final long before = appliedCount("effect");
        String outcome;
        try {
          outcome = "read " + channel.read(java.nio.ByteBuffer.allocate(8).asReadOnlyBuffer());
        } catch (final IllegalArgumentException refused) {
          outcome = refused.getClass().getSimpleName();
        }
        System.out.println("readOnlyChannelRead=" + outcome);
        System.out.println("readOnlyChannelApplied=" + (appliedCount("effect") - before));
      }
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  /** Writes 10 bytes and then 2000 bytes over a loopback connection. */
  private static void socketWriteSizes() throws Exception {
    try (java.net.ServerSocket server =
            new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        java.net.Socket client = new java.net.Socket("127.0.0.1", server.getLocalPort());
        java.net.Socket accepted = server.accept()) {
      client.getOutputStream().write(new byte[10]);
      client.getOutputStream().write(new byte[2000]);
      client.getOutputStream().flush();
      System.out.println("received=" + accepted.getInputStream().readNBytes(2010).length);
    }
  }

  /** Writes a file in directory "a" and one in directory "b" under -Dchaos.test.dir. */
  private static void fileWritesByPath() throws Exception {
    final java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("chaos.test.dir"));
    for (final String directory : new String[] {"a", "b"}) {
      final java.nio.file.Path dir = java.nio.file.Files.createDirectories(root.resolve(directory));
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(dir.resolve("f").toFile())) {
        out.write(new byte[] {1, 2, 3});
        System.out.println(directory + "=ok");
      } catch (final java.io.IOException rejected) {
        System.out.println(directory + "=rejected");
        System.out.println(directory + ".type=" + rejected.getClass().getName());
        System.out.println(directory + ".message=" + rejected.getMessage());
      }
    }
  }

  /**
   * In directories a and b of chaos.test.dir: truncates through a FileChannel and a
   * RandomAccessFile, moves with Files.move and File.renameTo, deletes with Files.delete and
   * File.delete; prints each outcome ("ok", "true"/"false" or the exception class).
   */
  private static void fileOps() throws Exception {
    final java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("chaos.test.dir"));
    for (final String directory : new String[] {"a", "b"}) {
      final java.nio.file.Path dir = java.nio.file.Files.createDirectories(root.resolve(directory));
      java.nio.file.Path current = dir.resolve("f");
      java.nio.file.Files.write(current, new byte[10]);
      try (java.nio.channels.FileChannel channel =
          java.nio.channels.FileChannel.open(current, java.nio.file.StandardOpenOption.WRITE)) {
        channel.truncate(5);
        System.out.println(directory + ".truncate=ok");
      } catch (final java.io.IOException failed) {
        System.out.println(directory + ".truncate=" + failed.getClass().getName());
      }
      try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(current.toFile(), "rw")) {
        file.setLength(3);
        System.out.println(directory + ".setLength=ok");
      } catch (final java.io.IOException failed) {
        System.out.println(directory + ".setLength=" + failed.getClass().getName());
      }
      try {
        current = java.nio.file.Files.move(current, dir.resolve("g"));
        System.out.println(directory + ".move=ok");
      } catch (final java.io.IOException failed) {
        System.out.println(directory + ".move=" + failed.getClass().getName());
      }
      final java.io.File renamed = dir.resolve("h").toFile();
      final boolean renameResult = current.toFile().renameTo(renamed);
      System.out.println(directory + ".renameTo=" + renameResult);
      if (renameResult) {
        current = renamed.toPath();
      }
      try {
        java.nio.file.Files.delete(current);
        System.out.println(directory + ".delete=ok");
      } catch (final java.io.IOException failed) {
        System.out.println(directory + ".delete=" + failed.getClass().getName());
      }
      final java.io.File other = dir.resolve("k").toFile();
      java.nio.file.Files.write(other.toPath(), new byte[1]);
      System.out.println(directory + ".fileDelete=" + other.delete());
      System.out.println(directory + ".exists=" + java.nio.file.Files.exists(current));
      try {
        System.out.println(
            directory
                + ".deleteIfExists="
                + java.nio.file.Files.deleteIfExists(dir.resolve("absent")));
      } catch (final java.io.IOException failed) {
        System.out.println(directory + ".deleteIfExists=" + failed.getClass().getName());
      }
    }
  }

  /**
   * Writes 1000 bytes through a socket stream and 1000 through a SocketChannel to a loopback server
   * that counts what it receives; prints the stream write's failure, the channel's first write
   * count and the bytes the server received from the stream.
   */
  private static void socketTornWrite() throws Exception {
    try (java.net.ServerSocket server =
        new java.net.ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
      final String[] peerEnd = {"eof"};
      final java.util.concurrent.CompletableFuture<Long> received =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try (java.net.Socket accepted = server.accept()) {
                  final byte[] buffer = new byte[4096];
                  long total = 0;
                  try {
                    for (int n; (n = accepted.getInputStream().read(buffer)) > 0; ) {
                      total += n;
                    }
                  } catch (final java.io.IOException reset) {
                    // the writer's connection ended; what arrived is counted
                    peerEnd[0] = "reset";
                  }
                  return total;
                } catch (final java.io.IOException failed) {
                  return -1L;
                }
              });
      try (java.net.Socket client = new java.net.Socket("127.0.0.1", server.getLocalPort())) {
        try {
          client.getOutputStream().write(new byte[1000]);
          System.out.println("streamWrite=ok");
        } catch (final java.net.SocketException torn) {
          System.out.println("streamWrite=" + torn.getMessage());
        }
        try {
          client.getOutputStream().write(1);
          System.out.println("afterTorn=ok");
        } catch (final java.io.IOException closed) {
          System.out.println("afterTorn=" + closed.getMessage());
        }
      }
      System.out.println("received=" + received.get(5, TimeUnit.SECONDS));
      System.out.println("peerEnd=" + peerEnd[0]);
      final java.util.concurrent.CompletableFuture<java.net.Socket> sink =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return server.accept();
                } catch (final java.io.IOException failed) {
                  return null;
                }
              });
      try (java.nio.channels.SocketChannel channel =
          java.nio.channels.SocketChannel.open(
              new java.net.InetSocketAddress("127.0.0.1", server.getLocalPort()))) {
        System.out.println(
            "channelFirstWrite=" + channel.write(java.nio.ByteBuffer.allocate(1000)));
      }
      final java.net.Socket accepted = sink.get(5, TimeUnit.SECONDS);
      if (accepted != null) {
        accepted.close();
      }
    }
  }

  /**
   * Opens a file under directories {@code a} and {@code b} of {@code chaos.test.dir} in every way
   * the JDK offers; prints "ok" or the failure's class and message for each.
   */
  private static void fileOpen() throws Exception {
    final java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("chaos.test.dir"));
    for (final String directory : new String[] {"a", "b"}) {
      final java.nio.file.Path file =
          java.nio.file.Files.createDirectories(root.resolve(directory)).resolve("f");
      // Created through File.createNewFile, which is not an open.
      file.toFile().createNewFile();
      final Map<String, java.util.concurrent.Callable<java.io.Closeable>> opens =
          new java.util.LinkedHashMap<>();
      opens.put("fileInputStream", () -> new java.io.FileInputStream(file.toFile()));
      opens.put("fileOutputStream", () -> new java.io.FileOutputStream(file.toFile(), true));
      opens.put("randomAccessFile", () -> new java.io.RandomAccessFile(file.toFile(), "r"));
      opens.put("newInputStream", () -> java.nio.file.Files.newInputStream(file));
      opens.put("newBufferedReader", () -> java.nio.file.Files.newBufferedReader(file));
      opens.put("fileChannel", () -> java.nio.channels.FileChannel.open(file));
      for (final Map.Entry<String, java.util.concurrent.Callable<java.io.Closeable>> open :
          opens.entrySet()) {
        try (java.io.Closeable opened = open.getValue().call()) {
          System.out.println(directory + "." + open.getKey() + "=ok");
        } catch (final java.io.IOException failed) {
          System.out.println(
              directory
                  + "."
                  + open.getKey()
                  + "="
                  + failed.getClass().getName()
                  + ": "
                  + failed.getMessage());
        }
      }
    }
  }

  /**
   * Under {@code chaos.test.dir}: maps a file in {@code map}, forces a mapping in {@code plain},
   * transfers into a file in {@code torn} and out of one in {@code corrupt}, and copies into {@code
   * denied}. Prints what each call returned or threw.
   */
  private static void fileMapAndTransfer() throws Exception {
    final java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("chaos.test.dir"));
    final byte[] pattern = new byte[1024];
    for (int i = 0; i < pattern.length; i++) {
      pattern[i] = (byte) i;
    }
    final java.util.function.Function<String, java.nio.file.Path> file =
        directory -> {
          try {
            final java.nio.file.Path created =
                java.nio.file.Files.createDirectories(root.resolve(directory)).resolve("f");
            java.nio.file.Files.write(created, pattern);
            return created;
          } catch (final java.io.IOException failed) {
            throw new java.io.UncheckedIOException(failed);
          }
        };
    final java.nio.file.Path mapped = file.apply("map");
    try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(mapped)) {
      channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, pattern.length);
      System.out.println("map=ok");
    } catch (final java.io.IOException failed) {
      System.out.println(
          "map=" + failed.getMessage() + " / " + failed.getCause().getClass().getName());
    }
    final java.nio.file.Path plain = file.apply("plain");
    try (java.nio.channels.FileChannel channel =
        java.nio.channels.FileChannel.open(
            plain, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
      final java.nio.MappedByteBuffer buffer =
          channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, pattern.length);
      buffer.force();
      System.out.println("force=ok");
    } catch (final java.io.UncheckedIOException failed) {
      System.out.println("force=" + failed.getCause().getClass().getName());
    }
    // Created empty: a write of the pattern would itself be torn.
    final java.nio.file.Path torn =
        java.nio.file.Files.createDirectories(root.resolve("torn")).resolve("f");
    torn.toFile().createNewFile();
    try (java.nio.channels.FileChannel out =
            java.nio.channels.FileChannel.open(torn, java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.ReadableByteChannel in =
            java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(pattern))) {
      System.out.println("transferFrom=" + out.transferFrom(in, 0, pattern.length));
    }
    final java.nio.file.Path corrupt = file.apply("corrupt");
    final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(corrupt)) {
      System.out.println(
          "transferTo="
              + channel.transferTo(0, pattern.length, java.nio.channels.Channels.newChannel(sink)));
    }
    System.out.println("transferIntact=" + Arrays.equals(sink.toByteArray(), pattern));
    java.nio.file.Files.createDirectories(root.resolve("denied"));
    try {
      java.nio.file.Files.copy(plain, root.resolve("denied").resolve("copy"));
      System.out.println("copy=ok");
    } catch (final java.io.IOException failed) {
      System.out.println("copy=" + failed.getClass().getName() + ": " + failed.getMessage());
    }
  }

  /**
   * UDP over loopback and a reverse lookup, each under a scenario activated here for the receiver's
   * port: a dropped datagram, corrupted receives, a refused send and a failed reverse lookup.
   * Prints what the application saw.
   */
  private static void udpAndReverseDns() throws Exception {
    final ChaosControlPlane plane = ChaosPlatform.current();
    final byte[] pattern = new byte[64];
    for (int i = 0; i < pattern.length; i++) {
      pattern[i] = (byte) i;
    }
    try (java.net.DatagramSocket receiver =
            new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        java.net.DatagramSocket sender = new java.net.DatagramSocket()) {
      receiver.setSoTimeout(2000);
      final InetSocketAddress to = new InetSocketAddress("127.0.0.1", receiver.getLocalPort());
      final java.util.function.Function<OperationType, ChaosSelector> toReceiver =
          operation ->
              new ChaosSelector.NetworkSelector(
                  Set.of(operation), NamePattern.any(), "127.0.0.1", to.getPort(), null, null);

      final ChaosActivationHandle drop =
          plane.activate(
              ChaosScenario.builder("udp-drop-first")
                  .selector(toReceiver.apply(OperationType.DATAGRAM_SEND))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.count(1))
                  .build());
      sender.send(new java.net.DatagramPacket("one".getBytes(StandardCharsets.UTF_8), 3, to));
      sender.send(new java.net.DatagramPacket("two".getBytes(StandardCharsets.UTF_8), 3, to));
      final java.net.DatagramPacket first = new java.net.DatagramPacket(new byte[16], 16);
      receiver.receive(first);
      System.out.println(
          "udpFirst=" + new String(first.getData(), 0, first.getLength(), StandardCharsets.UTF_8));
      printApplied(drop);

      // One DatagramSocket.send is one DATAGRAM_SEND, although the JDK sends it through the
      // channel's own send(ByteBuffer, SocketAddress).
      final ChaosActivationHandle sendOnce =
          plane.activate(
              ChaosScenario.builder("udp-send-once")
                  .selector(toReceiver.apply(OperationType.DATAGRAM_SEND))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .build());
      sender.send(new java.net.DatagramPacket("once".getBytes(StandardCharsets.UTF_8), 4, to));
      receiver.receive(new java.net.DatagramPacket(new byte[16], 16));
      printApplied(sendOnce);

      final ChaosActivationHandle corrupt =
          plane.activate(
              ChaosScenario.builder("udp-corrupt")
                  .selector(ChaosSelector.network(Set.of(OperationType.DATAGRAM_RECEIVE)))
                  .effect(ChaosEffect.corruptRead())
                  .build());
      sender.send(new java.net.DatagramPacket(pattern, pattern.length, to));
      final java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[128], 128);
      receiver.receive(packet);
      System.out.println("udpSocketBits=" + flippedBits(packet.getData(), pattern));
      try (java.nio.channels.DatagramChannel channel =
          java.nio.channels.DatagramChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
        sender.send(
            new java.net.DatagramPacket(pattern, pattern.length, channel.getLocalAddress()));
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(128);
        channel.receive(buffer);
        System.out.println("udpChannelBits=" + flippedBits(buffer.array(), pattern));
      }
      printApplied(corrupt);

      final ChaosActivationHandle refused =
          plane.activate(
              ChaosScenario.builder("udp-refused")
                  .selector(toReceiver.apply(OperationType.DATAGRAM_SEND))
                  .effect(ChaosEffect.errno(ChaosEffect.Errno.ECONNREFUSED))
                  .build());
      try {
        sender.send(new java.net.DatagramPacket(pattern, pattern.length, to));
        System.out.println("udpRefused=sent");
      } catch (final java.io.IOException failed) {
        System.out.println("udpRefused=" + failed.getClass().getName());
      }
      printApplied(refused);
    }

    final InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    final ChaosActivationHandle reverse =
        plane.activate(
            ChaosScenario.builder("reverse-fails")
                .selector(
                    ChaosSelector.dns(
                        Set.of(OperationType.DNS_REVERSE), NamePattern.exact("127.0.0.1")))
                .effect(ChaosEffect.reject("no PTR"))
                .build());
    System.out.println("reverseName=" + loopback.getCanonicalHostName());
    printApplied(reverse);
  }

  /** Counts the bits in which {@code received} differs from {@code expected}. */
  private static int flippedBits(final byte[] received, final byte[] expected) {
    int flipped = 0;
    for (int i = 0; i < expected.length; i++) {
      flipped += Integer.bitCount((received[i] ^ expected[i]) & 0xFF);
    }
    return flipped;
  }

  /**
   * Connects a socket and a socket channel to a port nothing listens on, under a scenario that
   * redirects that port to a live server; prints what each connect reached.
   */
  private static void redirect() throws Exception {
    final int deadPort;
    try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
      deadPort = probe.getLocalPort();
    }
    try (java.net.ServerSocket server =
        new java.net.ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
      final ChaosActivationHandle redirect =
          ChaosPlatform.current()
              .activate(
                  ChaosScenario.builder("failover")
                      .selector(
                          new ChaosSelector.NetworkSelector(
                              Set.of(OperationType.SOCKET_CONNECT),
                              NamePattern.any(),
                              "127.0.0.1",
                              deadPort,
                              null,
                              null))
                      .effect(ChaosEffect.redirect("127.0.0.1", server.getLocalPort()))
                      .build());
      try (java.net.Socket socket = new java.net.Socket("127.0.0.1", deadPort);
          java.net.Socket accepted = server.accept()) {
        System.out.println("socketPeer=" + (socket.getPort() == server.getLocalPort()));
      }
      try (java.nio.channels.SocketChannel channel =
              java.nio.channels.SocketChannel.open(new InetSocketAddress("127.0.0.1", deadPort));
          java.net.Socket accepted = server.accept()) {
        System.out.println(
            "channelPeer="
                + (((InetSocketAddress) channel.getRemoteAddress()).getPort()
                    == server.getLocalPort()));
      }
      printApplied(redirect);
    }
  }

  /**
   * File closes and moves under {@code chaos.test.dir}, socket half-closes, a timed select and a
   * timed process wait, each under a scenario activated here. Prints what the application saw.
   */
  private static void closeShutdownAndWait() throws Exception {
    final ChaosControlPlane plane = ChaosPlatform.current();
    final java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("chaos.test.dir"));
    final java.nio.file.Path failing = java.nio.file.Files.createDirectories(root.resolve("nfs"));
    final java.nio.file.Path locked = java.nio.file.Files.createDirectories(root.resolve("locked"));
    final java.nio.file.Path plain = java.nio.file.Files.createDirectories(root.resolve("plain"));
    final ChaosActivationHandle closeEio =
        plane.activate(
            ChaosScenario.builder("close-eio")
                .selector(
                    new ChaosSelector.FileIoSelector(
                        Set.of(OperationType.FILE_IO_CLOSE),
                        com.macstab.chaos.jvm.api.PathPattern.under(failing.toString()),
                        null,
                        null))
                .effect(ChaosEffect.errno(ChaosEffect.Errno.EIO))
                .build());
    final java.io.FileOutputStream stream =
        new java.io.FileOutputStream(failing.resolve("a").toFile());
    try {
      stream.close();
      System.out.println("streamClose=ok");
    } catch (final java.io.IOException failed) {
      System.out.println("streamClose=" + failed.getClass().getName() + ": " + failed.getMessage());
    }
    // the descriptor is released all the same
    System.out.println("streamFdValid=" + stream.getFD().valid());
    final java.nio.channels.FileChannel channel =
        java.nio.channels.FileChannel.open(
            failing.resolve("b"),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE);
    try {
      channel.close();
      System.out.println("channelClose=ok");
    } catch (final java.io.IOException failed) {
      System.out.println("channelClose=" + failed.getClass().getName());
    }
    System.out.println("channelOpen=" + channel.isOpen());
    try (java.io.FileOutputStream other =
        new java.io.FileOutputStream(plain.resolve("c").toFile())) {
      other.getChannel().write(java.nio.ByteBuffer.allocate(1));
    }
    System.out.println("plainClose=ok");
    printApplied(closeEio);

    final ChaosActivationHandle moveDenied =
        plane.activate(
            ChaosScenario.builder("move-into-locked")
                .selector(
                    new ChaosSelector.FileIoSelector(
                        Set.of(OperationType.FILE_IO_MOVE_TARGET),
                        com.macstab.chaos.jvm.api.PathPattern.under(locked.toString()),
                        null,
                        null))
                .effect(ChaosEffect.errno(ChaosEffect.Errno.EACCES))
                .build());
    try {
      java.nio.file.Files.move(plain.resolve("c"), locked.resolve("c"));
      System.out.println("moveInto=ok");
    } catch (final java.io.IOException failed) {
      System.out.println("moveInto=" + failed.getClass().getName());
    }
    System.out.println(
        "renameInto=" + plain.resolve("c").toFile().renameTo(locked.resolve("d").toFile()));
    System.out.println(
        "renameElsewhere=" + plain.resolve("c").toFile().renameTo(plain.resolve("e").toFile()));
    printApplied(moveDenied);

    try (java.net.ServerSocket server =
        new java.net.ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
      final ChaosActivationHandle notConnected =
          plane.activate(
              ChaosScenario.builder("shutdown-enotconn")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_SHUTDOWN),
                          NamePattern.any(),
                          "127.0.0.1",
                          server.getLocalPort(),
                          null,
                          null))
                  .effect(ChaosEffect.errno(ChaosEffect.Errno.ENOTCONN))
                  .build());
      try (java.net.Socket socket = new java.net.Socket("127.0.0.1", server.getLocalPort());
          java.net.Socket accepted = server.accept()) {
        socket.shutdownOutput();
        System.out.println("socketShutdown=ok");
      } catch (final java.net.SocketException failed) {
        System.out.println("socketShutdown=" + failed.getMessage());
      }
      try (java.nio.channels.SocketChannel socketChannel =
              java.nio.channels.SocketChannel.open(
                  new InetSocketAddress("127.0.0.1", server.getLocalPort()));
          java.net.Socket accepted = server.accept()) {
        socketChannel.shutdownOutput();
        System.out.println("channelShutdown=ok");
      } catch (final java.net.SocketException failed) {
        System.out.println("channelShutdown=" + failed.getMessage());
      }
      printApplied(notConnected);
    }

    final ChaosActivationHandle stalled =
        plane.activate(
            ChaosScenario.builder("select-stalls")
                .selector(
                    new ChaosSelector.NioSelector(
                        Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any()))
                .effect(ChaosEffect.timeout())
                .build());
    final java.nio.channels.Pipe pipe = java.nio.channels.Pipe.open();
    try (Selector selector = Selector.open()) {
      pipe.source().configureBlocking(false);
      pipe.source().register(selector, java.nio.channels.SelectionKey.OP_READ);
      pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[] {1}));
      final long start = System.nanoTime();
      final int ready = selector.select(300);
      System.out.println("selectReady=" + ready);
      System.out.println(
          "selectMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      System.out.println("selectNowReady=" + selector.selectNow());
    } finally {
      pipe.sink().close();
      pipe.source().close();
    }
    printApplied(stalled);

    final ChaosActivationHandle hangs =
        plane.activate(
            ChaosScenario.builder("child-hangs")
                .selector(
                    new ChaosSelector.ProcessSelector(
                        Set.of(OperationType.PROCESS_WAIT), NamePattern.exact("java")))
                .effect(ChaosEffect.timeout())
                .build());
    final Process child =
        new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-version")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    final long start = System.nanoTime();
    System.out.println("waitExited=" + child.waitFor(300, TimeUnit.MILLISECONDS));
    System.out.println("waitMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    printApplied(hangs);
    System.out.println("exitCode=" + child.waitFor());
  }

  /**
   * With every hook group woven ({@code instrumentation=eager}), loads every JDK class a hook
   * targets — by name, or by creating the platform's implementation of a JDK type — and prints the
   * hooks that matched a method and those that matched none.
   */
  private static void hookCoverage() throws Exception {
    final String[] targets = {
      "java.lang.ProcessBuilder",
      "java.lang.ProcessImpl",
      "java.lang.VirtualThread",
      "java.nio.Bits",
      "java.nio.MappedByteBuffer",
      "java.util.concurrent.CompletableFuture$Delayer",
      "java.util.concurrent.CompletableFuture$DelayedCompleter",
      "java.util.concurrent.CompletableFuture$Timeout",
      "sun.nio.ch.Util",
      "java.time.Clock$SystemClock",
      "java.time.Clock$SystemInstantSource",
      "sun.nio.ch.DatagramChannelImpl",
      "sun.nio.ch.FileChannelImpl",
      "sun.nio.ch.Net",
      "sun.nio.ch.NioSocketImpl",
      "sun.nio.ch.ServerSocketChannelImpl",
      "sun.nio.ch.SocketChannelImpl",
      "sun.net.www.protocol.http.HttpURLConnection",
      "jdk.internal.net.http.HttpClientImpl",
      "java.util.concurrent.ArrayBlockingQueue",
      "java.util.concurrent.LinkedBlockingQueue",
      "java.util.concurrent.LinkedBlockingDeque",
      "java.util.concurrent.PriorityBlockingQueue",
      "java.util.concurrent.DelayQueue",
      "java.util.concurrent.SynchronousQueue",
      "java.util.concurrent.LinkedTransferQueue",
      "javax.naming.InitialContext",
      // client libraries, on the classpath of the coverage run
      "com.zaxxer.hikari.pool.HikariPool",
      "com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool",
      "io.grpc.internal.ManagedChannelImpl$ChannelStreamProvider",
      "io.lettuce.core.RedisChannelHandler",
      "io.netty.channel.nio.AbstractNioByteChannel",
      "io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe",
      "io.netty.channel.epoll.AbstractEpollStreamChannel$EpollStreamUnsafe",
      "io.netty.channel.kqueue.AbstractKQueueStreamChannel$KQueueStreamUnsafe",
      "io.r2dbc.h2.H2Statement",
      "okhttp3.internal.connection.RealCall",
      "org.apache.hc.client5.http.impl.classic.CloseableHttpClient",
      "org.apache.hc.client5.http.impl.classic.InternalHttpClient",
      "org.apache.http.impl.client.CloseableHttpClient",
      "org.apache.http.impl.client.InternalHttpClient",
      "org.apache.kafka.clients.consumer.KafkaConsumer",
      "org.apache.kafka.clients.producer.KafkaProducer",
      "org.apache.tomcat.util.threads.ThreadPoolExecutor",
      "reactor.netty.http.client.HttpClientConnect$MonoHttpConnect",
      "redis.clients.jedis.Connection",
      "redis.clients.jedis.ConnectionFactory",
      "redis.clients.jedis.JedisFactory",
      "software.amazon.awssdk.core.internal.handler.BaseAsyncClientHandler",
      "software.amazon.awssdk.core.internal.handler.BaseSyncClientHandler",
    };
    for (final String target : targets) {
      try {
        Class.forName(target, false, ClassLoader.getSystemClassLoader());
      } catch (final ClassNotFoundException absent) {
        System.out.println("absent." + target + "=true");
      }
    }
    // Implementations chosen by the platform: create one of each.
    Selector.open().close();
    javax.net.ssl.SSLContext.getDefault().createSSLEngine();
    javax.net.ssl.SSLSocketFactory.getDefault().createSocket().close();
    ManagementFactory.getPlatformMBeanServer();
    new java.util.zip.Inflater().end();
    new java.util.zip.Deflater().end();
    new java.util.concurrent.ScheduledThreadPoolExecutor(1).shutdownNow();
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:coverage");
        Statement statement = connection.createStatement();
        PreparedStatement prepared = connection.prepareStatement("select 1")) {
      statement.execute("select 1");
      prepared.execute();
    }
    System.out.println("runtime=" + Runtime.version());
    System.out.println(
        "matchedHooks="
            + String.join(
                "|",
                com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.matchedHooks()));
    System.out.println(
        "unmatchedHooks="
            + ChaosPlatform.current()
                .diagnostics()
                .snapshot()
                .runtimeDetails()
                .get("unmatchedHooks"));
    System.out.println(
        "hookFailures="
            + ChaosPlatform.current()
                .diagnostics()
                .snapshot()
                .runtimeDetails()
                .get("hookFailures"));
  }

  /**
   * Binds a server socket and a server socket channel to 127.0.0.1 and to ::1, then accepts on a
   * ::1 server; prints "ok" or the failure for each.
   */
  private static void socketBind() throws Exception {
    for (final String host : new String[] {"127.0.0.1", "::1"}) {
      try (java.net.ServerSocket server = new java.net.ServerSocket()) {
        server.bind(new InetSocketAddress(host, 0));
        System.out.println("serverSocket." + host + "=ok");
      } catch (final java.io.IOException failed) {
        System.out.println(
            "serverSocket."
                + host
                + "="
                + failed.getClass().getName()
                + ": "
                + failed.getMessage());
      }
      try (java.nio.channels.ServerSocketChannel channel =
          java.nio.channels.ServerSocketChannel.open()) {
        channel.bind(new InetSocketAddress(host, 0));
        System.out.println("serverChannel." + host + "=ok");
      } catch (final java.io.IOException failed) {
        System.out.println(
            "serverChannel."
                + host
                + "="
                + failed.getClass().getName()
                + ": "
                + failed.getMessage());
      }
    }
    try (java.net.ServerSocket server =
        new java.net.ServerSocket(0, 1, InetAddress.getByName("::1"))) {
      // No client connects: the EMFILE scenario must fail the accept. Bounded, so a fault that
      // did not apply reports a timeout instead of hanging the probe.
      server.setSoTimeout(5_000);
      server.accept().close();
      System.out.println("accept=ok");
    } catch (final java.io.IOException failed) {
      System.out.println("accept=" + failed.getClass().getName() + ": " + failed.getMessage());
    }
  }

  /**
   * One real call per row under a reject (or an errno), each scenario active only around its call.
   * Prints {@code <row>.type} (the failure's class) and {@code <row>.top} (its top frame as {@code
   * class.method}), and for the reflective call {@code <row>.cause}.
   */
  private static void rejectTypes() throws Exception {
    final Path dir = java.nio.file.Files.createTempDirectory("reject-types");
    final ChaosEffect reject = ChaosEffect.reject("chaos");
    rejectRow(
        "dnsAnswer",
        ChaosSelector.dns(Set.of(OperationType.DNS_ANSWER), NamePattern.exact("localhost")),
        reject,
        () -> InetAddress.getAllByName("localhost"));
    final java.lang.reflect.Method target = Object.class.getMethod("hashCode");
    rejectRow(
        "reflection",
        ChaosSelector.jvmRuntime(Set.of(OperationType.REFLECTION_INVOKE)),
        reject,
        () -> target.invoke(new Object()));
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    rejectRow(
        "scheduleSubmit",
        ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)),
        reject,
        () -> scheduler.schedule(() -> {}, 1, TimeUnit.SECONDS));
    scheduler.shutdownNow();
    rejectRow(
        "awaitTermination",
        ChaosSelector.shutdown(Set.of(OperationType.EXECUTOR_AWAIT_TERMINATION)),
        reject,
        () -> scheduler.awaitTermination(1, TimeUnit.MILLISECONDS));
    final BlockingQueue<String> queue = new java.util.concurrent.ArrayBlockingQueue<>(1);
    rejectRow(
        "queuePut",
        ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)),
        reject,
        () -> queue.put("item"));
    rejectRow(
        "threadStart",
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.THREAD_START),
            ChaosSelector.ThreadKind.PLATFORM,
            NamePattern.exact("probe-reject"),
            null),
        reject,
        () -> new Thread(() -> {}, "probe-reject").start());
    rejectRow(
        "shutdownHook",
        ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
        reject,
        () -> Runtime.getRuntime().addShutdownHook(new Thread(() -> {})));
    try (Selector selector = Selector.open()) {
      rejectRow(
          "select",
          ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT)),
          reject,
          () -> selector.select(10));
    }
    final Path opened = dir.resolve("opened");
    rejectRow(
        "nioOpen",
        new ChaosSelector.FileIoSelector(
            Set.of(OperationType.FILE_IO_OPEN),
            com.macstab.chaos.jvm.api.PathPattern.under(dir.toString()),
            null,
            null),
        reject,
        () ->
            java.nio.channels.FileChannel.open(
                    opened,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE)
                .close());
    try (java.nio.channels.FileChannel channel =
        java.nio.channels.FileChannel.open(
            dir.resolve("written"),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE)) {
      rejectRow(
          "channelWriteEnospc",
          new ChaosSelector.FileIoSelector(
              Set.of(OperationType.FILE_IO_WRITE),
              com.macstab.chaos.jvm.api.PathPattern.under(dir.toString()),
              null,
              null),
          ChaosEffect.errno(ChaosEffect.Errno.ENOSPC),
          () -> channel.write(java.nio.ByteBuffer.allocate(8)));
    }
    try (java.net.ServerSocket server =
            new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        java.net.Socket client = new java.net.Socket()) {
      client.connect(server.getLocalSocketAddress());
      try (java.net.Socket accepted = server.accept()) {
        final ChaosSelector.NetworkSelector toServer =
            new ChaosSelector.NetworkSelector(
                Set.of(OperationType.SOCKET_READ, OperationType.SOCKET_WRITE),
                NamePattern.any(),
                "127.0.0.1/32",
                server.getLocalPort(),
                null,
                null);
        rejectRow("socketRead", toServer, reject, () -> client.getInputStream().read());
        rejectRow("socketWrite", toServer, reject, () -> client.getOutputStream().write(1));
        accepted.getOutputStream().flush();
      }
    }
    // Last: class loading from the agent's JAR inflates too, so every class the dispatch needs
    // is loaded by the rows above before inflation is rejected.
    final java.util.zip.Inflater inflater = new java.util.zip.Inflater();
    inflater.setInput(new byte[] {0x78, (byte) 0x9c, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
    rejectRow(
        "zipInflate",
        ChaosSelector.jvmRuntime(Set.of(OperationType.ZIP_INFLATE)),
        reject,
        () -> inflater.inflate(new byte[8]));
    inflater.end();
  }

  @FunctionalInterface
  interface Call {
    void run() throws Exception;
  }

  private static void rejectRow(
      final String row, final ChaosSelector selector, final ChaosEffect effect, final Call call) {
    Throwable failure = null;
    try (ChaosActivationHandle handle =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder(row)
                    .selector(selector)
                    .effect(effect)
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      try {
        call.run();
      } catch (final Throwable thrown) {
        failure = thrown;
      }
    }
    if (failure == null) {
      System.out.println(row + ".type=none");
      return;
    }
    System.out.println(row + ".type=" + failure.getClass().getName());
    final StackTraceElement[] stack = failure.getStackTrace();
    System.out.println(
        row
            + ".top="
            + (stack.length == 0
                ? "none"
                : stack[0].getClassName() + "." + stack[0].getMethodName()));
    if (failure.getCause() != null) {
      System.out.println(row + ".cause=" + failure.getCause().getClass().getName());
    }
  }

  /** Starts one platform thread under an EAGAIN scenario, then one after it is closed. */
  private static void threadEagain() throws Exception {
    final ChaosActivationHandle eagain =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("thread-eagain")
                    .selector(
                        new ChaosSelector.ThreadSelector(
                            Set.of(OperationType.THREAD_START),
                            ChaosSelector.ThreadKind.PLATFORM,
                            NamePattern.exact("probe-worker"),
                            null))
                    .effect(ChaosEffect.errno(ChaosEffect.Errno.EAGAIN))
                    .build());
    try {
      new Thread(() -> {}, "probe-worker").start();
      System.out.println("during=started");
    } catch (final OutOfMemoryError failed) {
      System.out.println("during=" + failed.getMessage());
    }
    eagain.close();
    final Thread after = new Thread(() -> {}, "probe-worker");
    after.start();
    after.join();
    System.out.println("after=started");
  }

  /**
   * Socket channels as sockets, filtered on the server's port so only the client's side matches.
   * Phase one trickles the client's reads and resets its close; phase two corrupts its reads and
   * tears its writes, single and gathered. Prints what the client and the server saw.
   */
  private static void socketChannel() throws Exception {
    final byte[] payload = new byte[1000];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) i;
    }
    final ChaosControlPlane plane = ChaosPlatform.current();
    try (java.net.ServerSocket server =
        new java.net.ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
      final int port = server.getLocalPort();
      final java.util.function.Function<String, ChaosSelector> clientSide =
          operation ->
              new ChaosSelector.NetworkSelector(
                  Set.of(OperationType.valueOf(operation)),
                  NamePattern.any(),
                  "127.0.0.1",
                  port,
                  null,
                  null);

      final ChaosActivationHandle trickle =
          plane.activate(
              ChaosScenario.builder("channel-trickle")
                  .selector(clientSide.apply("SOCKET_READ"))
                  .effect(ChaosEffect.trickle(16))
                  .build());
      final ChaosActivationHandle reset =
          plane.activate(
              ChaosScenario.builder("channel-reset")
                  .selector(clientSide.apply("SOCKET_CLOSE"))
                  .effect(ChaosEffect.connectionReset())
                  .build());
      final java.util.concurrent.CompletableFuture<String> firstPeer = serve(server, payload);
      try (java.nio.channels.SocketChannel channel =
          java.nio.channels.SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(payload.length);
        System.out.println("trickledRead=" + channel.read(buffer));
      }
      System.out.println("closePeer=" + firstPeer.get(5, TimeUnit.SECONDS));
      printApplied(trickle, reset);

      final ChaosActivationHandle corrupt =
          plane.activate(
              ChaosScenario.builder("channel-corrupt")
                  .selector(clientSide.apply("SOCKET_READ"))
                  .effect(ChaosEffect.corruptRead())
                  .build());
      final ChaosActivationHandle torn =
          plane.activate(
              ChaosScenario.builder("channel-torn")
                  .selector(clientSide.apply("SOCKET_WRITE"))
                  .effect(ChaosEffect.tornWrite())
                  .build());
      final java.util.concurrent.CompletableFuture<String> secondPeer = serve(server, payload);
      try (java.nio.channels.SocketChannel channel =
          java.nio.channels.SocketChannel.open(new InetSocketAddress("127.0.0.1", port))) {
        final java.nio.ByteBuffer first = java.nio.ByteBuffer.allocate(400);
        final java.nio.ByteBuffer second = java.nio.ByteBuffer.allocate(payload.length - 400);
        int reads = 0;
        while (second.hasRemaining()
            && channel.read(new java.nio.ByteBuffer[] {first, second}, 0, 2) > 0) {
          reads++;
        }
        int flipped = 0;
        for (int i = 0; i < payload.length; i++) {
          final byte got = i < 400 ? first.get(i) : second.get(i - 400);
          flipped += Integer.bitCount((got ^ payload[i]) & 0xFF);
        }
        System.out.println("scatterReads=" + reads);
        System.out.println("bitsFlipped=" + flipped);
        System.out.println("tornWrite=" + channel.write(java.nio.ByteBuffer.allocate(600)));
        System.out.println(
            "tornGather="
                + channel.write(
                    new java.nio.ByteBuffer[] {
                      java.nio.ByteBuffer.allocate(600), java.nio.ByteBuffer.allocate(600)
                    }));
      }
      System.out.println("secondPeer=" + secondPeer.get(5, TimeUnit.SECONDS));
      printApplied(corrupt, torn);
    }
    unixSocketChannel();
  }

  /**
   * A Unix-domain socket channel: its peer is the socket's path, which a {@code NetworkSelector}
   * host pattern matches. Trickles the client's read of the server's 100 bytes to 4.
   */
  private static void unixSocketChannel() throws Exception {
    final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("chaos-unix");
    final java.nio.file.Path path = dir.resolve("app.sock");
    final ChaosActivationHandle trickle =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("unix-trickle")
                    .selector(
                        ChaosSelector.network(
                            Set.of(OperationType.SOCKET_READ), NamePattern.exact(path.toString())))
                    .effect(ChaosEffect.trickle(4))
                    .build());
    try (java.nio.channels.ServerSocketChannel server =
        java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
      server.bind(java.net.UnixDomainSocketAddress.of(path));
      try (java.nio.channels.SocketChannel client =
              java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(path));
          java.nio.channels.SocketChannel accepted = server.accept()) {
        accepted.write(java.nio.ByteBuffer.allocate(100));
        System.out.println("unixRead=" + client.read(java.nio.ByteBuffer.allocate(100)));
      }
    } finally {
      printApplied(trickle);
      java.nio.file.Files.deleteIfExists(path);
      java.nio.file.Files.deleteIfExists(dir);
    }
  }

  /** The work queue of the pools in these probes, so a selector matches only it. */
  static final class PoolWorkQueue extends java.util.concurrent.LinkedBlockingQueue<Runnable> {
    private static final long serialVersionUID = 1L;
  }

  /**
   * A thread pool's own work queue: a reject on QUEUE_TAKE or QUEUE_POLL is refused at activation;
   * a delay on QUEUE_TAKE applies to the worker's take without killing it, and the task runs.
   */
  private static void poolQueueReject() throws Exception {
    final ChaosControlPlane plane = ChaosPlatform.current();
    for (final OperationType operation :
        java.util.List.of(OperationType.QUEUE_TAKE, OperationType.QUEUE_POLL)) {
      final String name = operation.name().toLowerCase(java.util.Locale.ROOT);
      try {
        plane.activate(
            ChaosScenario.builder("pool-reject-" + name)
                .selector(
                    new ChaosSelector.QueueSelector(
                        Set.of(operation), NamePattern.exact(PoolWorkQueue.class.getName())))
                .effect(ChaosEffect.reject("queue refused"))
                .build());
        System.out.println(name + ".reject=activated");
      } catch (final RuntimeException refused) {
        System.out.println(name + ".reject=" + refused.getClass().getSimpleName());
      }
    }
    final ChaosActivationHandle delay =
        plane.activate(
            ChaosScenario.builder("pool-slow-take")
                .selector(
                    new ChaosSelector.QueueSelector(
                        Set.of(OperationType.QUEUE_TAKE),
                        NamePattern.exact(PoolWorkQueue.class.getName())))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .build());
    final java.util.concurrent.atomic.AtomicInteger created =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.ThreadPoolExecutor pool =
        new java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new PoolWorkQueue(),
            task -> {
              created.incrementAndGet();
              final Thread thread = new Thread(task);
              thread.setDaemon(true);
              return thread;
            });
    final CountDownLatch ran = new CountDownLatch(2);
    pool.execute(ran::countDown);
    pool.execute(ran::countDown);
    System.out.println("delay.ran=" + ran.await(2, TimeUnit.SECONDS));
    System.out.println("delay.threadsCreated=" + created.get());
    System.out.println("delay.applied=" + (delay.appliedCount() > 0));
    delay.close();
    pool.shutdownNow();
  }

  /**
   * A reject on QUEUE_OFFER while a scheduled pool, whose delay queue has no capacity limit, runs a
   * one-shot and a fixed-rate task: prints the one-shot's result (or {@code timeout}) and whether
   * the fixed-rate task ticked three times.
   */
  private static void scheduledOfferReject() throws Exception {
    final ChaosActivationHandle reject =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("offer-reject")
                    .selector(ChaosSelector.queue(Set.of(OperationType.QUEUE_OFFER)))
                    .effect(ChaosEffect.reject("full"))
                    .build());
    final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    try {
      final ScheduledFuture<Integer> oneShot =
          executor.schedule(() -> 42, 10L, TimeUnit.MILLISECONDS);
      try {
        System.out.println("scheduledResult=" + oneShot.get(5L, TimeUnit.SECONDS));
      } catch (final java.util.concurrent.TimeoutException timedOut) {
        System.out.println("scheduledResult=timeout");
      }
      final CountDownLatch ticks = new CountDownLatch(3);
      executor.scheduleAtFixedRate(ticks::countDown, 0L, 10L, TimeUnit.MILLISECONDS);
      System.out.println("fixedRateTicked=" + ticks.await(5L, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      printApplied(reject);
    }
  }

  /**
   * SUPPRESS on QUEUE_POLL: how long a timed poll takes to return null, and what a thread pool
   * whose workers poll with a keep-alive does while the scenario is active.
   */
  private static void queuePollSuppress() throws Exception {
    final ChaosControlPlane plane = ChaosPlatform.current();
    final ChaosActivationHandle handle =
        plane.activate(
            ChaosScenario.builder("poll-suppress")
                .selector(
                    new ChaosSelector.QueueSelector(
                        Set.of(OperationType.QUEUE_POLL),
                        NamePattern.exact(PoolWorkQueue.class.getName())))
                .effect(ChaosEffect.suppress())
                .build());
    final PoolWorkQueue direct = new PoolWorkQueue();
    final long start = System.nanoTime();
    final Runnable polled = direct.poll(200, TimeUnit.MILLISECONDS);
    System.out.println("timedPollNull=" + (polled == null));
    System.out.println(
        "timedPollMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    Thread.currentThread().interrupt();
    try {
      direct.poll(1, TimeUnit.SECONDS);
      System.out.println("interruptedPoll=returned");
    } catch (final InterruptedException interrupted) {
      System.out.println("interruptedPoll=InterruptedException");
    }
    final java.util.concurrent.atomic.AtomicInteger created =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.ThreadPoolExecutor pool =
        new java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            50,
            TimeUnit.MILLISECONDS,
            new PoolWorkQueue(),
            task -> {
              created.incrementAndGet();
              final Thread thread = new Thread(task);
              thread.setDaemon(true);
              return thread;
            });
    pool.allowCoreThreadTimeOut(true);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch ran = new CountDownLatch(1);
    final long before = handle.appliedCount();
    // the first task holds the only worker, so the second one waits in the queue
    pool.execute(
        () -> {
          try {
            release.await();
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    pool.execute(ran::countDown);
    release.countDown();
    System.out.println("poolRanWhileActive=" + ran.await(300, TimeUnit.MILLISECONDS));
    System.out.println("poolPollsIn300ms=" + (handle.appliedCount() - before));
    System.out.println("poolThreadsCreated=" + created.get());
    handle.close();
    pool.shutdownNow();
  }

  /** Prints each handle's applied count, then closes it. */
  private static void printApplied(final ChaosActivationHandle... handles) {
    for (final ChaosActivationHandle handle : handles) {
      System.out.println("applied." + handle.id() + "=" + handle.appliedCount());
      handle.close();
    }
  }

  /**
   * Accepts one connection, sends {@code payload}, then reads until the client goes away; completes
   * with {@code "reset"} or {@code "eof"}.
   */
  private static java.util.concurrent.CompletableFuture<String> serve(
      final java.net.ServerSocket server, final byte[] payload) {
    return java.util.concurrent.CompletableFuture.supplyAsync(
        () -> {
          try (java.net.Socket accepted = server.accept()) {
            accepted.getOutputStream().write(payload);
            accepted.getOutputStream().flush();
            final byte[] buffer = new byte[4096];
            try {
              while (accepted.getInputStream().read(buffer) > 0) {
                // drain what the client writes
              }
              return "eof";
            } catch (final java.io.IOException reset) {
              return "reset";
            }
          } catch (final java.io.IOException failed) {
            return "failed: " + failed.getMessage();
          }
        });
  }

  /**
   * Starts this JVM's java launcher with ProcessBuilder and with Runtime.exec, and a missing
   * program; prints "ok" or the failure message of each.
   */
  private static void processStart() throws Exception {
    final String launcher =
        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
    try {
      System.out.println(
          "builder="
              + (new ProcessBuilder(launcher, "-version").start().waitFor() == 0 ? "ok" : "exit"));
    } catch (final java.io.IOException failed) {
      System.out.println("builder=" + failed.getMessage());
    }
    try {
      System.out.println(
          "exec="
              + (Runtime.getRuntime().exec(new String[] {launcher, "-version"}).waitFor() == 0
                  ? "ok"
                  : "exit"));
    } catch (final java.io.IOException failed) {
      System.out.println("exec=" + failed.getMessage());
    }
    try {
      new ProcessBuilder("definitely-not-a-program-xyz").start();
      System.out.println("other=started");
    } catch (final java.io.IOException failed) {
      System.out.println("other=" + failed.getMessage());
    }
  }

  /**
   * Prints how far the clocks read by ClockReader (a selected package) are from those read here
   * (not selected), in seconds; read after JIT compilation of ClockReader too.
   */
  private static void clockCallSites() {
    for (int i = 0; i < 20_000; i++) {
      com.example.chaostarget.ClockReader.millis();
      com.example.chaostarget.ClockReader.nanos();
    }
    System.out.println(
        "millisSkewSeconds="
            + Math.round(
                (com.example.chaostarget.ClockReader.millis() - System.currentTimeMillis())
                    / 1000.0));
    System.out.println(
        "nanosSkewSeconds="
            + Math.round((com.example.chaostarget.ClockReader.nanos() - System.nanoTime()) / 1e9));
  }

  /**
   * A loopback server that joins the session named by the request's X-Chaos-Session header and
   * calls Greeter.greet, which a session-scoped scenario fails. Requests are sent through the JDK
   * HttpClient and HttpURLConnection inside the session, then once more after it closed; prints the
   * header each request carried and what the server saw.
   */
  private static void sessionHeader() throws Exception {
    final com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          final String header = exchange.getRequestHeaders().getFirst(ChaosSession.HEADER);
          String outcome;
          try (ChaosSession.ScopeBinding joined = ChaosPlatform.current().bindSession(header)) {
            new com.example.chaostarget.Greeter().greet("x");
            outcome = "ok";
          } catch (final IllegalStateException injected) {
            outcome = "injected";
          }
          final byte[] body = (header + "|" + outcome).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    try {
      final String sessionId;
      try (ChaosSession session = ChaosPlatform.current().openSession("header")) {
        sessionId = session.id();
        session.activate(
            ChaosScenario.builder("inject-via-header")
                .scope(ChaosScenario.ScenarioScope.SESSION)
                .selector(
                    new ChaosSelector.MethodSelector(
                        Set.of(OperationType.METHOD_ENTER),
                        NamePattern.exact("com.example.chaostarget.Greeter"),
                        NamePattern.exact("greet"),
                        null))
                .effect(
                    new ChaosEffect.ExceptionInjectionEffect(
                        "java.lang.IllegalStateException", "injected", false))
                .activationPolicy(ActivationPolicy.always())
                .build());
        System.out.println("httpClient=" + viaHttpClient(url, sessionId));
        System.out.println("urlConnection=" + viaUrlConnection(url, sessionId));
      }
      System.out.println("afterClose=" + viaHttpClient(url, sessionId));
    } finally {
      server.stop(0);
    }
  }

  /** Returns the server's answer with the session id replaced by "session". */
  private static String viaHttpClient(final String url, final String sessionId) throws Exception {
    try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
      final String body =
          client
              .send(
                  java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString())
              .body();
      return body.replace(sessionId, "session");
    }
  }

  private static String viaUrlConnection(final String url, final String sessionId)
      throws Exception {
    final java.net.HttpURLConnection connection =
        (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
    try (java.io.InputStream in = connection.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(sessionId, "session");
    } finally {
      connection.disconnect();
    }
  }

  /** A Comparable task of a priority pool. */
  private record PrioritizedTask(int priority, Runnable body)
      implements Runnable, Comparable<PrioritizedTask> {

    @Override
    public void run() {
      body.run();
    }

    @Override
    public int compareTo(final PrioritizedTask other) {
      return Integer.compare(priority, other.priority);
    }
  }

  /**
   * A pool on a PriorityBlockingQueue used from inside a session without scenarios: the first task
   * holds the only worker, so the second is queued and must be Comparable. Prints {@code
   * queued=ran} or the failure.
   */
  private static void sessionPriorityPool() throws Exception {
    final java.util.concurrent.ThreadPoolExecutor pool =
        new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>());
    final CountDownLatch holding = new CountDownLatch(1);
    final CountDownLatch ran = new CountDownLatch(1);
    try (ChaosSession session = ChaosPlatform.current().openSession("priority")) {
      pool.execute(
          new PrioritizedTask(
              0,
              () -> {
                try {
                  holding.await();
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              }));
      try {
        pool.execute(new PrioritizedTask(1, ran::countDown));
        holding.countDown();
        System.out.println("queued=" + (ran.await(5L, TimeUnit.SECONDS) ? "ran" : "timeout"));
      } catch (final RuntimeException failed) {
        System.out.println("queued=" + failed.getClass().getName());
      }
    } finally {
      holding.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * A session-scoped SCHEDULE_TICK delay and a fixed-rate task scheduled in the session: prints how
   * many times the task ran and how many ticks the scenario was applied to.
   */
  private static void sessionScheduleTick() throws Exception {
    final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    final AtomicLong runs = new AtomicLong();
    final CountDownLatch fiveRuns = new CountDownLatch(5);
    try (ChaosSession session = ChaosPlatform.current().openSession("tick")) {
      final ChaosActivationHandle tick =
          session.activate(
              ChaosScenario.builder("session-tick")
                  .scope(ChaosScenario.ScenarioScope.SESSION)
                  .selector(ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_TICK)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final ScheduledFuture<?> periodic =
          executor.scheduleAtFixedRate(
              () -> {
                runs.incrementAndGet();
                fiveRuns.countDown();
              },
              0L,
              10L,
              TimeUnit.MILLISECONDS);
      System.out.println("fiveRuns=" + fiveRuns.await(5L, TimeUnit.SECONDS));
      periodic.cancel(false);
      executor.shutdown();
      executor.awaitTermination(5L, TimeUnit.SECONDS);
      System.out.println("runs=" + runs.get());
      System.out.println("ticks=" + tick.appliedCount());
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * A pool whose worker is created by a submission from inside a session: the task submitted in the
   * session runs in it, a later task submitted from outside any session must not.
   */
  private static void poolWorkerSession() throws Exception {
    final com.example.chaostarget.Greeter greeter = new com.example.chaostarget.Greeter();
    final java.util.concurrent.Callable<String> attempt =
        () -> {
          try {
            greeter.greet("x");
            return "ok";
          } catch (final IllegalStateException injected) {
            return "injected";
          }
        };
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      final java.util.concurrent.atomic.AtomicReference<String> later =
          new java.util.concurrent.atomic.AtomicReference<>();
      try (ChaosSession session = ChaosPlatform.current().openSession("pool")) {
        session.activate(
            ChaosScenario.builder("inject-in-pool-session")
                .scope(ChaosScenario.ScenarioScope.SESSION)
                .selector(
                    new ChaosSelector.MethodSelector(
                        Set.of(OperationType.METHOD_ENTER),
                        NamePattern.exact("com.example.chaostarget.Greeter"),
                        NamePattern.exact("greet"),
                        null))
                .effect(
                    new ChaosEffect.ExceptionInjectionEffect(
                        "java.lang.IllegalStateException", "injected", false))
                .activationPolicy(ActivationPolicy.always())
                .build());
        System.out.println("inSession=" + pool.submit(attempt).get(5, TimeUnit.SECONDS));
        // A thread outside the session submits to the same worker while the session is open.
        final Thread outsider =
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      try {
                        later.set(pool.submit(attempt).get(5, TimeUnit.SECONDS));
                      } catch (final Exception failed) {
                        later.set(failed.toString());
                      }
                    });
        try (ChaosSession.ScopeBinding none = ChaosPlatform.current().bindSession(null)) {
          outsider.start();
        }
        outsider.join();
      }
      System.out.println("outsideSession=" + later.get());
    } finally {
      pool.shutdownNow();
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

  /**
   * Prints how far each system-clock read is ahead of the real time, in seconds: the system clock's
   * instant and millis, the system instant source, the java.time now() methods without an operation
   * of their own, and Instant.now / LocalDateTime.now.
   */
  private static void clockReads() {
    final long real =
        java.time.Instant.ofEpochMilli(java.lang.System.currentTimeMillis()).getEpochSecond();
    final java.time.ZoneOffset utc = java.time.ZoneOffset.UTC;
    final Map<String, Long> reads = new java.util.LinkedHashMap<>();
    reads.put("clockInstant", java.time.Clock.systemUTC().instant().getEpochSecond());
    reads.put("clockMillis", java.time.Clock.systemDefaultZone().millis() / 1000L);
    reads.put("instantSource", java.time.InstantSource.system().instant().getEpochSecond());
    reads.put("offsetDateTime", java.time.OffsetDateTime.now(utc).toEpochSecond());
    reads.put("localTime", (long) java.time.LocalTime.now(utc).toSecondOfDay());
    reads.put("instantNow", java.time.Instant.now().getEpochSecond());
    reads.put("localDateTime", java.time.LocalDateTime.now(utc).toEpochSecond(utc));
    for (final Map.Entry<String, Long> read : reads.entrySet()) {
      final long ahead =
          read.getKey().equals("localTime")
              ? Math.floorMod(read.getValue() - Math.floorMod(real, 86_400L), 86_400L)
              : read.getValue() - real;
      System.out.println(read.getKey() + "=" + ahead);
    }
  }

  /** Reads the skewed clock, then submits one task to an executor. */
  private static void skewedTimeBounds() throws Exception {
    final long real =
        java.time.Instant.ofEpochMilli(java.lang.System.currentTimeMillis()).getEpochSecond();
    System.out.println(
        "clockAhead=" + (java.time.Clock.systemUTC().instant().getEpochSecond() - real));
    // An event published on this (skewed) thread outside any dispatch carries the real time.
    final java.util.concurrent.atomic.AtomicLong registeredAt =
        new java.util.concurrent.atomic.AtomicLong(-1L);
    final ChaosControlPlane plane = ChaosPlatform.current();
    plane.addEventListener(
        event -> {
          if (event.type() == com.macstab.chaos.jvm.api.ChaosEvent.Type.REGISTERED) {
            registeredAt.set(event.timestamp().getEpochSecond());
          }
        });
    plane
        .activate(
            ChaosScenario.builder("event-time")
                .selector(ChaosSelector.process(NamePattern.exact("never-started")))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .build())
        .close();
    System.out.println("eventAhead=" + (registeredAt.get() - real));
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(() -> {}).get(5L, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
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
    } finally {
      server.stop(0);
    }
  }

  @FunctionalInterface
  interface HttpProbe {
    void call(String url) throws Exception;
  }

  /** Reads a response through java.net.HttpURLConnection; a suppressed call is reported. */
  private static void httpUrlConnection(final String url) throws Exception {
    final java.net.HttpURLConnection connection =
        (java.net.HttpURLConnection) URI.create(url).toURL().openConnection();
    try {
      // Drives connect() through getResponseCode() and then reads the body: one request.
      System.out.println("status=" + connection.getResponseCode());
      System.out.println(
          "body="
              + new String(
                  connection.getInputStream().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
    } catch (final java.io.IOException rejected) {
      System.out.println(
          "ioException=" + rejected.getClass().getName() + ": " + rejected.getMessage());
    } finally {
      connection.disconnect();
    }
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

  /** A rejected {@code sendAsync}: whether the call threw, and how its future ended. */
  private static void jdkSendAsyncReject(final String url) throws Exception {
    final HttpClient client = HttpClient.newHttpClient();
    final java.util.concurrent.CompletableFuture<HttpResponse<String>> response;
    try {
      response =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create(url)).build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (final Exception failure) {
      System.out.println("call.type=" + failure.getClass().getName());
      return;
    }
    try {
      System.out.println("status=" + response.get(10L, TimeUnit.SECONDS).statusCode());
    } catch (final java.util.concurrent.ExecutionException failure) {
      System.out.println("future.type=" + failure.getCause().getClass().getName());
      System.out.println("future.message=" + failure.getCause().getMessage());
    }
  }

  /**
   * Activates every thread-owning stressor while a JVM-wide THREAD_START reject and a 2 s
   * THREAD_SLEEP delay are active, and prints whether each activation succeeded ({@code
   * activated.<id>=ACTIVE} or the exception), how long the deadlock took to form and how many
   * collections the safepoint storm caused in one second. The probe itself never sleeps: it parks.
   */
  private static void agentOwnedStressors() {
    final ChaosControlPlane plane = ChaosPlatform.current();
    final java.util.List<ChaosActivationHandle> handles = new java.util.ArrayList<>();
    handles.add(
        plane.activate(
            ChaosScenario.builder("reject-thread-start")
                .selector(
                    new ChaosSelector.ThreadSelector(
                        Set.of(OperationType.THREAD_START),
                        ChaosSelector.ThreadKind.ANY,
                        NamePattern.any(),
                        null))
                .effect(ChaosEffect.reject("no threads"))
                .activationPolicy(ActivationPolicy.always())
                .build()));
    handles.add(
        plane.activate(
            ChaosScenario.builder("slow-sleep")
                .selector(
                    new ChaosSelector.ThreadSelector(
                        Set.of(OperationType.THREAD_SLEEP),
                        ChaosSelector.ThreadKind.ANY,
                        NamePattern.any(),
                        null))
                .effect(ChaosEffect.delay(Duration.ofSeconds(2)))
                .activationPolicy(ActivationPolicy.always())
                .build()));
    final Map<String, ChaosEffect> stressors = new java.util.LinkedHashMap<>();
    stressors.put(
        "keepAlive",
        new ChaosEffect.KeepAliveEffect("probe-keepalive", true, Duration.ofMillis(10)));
    stressors.put("threadLeak", new ChaosEffect.ThreadLeakEffect(2, "probe-leak-", true, null));
    stressors.put("deadlock", new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(10)));
    stressors.put(
        "monitorContention",
        new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(1), 2, false));
    stressors.put(
        "gcPressure",
        new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ofMinutes(1)));
    stressors.put(
        "referenceQueueFlood",
        new ChaosEffect.ReferenceQueueFloodEffect(10, Duration.ofMillis(10)));
    stressors.put("safepointStorm", new ChaosEffect.SafepointStormEffect(Duration.ofMillis(20), 0));
    stressors.put("codeCachePressure", new ChaosEffect.CodeCachePressureEffect(1, 1));
    stressors.put(
        "carrierPinning",
        new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMillis(10)));
    final Map<String, ChaosSelector.StressTarget> targets =
        Map.of(
            "keepAlive", ChaosSelector.StressTarget.KEEPALIVE,
            "threadLeak", ChaosSelector.StressTarget.THREAD_LEAK,
            "deadlock", ChaosSelector.StressTarget.DEADLOCK,
            "monitorContention", ChaosSelector.StressTarget.MONITOR_CONTENTION,
            "gcPressure", ChaosSelector.StressTarget.GC_PRESSURE,
            "referenceQueueFlood", ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD,
            "safepointStorm", ChaosSelector.StressTarget.SAFEPOINT_STORM,
            "codeCachePressure", ChaosSelector.StressTarget.CODE_CACHE_PRESSURE,
            "carrierPinning", ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING);
    try {
      for (final Map.Entry<String, ChaosEffect> stressor : stressors.entrySet()) {
        try {
          handles.add(
              plane.activate(
                  ChaosScenario.builder(stressor.getKey())
                      .selector(ChaosSelector.stress(targets.get(stressor.getKey())))
                      .effect(stressor.getValue())
                      .activationPolicy(ActivationPolicy.withDestructiveEffects())
                      .build()));
          System.out.println("activated." + stressor.getKey() + "=" + state(stressor.getKey()));
        } catch (final RuntimeException | Error failed) {
          System.out.println(
              "activated." + stressor.getKey() + "=" + failed.getClass().getSimpleName());
        }
      }
      final long deadlockStart = System.nanoTime();
      while (ManagementFactory.getThreadMXBean().findDeadlockedThreads() == null
          && System.nanoTime() - deadlockStart < TimeUnit.SECONDS.toNanos(5)) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
      }
      System.out.println(
          "deadlockMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - deadlockStart));
      final long collectionsBefore = collections();
      LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
      System.out.println("collectionsPerSecond=" + (collections() - collectionsBefore));
    } finally {
      for (final ChaosActivationHandle handle : handles) {
        handle.close();
      }
    }
  }

  private static String state(final String scenarioId) {
    return ChaosPlatform.current().diagnostics().snapshot().scenarios().stream()
        .filter(report -> report.id().equals(scenarioId))
        .map(report -> report.state().name())
        .findFirst()
        .orElse("missing");
  }

  private static long collections() {
    long total = 0;
    for (final java.lang.management.GarbageCollectorMXBean collector :
        ManagementFactory.getGarbageCollectorMXBeans()) {
      total += Math.max(0L, collector.getCollectionCount());
    }
    return total;
  }

  /** The thread-local-leak stressor on the common pool, planting one small entry per worker. */
  private static ChaosScenario threadLocalLeak() {
    return ChaosScenario.builder("thread-local-leak")
        .selector(ChaosSelector.stress(ChaosSelector.StressTarget.THREAD_LOCAL_LEAK))
        .effect(new ChaosEffect.ThreadLocalLeakEffect(1, 16))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  /**
   * Activates the thread-local-leak stressor, on another thread, while a gate holds every
   * FORK_JOIN_TASK_RUN: prints {@code threadLocalLeak=activated} and the milliseconds it took, or
   * {@code threadLocalLeak=timeout} after 5 s.
   */
  private static void threadLocalLeakForkJoinGate() throws Exception {
    final ChaosControlPlane plane = ChaosPlatform.current();
    final ChaosActivationHandle gate =
        plane.activate(
            ChaosScenario.builder("gate-fork-join")
                .selector(ChaosSelector.executor(Set.of(OperationType.FORK_JOIN_TASK_RUN)))
                .effect(new ChaosEffect.GateEffect(null))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final java.util.concurrent.atomic.AtomicReference<ChaosActivationHandle> leak =
        new java.util.concurrent.atomic.AtomicReference<>();
    final long start = System.nanoTime();
    final Thread activator =
        Thread.ofPlatform().daemon().start(() -> leak.set(plane.activate(threadLocalLeak())));
    activator.join(5_000L);
    final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    System.out.println("threadLocalLeak=" + (leak.get() != null ? "activated" : "timeout"));
    System.out.println("threadLocalLeakMillis=" + elapsedMillis);
    gate.close();
    activator.join(5_000L);
    if (leak.get() != null) {
      leak.get().close();
    }
  }

  /**
   * Activates the thread-local-leak stressor while every ThreadLocal.set is delayed by 2 s and
   * prints how long the activation took.
   */
  private static void threadLocalLeakSetDelay() {
    final ChaosControlPlane plane = ChaosPlatform.current();
    try (ChaosActivationHandle slowSet =
        plane.activate(
            ChaosScenario.builder("slow-thread-local-set")
                .selector(
                    new ChaosSelector.ThreadLocalSelector(
                        Set.of(OperationType.THREAD_LOCAL_SET), NamePattern.any()))
                .effect(ChaosEffect.delay(Duration.ofSeconds(2)))
                .activationPolicy(ActivationPolicy.always())
                .build())) {
      final long start = System.nanoTime();
      try (ChaosActivationHandle leak = plane.activate(threadLocalLeak())) {
        System.out.println(
            "threadLocalLeakMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      }
    }
  }

  /**
   * Inside a session with a session-scoped THREAD_LOCAL_GET suppress, reads the current session
   * from the diagnostics: prints {@code sessionVisible=true} when it is the session, else what was
   * seen.
   */
  private static void sessionThreadLocalSuppress() {
    try (ChaosSession session = ChaosPlatform.current().openSession("thread-local")) {
      session.activate(
          ChaosScenario.builder("suppress-thread-local-get")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(
                  new ChaosSelector.ThreadLocalSelector(
                      Set.of(OperationType.THREAD_LOCAL_GET), NamePattern.any()))
              .effect(ChaosEffect.suppress())
              .activationPolicy(ActivationPolicy.always())
              .build());
      String seen;
      try {
        seen =
            ChaosPlatform.current()
                .diagnostics()
                .snapshot()
                .runtimeDetails()
                .get("currentSessionId");
      } catch (final RuntimeException failed) {
        seen = failed.getClass().getSimpleName();
      }
      System.out.println("sessionVisible=" + (session.id().equals(seen) ? "true" : seen));
    }
  }

  /**
   * Run with {@code -XX:MaxDirectMemorySize=8m}: activates 64 MiB of direct-buffer pressure and
   * prints what the activation threw ({@code directBuffer=}) and the recorded failure category.
   */
  private static void directBufferAboveLimit() {
    final ChaosControlPlane plane = ChaosPlatform.current();
    try (ChaosActivationHandle handle =
        plane.activate(
            ChaosScenario.builder("direct-64m")
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
                .effect(
                    new ChaosEffect.DirectBufferPressureEffect(
                        64L * 1024 * 1024, 1024 * 1024, false))
                .activationPolicy(ActivationPolicy.always())
                .build())) {
      System.out.println("directBuffer=" + handle.state());
    } catch (final RuntimeException | Error failed) {
      System.out.println("directBuffer=" + failed.getClass().getSimpleName());
    }
    System.out.println(
        "category="
            + plane.diagnostics().snapshot().failures().stream()
                .map(failure -> failure.category().name())
                .findFirst()
                .orElse("none"));
  }

  /**
   * An exceptional-completion effect on ASYNC_COMPLETE, with a dependent registered before the
   * completion so it runs inside the agent's own completeExceptionally. Row one: the dependent
   * sleeps 1 ms under a 200 ms THREAD_SLEEP delay of this thread; prints the delay's applied count
   * and how long the completion took. Row two: the dependent completes another future, matched by
   * the same scenario capped at one application; prints its applied count and how the other future
   * ended.
   */
  private static void completionDependents() throws Exception {
    final ChaosControlPlane plane = ChaosPlatform.current();
    final ChaosScenario.Builder failCompletion =
        ChaosScenario.builder("fail-completion")
            .selector(ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)))
            .effect(
                ChaosEffect.exceptionalCompletion(
                    ChaosEffect.FailureKind.ILLEGAL_STATE, "injected"));
    try (ChaosActivationHandle completion =
            plane.activate(failCompletion.activationPolicy(ActivationPolicy.always()).build());
        ChaosActivationHandle sleep =
            plane.activate(
                ChaosScenario.builder("slow-dependent")
                    .selector(
                        new ChaosSelector.ThreadSelector(
                            Set.of(OperationType.THREAD_SLEEP),
                            ChaosSelector.ThreadKind.ANY,
                            NamePattern.exact(Thread.currentThread().getName()),
                            null))
                    .effect(ChaosEffect.delay(Duration.ofMillis(200)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      final java.util.concurrent.CompletableFuture<String> future =
          new java.util.concurrent.CompletableFuture<>();
      future.whenComplete((value, failure) -> sleepOneMillisecond());
      final long start = System.nanoTime();
      future.complete("value");
      System.out.println(
          "completionMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      System.out.println("completedExceptionally=" + future.isCompletedExceptionally());
      System.out.println("sleepApplied=" + sleep.appliedCount());
    }
    try (ChaosActivationHandle once =
        plane.activate(failCompletion.activationPolicy(ActivationPolicy.count(1)).build())) {
      final java.util.concurrent.CompletableFuture<String> future =
          new java.util.concurrent.CompletableFuture<>();
      final java.util.concurrent.CompletableFuture<String> other =
          new java.util.concurrent.CompletableFuture<>();
      future.whenComplete((value, failure) -> other.complete("other"));
      future.complete("value");
      System.out.println("onceApplied=" + once.appliedCount());
      System.out.println("other=" + (other.isCompletedExceptionally() ? "failed" : other.join()));
    }
  }

  private static void sleepOneMillisecond() {
    try {
      Thread.sleep(1L);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
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

  /**
   * 50 virtual threads that each sleep 1 ms: every start and every wake-up mounts the thread on a
   * carrier through a fork-join task of the virtual-thread scheduler.
   */
  private static void virtualThreadResumes() throws InterruptedException {
    final java.util.List<Thread> threads = new java.util.ArrayList<>();
    for (int index = 0; index < 50; index++) {
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      Thread.sleep(1L);
                    } catch (final InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                    }
                  }));
    }
    for (final Thread thread : threads) {
      thread.join();
    }
  }

  /** A parallel stream: its tasks run in the common fork-join pool. */
  private static void parallelStream() {
    System.out.println(
        "parallelSum=" + java.util.stream.LongStream.range(0L, 100_000L).parallel().sum());
  }

  private static long appliedCount(final String scenarioId) {
    return ChaosPlatform.current()
        .diagnostics()
        .scenario(scenarioId)
        .map(ChaosDiagnostics.ScenarioReport::appliedCount)
        .orElse(-1L);
  }

  /** Where the agent jar keeps the bootstrap bridge classes, out of every class loader's view. */
  private static final String BRIDGE_LAYOUT = "META-INF/macstab-chaos-bridge/";

  /**
   * A web application's class loader: child-first over the agent jar and the libraries in its
   * {@code WEB-INF/lib} (here Kafka and {@code childfirst.ChildFirstTarget}), as Tomcat's is.
   * Prints whether a method hook and a library hook in classes it defines reach the agent, where it
   * resolves every bootstrap bridge class from, and what a loader that never delegates and holds
   * its own copy of the bridge records instead of a {@code LinkageError}. Runs on the agent jar
   * alone ({@link ChildJvmProbe#runShadedOnly}); {@code chaos.bootstrap.agentJar} names it and
   * {@code chaos.test.isolatedClasspath} names the Kafka jars.
   */
  private static void childFirstLoader() throws Exception {
    final Path agentJar = Path.of(System.getProperty("chaos.bootstrap.agentJar"));
    final URL probeClasses =
        DefectProbeMain.class.getProtectionDomain().getCodeSource().getLocation();
    final java.util.List<URL> webInfLib = new java.util.ArrayList<>();
    webInfLib.add(agentJar.toUri().toURL());
    webInfLib.add(probeClasses);
    for (final String entry :
        Arrays.stream(
                System.getProperty("chaos.test.isolatedClasspath")
                    .split(java.io.File.pathSeparator))
            .toList()) {
      webInfLib.add(Path.of(entry).toUri().toURL());
    }
    final ChaosControlPlane controlPlane = ChaosPlatform.current();
    try (URLClassLoader webapp =
            new ChildFirstClassLoader(
                webInfLib.toArray(new URL[0]), ClassLoader.getSystemClassLoader());
        ChaosActivationHandle method =
            controlPlane.activate(
                ChaosScenario.builder("child-first-method")
                    .selector(
                        ChaosSelector.method(
                            Set.of(OperationType.METHOD_ENTER),
                            NamePattern.exact("childfirst.ChildFirstTarget"),
                            NamePattern.exact("call")))
                    .effect(ChaosEffect.reject("child-first down"))
                    .activationPolicy(ActivationPolicy.always())
                    .build());
        ChaosActivationHandle kafka =
            controlPlane.activate(
                ChaosScenario.builder("child-first-kafka")
                    .selector(
                        ChaosSelector.messaging(
                            Set.of(OperationType.MESSAGE_SEND), NamePattern.exact("orders")))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      // The method hook: a reject throws from the woven method.
      final Class<?> target = Class.forName("childfirst.ChildFirstTarget", true, webapp);
      System.out.println("target.childFirst=" + (target.getClassLoader() == webapp));
      try {
        System.out.println(
            "target.call="
                + target.getMethod("call").invoke(target.getConstructor().newInstance()));
      } catch (final java.lang.reflect.InvocationTargetException failure) {
        System.out.println("target.call=" + failure.getCause().getClass().getName());
      }
      System.out.println("method.applied=" + method.appliedCount());

      // The library hook: Kafka in WEB-INF/lib.
      final Thread current = Thread.currentThread();
      final ClassLoader previous = current.getContextClassLoader();
      current.setContextClassLoader(webapp);
      try {
        final java.util.Properties config = new java.util.Properties();
        config.put("bootstrap.servers", "127.0.0.1:1");
        config.put("max.block.ms", "100");
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        final Class<?> producerType =
            Class.forName("org.apache.kafka.clients.producer.KafkaProducer", true, webapp);
        final Class<?> recordType =
            Class.forName("org.apache.kafka.clients.producer.ProducerRecord", true, webapp);
        try (AutoCloseable producer =
            (AutoCloseable)
                producerType.getConstructor(java.util.Properties.class).newInstance(config)) {
          producerType
              .getMethod("send", recordType)
              .invoke(
                  producer,
                  recordType
                      .getConstructor(String.class, Object.class, Object.class)
                      .newInstance("orders", "k", "v"));
        }
      } finally {
        current.setContextClassLoader(previous);
      }
      System.out.println("kafka.matched=" + kafka.matchedCount());

      // Every bridge class resolves to the boot copy, and the jar keeps exactly those classes
      // out of view: the Gradle relocation list and BridgeInjector.BRIDGE_CLASSES agree.
      final java.lang.reflect.Field listed =
          Class.forName("com.macstab.chaos.jvm.instrumentation.BridgeInjector")
              .getDeclaredField("BRIDGE_CLASSES");
      listed.setAccessible(true);
      @SuppressWarnings("unchecked")
      final java.util.List<String> bridgeClasses = (java.util.List<String>) listed.get(null);
      final java.util.Set<String> notBoot = new java.util.TreeSet<>();
      for (final String path : bridgeClasses) {
        final String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        if (Class.forName(name, false, webapp).getClassLoader() != null) {
          notBoot.add(name);
        }
      }
      final java.util.Set<String> unlisted = new java.util.TreeSet<>();
      try (java.util.jar.JarFile jar = new java.util.jar.JarFile(agentJar.toFile())) {
        jar.stream()
            .map(java.util.zip.ZipEntry::getName)
            .filter(name -> name.startsWith(BRIDGE_LAYOUT) && name.endsWith(".class"))
            .map(name -> name.substring(BRIDGE_LAYOUT.length()))
            .filter(path -> !bridgeClasses.contains(path))
            .forEach(unlisted::add);
      }
      System.out.println("bridge.count=" + bridgeClasses.size());
      System.out.println("bridge.notBoot=" + notBoot);
      System.out.println("bridge.unlisted=" + unlisted);

      // A loader that never delegates (JBoss Modules, OSGi without boot delegation) and holds its
      // own copy of the bridge: its classes are not woven, and it is named as a hook failure.
      final Path isolatedCopy = Files.createTempDirectory("chaos-non-delegating");
      try (java.util.jar.JarFile jar = new java.util.jar.JarFile(agentJar.toFile())) {
        for (final String path : bridgeClasses) {
          final java.util.zip.ZipEntry entry =
              jar.getEntry(BRIDGE_LAYOUT + path) != null
                  ? jar.getEntry(BRIDGE_LAYOUT + path)
                  : jar.getEntry(path);
          final Path copy = isolatedCopy.resolve(path);
          Files.createDirectories(copy.getParent());
          try (java.io.InputStream in = jar.getInputStream(entry)) {
            Files.copy(in, copy);
          }
        }
      }
      final String targetPath = "childfirst/ChildFirstTarget.class";
      Files.createDirectories(isolatedCopy.resolve("childfirst"));
      try (java.io.InputStream in = webapp.getResourceAsStream(targetPath)) {
        Files.copy(java.util.Objects.requireNonNull(in), isolatedCopy.resolve(targetPath));
      }
      try (URLClassLoader module =
              new NonDelegatingClassLoader(new URL[] {isolatedCopy.toUri().toURL()});
          java.util.stream.Stream<Path> copied = Files.walk(isolatedCopy)) {
        // Registered parents first: deleteOnExit deletes in reverse order, children first.
        copied.forEach(path -> path.toFile().deleteOnExit());
        System.out.println("module.loader=" + module);
        final Class<?> moduleTarget = Class.forName("childfirst.ChildFirstTarget", true, module);
        try {
          System.out.println(
              "module.call="
                  + moduleTarget
                      .getMethod("call")
                      .invoke(moduleTarget.getConstructor().newInstance()));
        } catch (final java.lang.reflect.InvocationTargetException failure) {
          System.out.println("module.call=" + failure.getCause());
        }
      }
      System.out.println(
          "hookFailures="
              + controlPlane.diagnostics().snapshot().runtimeDetails().get("hookFailures"));
    }
  }

  /**
   * Child-first, as Tomcat's web application class loader: a class the Java SE loader holds comes
   * from it, any other from this loader's own URLs first, then from the parent.
   */
  private static final class ChildFirstClassLoader extends URLClassLoader {
    ChildFirstClassLoader(final URL[] urls, final ClassLoader parent) {
      super("child-first", urls, parent);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> type = findLoadedClass(name);
        if (type == null) {
          final ClassLoader javaSe = ClassLoader.getPlatformClassLoader();
          if (javaSe.getResource(name.replace('.', '/') + ".class") != null) {
            type = javaSe.loadClass(name);
          } else {
            try {
              type = findClass(name);
            } catch (final ClassNotFoundException notLocal) {
              type = getParent().loadClass(name);
            }
          }
        }
        if (resolve) {
          resolveClass(type);
        }
        return type;
      }
    }

    @Override
    public URL getResource(final String name) {
      final URL local = findResource(name);
      return local != null ? local : getParent().getResource(name);
    }
  }

  /** A module loader that delegates nothing but {@code java.*}: no class path, no boot path. */
  private static final class NonDelegatingClassLoader extends URLClassLoader {
    NonDelegatingClassLoader(final URL[] urls) {
      super("non-delegating", urls, ClassLoader.getPlatformClassLoader());
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      if (name.startsWith("java.")) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> type = findLoadedClass(name);
        if (type == null) {
          type = findClass(name);
        }
        if (resolve) {
          resolveClass(type);
        }
        return type;
      }
    }

    @Override
    public URL getResource(final String name) {
      return name.startsWith("java/") ? getParent().getResource(name) : findResource(name);
    }
  }

  /**
   * Kafka defined by a loader whose parent is the platform loader, as in a plugin or an isolated
   * module: it reaches the boot path but not the application class path, so every agent class the
   * inlined advice references must come from the bootstrap bridge. The libraries come from {@code
   * chaos.test.isolatedClasspath} and are not on this JVM's class path.
   */
  private static void kafkaIsolatedLoader() throws Exception {
    final java.util.List<URL> urls = new java.util.ArrayList<>();
    for (final String entry :
        Arrays.stream(
                System.getProperty("chaos.test.isolatedClasspath")
                    .split(java.io.File.pathSeparator))
            .toList()) {
      urls.add(java.nio.file.Path.of(entry).toUri().toURL());
    }
    final Thread current = Thread.currentThread();
    final ClassLoader previous = current.getContextClassLoader();
    try (URLClassLoader isolated =
        new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
      // Kafka resolves the configured serializers through the context class loader.
      current.setContextClassLoader(isolated);
      final java.util.Properties config = new java.util.Properties();
      config.put("bootstrap.servers", "127.0.0.1:1");
      // No broker: the send waits this long for metadata, not the default 60 s.
      config.put("max.block.ms", "100");
      config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
      config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
      final Class<?> producerType =
          Class.forName("org.apache.kafka.clients.producer.KafkaProducer", true, isolated);
      final Class<?> recordType =
          Class.forName("org.apache.kafka.clients.producer.ProducerRecord", true, isolated);
      System.out.println("loader.isolated=" + (producerType.getClassLoader() == isolated));
      try (AutoCloseable producer =
          (AutoCloseable)
              producerType.getConstructor(java.util.Properties.class).newInstance(config)) {
        final Object record =
            recordType
                .getConstructor(String.class, Object.class, Object.class)
                .newInstance("orders", "k", "v");
        try {
          producerType.getMethod("send", recordType).invoke(producer, record);
          System.out.println("orders.type=sent");
        } catch (final java.lang.reflect.InvocationTargetException failure) {
          System.out.println("orders.type=" + failure.getCause().getClass().getName());
        }
      }
    } finally {
      current.setContextClassLoader(previous);
    }
  }

  /** A JVM-scoped scenario that delays by 1 ms and is always active. */
  private static ChaosActivationHandle activateDelay(
      final String id, final ChaosSelector selector) {
    return ChaosPlatform.current()
        .activate(
            ChaosScenario.builder(id)
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(selector)
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());
  }

  /** A selector for the client side of a loopback connection to {@code port}. */
  private static ChaosSelector toPort(final OperationType operation, final int port) {
    return new ChaosSelector.NetworkSelector(
        Set.of(operation), NamePattern.any(), "127.0.0.1/32", port, null, null);
  }

  /**
   * A socket channel's adaptor ({@code channel.socket()}): its connect, one read of its input
   * stream and one write of its output stream, each counted once.
   */
  private static void socketAdaptor() throws Exception {
    final byte[] payload = new byte[100];
    try (java.net.ServerSocket server =
        new java.net.ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
      final int port = server.getLocalPort();
      final ChaosActivationHandle connect =
          activateDelay("adaptor-connect", toPort(OperationType.SOCKET_CONNECT, port));
      final ChaosActivationHandle read =
          activateDelay("adaptor-read", toPort(OperationType.SOCKET_READ, port));
      final ChaosActivationHandle write =
          activateDelay("adaptor-write", toPort(OperationType.SOCKET_WRITE, port));
      final java.util.concurrent.CompletableFuture<String> peer = serve(server, payload);
      try (java.nio.channels.SocketChannel channel = java.nio.channels.SocketChannel.open()) {
        final java.net.Socket socket = channel.socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        System.out.println("read=" + (socket.getInputStream().read(new byte[100]) > 0));
        socket.getOutputStream().write(new byte[10]);
      }
      System.out.println("peer=" + peer.get(5, TimeUnit.SECONDS));
      System.out.println("connect=" + connect.matchedCount());
      System.out.println("readMatched=" + read.matchedCount());
      System.out.println("write=" + write.matchedCount());
    }
  }

  /** {@code SSLServerSocket.accept()}: the JDK's TLS server socket overrides {@code accept}. */
  private static void sslServerAccept() throws Exception {
    final ChaosActivationHandle accept =
        activateDelay("ssl-accept", ChaosSelector.network(Set.of(OperationType.SOCKET_ACCEPT)));
    try (java.net.ServerSocket server =
        javax.net.ssl.SSLServerSocketFactory.getDefault()
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      acceptOne(server);
    }
    System.out.println("accept=" + accept.matchedCount());
  }

  /**
   * A server socket channel's adaptor ({@code ssc.socket().accept()}) with an accept timeout and
   * without one: each accept counted once.
   */
  private static void adaptorAcceptTimeout() throws Exception {
    try (java.nio.channels.ServerSocketChannel channel =
        java.nio.channels.ServerSocketChannel.open()) {
      channel.bind(new InetSocketAddress("127.0.0.1", 0));
      final java.net.ServerSocket adaptor = channel.socket();
      try (ChaosActivationHandle timed =
          activateDelay(
              "accept-timed", ChaosSelector.network(Set.of(OperationType.SOCKET_ACCEPT)))) {
        adaptor.setSoTimeout(5_000);
        acceptOne(adaptor);
        System.out.println("timed=" + timed.matchedCount());
      }
      try (ChaosActivationHandle untimed =
          activateDelay(
              "accept-untimed", ChaosSelector.network(Set.of(OperationType.SOCKET_ACCEPT)))) {
        adaptor.setSoTimeout(0);
        acceptOne(adaptor);
        System.out.println("untimed=" + untimed.matchedCount());
      }
    }
  }

  /** Accepts one connection a client thread opens to {@code server}. */
  private static void acceptOne(final java.net.ServerSocket server) throws Exception {
    final int port = server.getLocalPort();
    final java.util.concurrent.CompletableFuture<Void> client =
        java.util.concurrent.CompletableFuture.runAsync(
            () -> {
              try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                socket.getInputStream().read();
              } catch (final java.io.IOException closed) {
                // the server closes the connection
              }
            });
    try (java.net.Socket accepted = server.accept()) {
      System.out.println("accepted=" + accepted.getClass().getSimpleName());
    }
    client.get(5, TimeUnit.SECONDS);
  }

  /**
   * {@code executeBatch} of a {@code Statement} and a {@code PreparedStatement} and {@code
   * executeLargeBatch}, each of two rows, on H2: each batch counted once.
   */
  private static void jdbcBatch() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:batch");
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE t (id INT)");
      try (PreparedStatement prepared = connection.prepareStatement("INSERT INTO t VALUES (?)");
          ChaosActivationHandle batch =
              activateDelay("batch", ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE))) {
        long before = batch.matchedCount();
        statement.addBatch("INSERT INTO t VALUES (1)");
        statement.addBatch("INSERT INTO t VALUES (2)");
        System.out.println("statementRows=" + statement.executeBatch().length);
        System.out.println("statementBatch=" + (batch.matchedCount() - before));
        before = batch.matchedCount();
        prepared.setInt(1, 3);
        prepared.addBatch();
        prepared.setInt(1, 4);
        prepared.addBatch();
        System.out.println("preparedRows=" + prepared.executeBatch().length);
        System.out.println("preparedBatch=" + (batch.matchedCount() - before));
        before = batch.matchedCount();
        statement.addBatch("INSERT INTO t VALUES (5)");
        statement.addBatch("INSERT INTO t VALUES (6)");
        System.out.println("largeRows=" + statement.executeLargeBatch().length);
        System.out.println("largeBatch=" + (batch.matchedCount() - before));
      }
    }
  }

  /**
   * {@code execute} and {@code submit} of a {@code ScheduledThreadPoolExecutor} and of a
   * virtual-thread-per-task executor under EXECUTOR_SUBMIT, SCHEDULE_SUBMIT and
   * VIRTUAL_THREAD_START scenarios: prints the matches each call adds to each scenario.
   */
  private static void executorSubmitKinds() throws Exception {
    final ChaosActivationHandle submit =
        activateDelay("submit", ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)));
    final ChaosActivationHandle schedule =
        activateDelay("schedule", ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)));
    final ChaosActivationHandle vtStart =
        activateDelay(
            "vt-start",
            ChaosSelector.thread(
                Set.of(OperationType.VIRTUAL_THREAD_START), ChaosSelector.ThreadKind.VIRTUAL));
    final java.util.concurrent.ScheduledThreadPoolExecutor stpe =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
      final Map<String, Call> calls = new java.util.LinkedHashMap<>();
      calls.put("stpeSubmit", () -> stpe.submit(() -> {}).get(5, TimeUnit.SECONDS));
      calls.put("stpeSubmitCallable", () -> stpe.submit(() -> "x").get(5, TimeUnit.SECONDS));
      calls.put("stpeExecute", () -> runAndAwait(stpe));
      calls.put("vtSubmit", () -> virtual.submit(() -> {}).get(5, TimeUnit.SECONDS));
      calls.put("vtSubmitCallable", () -> virtual.submit(() -> "x").get(5, TimeUnit.SECONDS));
      calls.put("vtExecute", () -> runAndAwait(virtual));
      for (final Map.Entry<String, Call> call : calls.entrySet()) {
        final long submits = submit.matchedCount();
        final long schedules = schedule.matchedCount();
        final long starts = vtStart.matchedCount();
        call.getValue().run();
        System.out.println(call.getKey() + ".submit=" + (submit.matchedCount() - submits));
        System.out.println(call.getKey() + ".schedule=" + (schedule.matchedCount() - schedules));
        System.out.println(call.getKey() + ".vtStart=" + (vtStart.matchedCount() - starts));
      }
    } finally {
      stpe.shutdownNow();
    }
  }

  /** {@code executor.execute} of a task that counts down a latch, then waits for it. */
  private static void runAndAwait(final java.util.concurrent.Executor executor) throws Exception {
    final CountDownLatch ran = new CountDownLatch(1);
    executor.execute(ran::countDown);
    if (!ran.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("task never ran");
    }
  }

  /**
   * An EXECUTOR_SUBMIT suppress on a {@code ScheduledThreadPoolExecutor} and a
   * virtual-thread-per-task executor: the task is replaced by a no-op and its future completes with
   * {@code null}, not cancelled.
   */
  private static void executorSubmitSuppress() throws Exception {
    final java.util.concurrent.atomic.AtomicInteger ran =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.ScheduledThreadPoolExecutor stpe =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    try (ChaosActivationHandle suppress =
            ChaosPlatform.current()
                .activate(
                    ChaosScenario.builder("suppress")
                        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                        .effect(ChaosEffect.suppress())
                        .activationPolicy(ActivationPolicy.always())
                        .build());
        ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
      final java.util.concurrent.Future<?> stpeRunnable =
          stpe.submit((Runnable) ran::incrementAndGet);
      final java.util.concurrent.Future<String> stpeCallable =
          stpe.submit(
              () -> {
                ran.incrementAndGet();
                return "ran";
              });
      final java.util.concurrent.Future<?> vtRunnable =
          virtual.submit((Runnable) ran::incrementAndGet);
      final java.util.concurrent.Future<String> vtCallable =
          virtual.submit(
              () -> {
                ran.incrementAndGet();
                return "ran";
              });
      System.out.println("stpeRunnable=" + stpeRunnable.get(5, TimeUnit.SECONDS));
      System.out.println("stpeCallable=" + stpeCallable.get(5, TimeUnit.SECONDS));
      System.out.println("vtRunnable=" + vtRunnable.get(5, TimeUnit.SECONDS));
      System.out.println("vtCallable=" + vtCallable.get(5, TimeUnit.SECONDS));
      System.out.println(
          "cancelled="
              + (stpeRunnable.isCancelled()
                  || stpeCallable.isCancelled()
                  || vtRunnable.isCancelled()
                  || vtCallable.isCancelled()));
      System.out.println("ran=" + ran.get());
      System.out.println("applied=" + suppress.appliedCount());
    } finally {
      stpe.shutdownNow();
    }
  }

  /**
   * A session-scoped SCHEDULE_TICK and EXECUTOR_SUBMIT on {@code stpe.submit(r)} from the bound
   * session: the submit is evaluated as EXECUTOR_SUBMIT and the task's run still as a tick of the
   * session.
   */
  private static void sessionTickStpeSubmit() throws Exception {
    final java.util.concurrent.ScheduledThreadPoolExecutor stpe =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    try (ChaosSession session = ChaosPlatform.current().openSession("tick-submit")) {
      final ChaosActivationHandle tick =
          session.activate(
              ChaosScenario.builder("session-tick")
                  .scope(ChaosScenario.ScenarioScope.SESSION)
                  .selector(ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_TICK)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final ChaosActivationHandle submit =
          session.activate(
              ChaosScenario.builder("session-submit")
                  .scope(ChaosScenario.ScenarioScope.SESSION)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try (ChaosSession.ScopeBinding binding = session.bind()) {
        stpe.submit(() -> {}).get(5, TimeUnit.SECONDS);
      }
      System.out.println("tick=" + tick.matchedCount());
      System.out.println("submit=" + submit.matchedCount());
    } finally {
      stpe.shutdownNow();
    }
  }

  /**
   * A {@code periodicOnly} SCHEDULE_SUBMIT and SCHEDULE_TICK scenario: a one-shot {@code schedule}
   * and a {@code scheduleAtFixedRate}; prints the matches each adds.
   */
  private static void schedulePeriodicOnly() throws Exception {
    final ChaosActivationHandle submit =
        activateDelay(
            "periodic-submit",
            new ChaosSelector.SchedulingSelector(
                Set.of(OperationType.SCHEDULE_SUBMIT), NamePattern.any(), true));
    final ChaosActivationHandle tick =
        activateDelay(
            "periodic-tick",
            new ChaosSelector.SchedulingSelector(
                Set.of(OperationType.SCHEDULE_TICK), NamePattern.any(), true));
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    try {
      executor.schedule(() -> {}, 0L, TimeUnit.MILLISECONDS).get(5L, TimeUnit.SECONDS);
      System.out.println("oneShotSubmit=" + submit.matchedCount());
      System.out.println("oneShotTick=" + tick.matchedCount());
      final CountDownLatch twoRuns = new CountDownLatch(2);
      final ScheduledFuture<?> periodic =
          executor.scheduleAtFixedRate(twoRuns::countDown, 0L, 10L, TimeUnit.MILLISECONDS);
      System.out.println("periodicRan=" + twoRuns.await(5L, TimeUnit.SECONDS));
      periodic.cancel(false);
      System.out.println("periodicSubmit=" + submit.matchedCount());
      System.out.println("periodicTicks=" + (tick.matchedCount() > 0L));
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * A {@code scheduledOnly} ExecutorSelector on EXECUTOR_SUBMIT, EXECUTOR_WORKER_RUN and
   * EXECUTOR_SHUTDOWN, and one on FORK_JOIN_TASK_RUN: a task through a {@code
   * ScheduledThreadPoolExecutor}, a {@code ThreadPoolExecutor} and the common pool; prints the
   * matches of each executor.
   */
  private static void executorScheduledOnly() throws Exception {
    final ChaosActivationHandle submit =
        activateDelay(
            "scheduled-submit",
            new ChaosSelector.ExecutorSelector(
                Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any(), NamePattern.any(), true));
    final ChaosActivationHandle run =
        activateDelay(
            "scheduled-run",
            new ChaosSelector.ExecutorSelector(
                Set.of(OperationType.EXECUTOR_WORKER_RUN),
                NamePattern.any(),
                NamePattern.any(),
                true));
    final ChaosActivationHandle shutdown =
        activateDelay(
            "scheduled-shutdown",
            new ChaosSelector.ExecutorSelector(
                Set.of(OperationType.EXECUTOR_SHUTDOWN),
                NamePattern.any(),
                NamePattern.any(),
                true));
    final ChaosActivationHandle forkJoin =
        activateDelay(
            "scheduled-fork-join",
            new ChaosSelector.ExecutorSelector(
                Set.of(OperationType.FORK_JOIN_TASK_RUN),
                NamePattern.any(),
                NamePattern.any(),
                true));
    final Map<String, ExecutorService> executors = new java.util.LinkedHashMap<>();
    executors.put("stpe", new java.util.concurrent.ScheduledThreadPoolExecutor(1));
    executors.put("tpe", Executors.newFixedThreadPool(1));
    for (final Map.Entry<String, ExecutorService> executor : executors.entrySet()) {
      final long submits = submit.matchedCount();
      final long runs = run.matchedCount();
      final long shutdowns = shutdown.matchedCount();
      executor.getValue().submit(() -> {}).get(5L, TimeUnit.SECONDS);
      executor.getValue().shutdown();
      executor.getValue().awaitTermination(5L, TimeUnit.SECONDS);
      System.out.println(executor.getKey() + ".submit=" + (submit.matchedCount() - submits));
      System.out.println(executor.getKey() + ".run=" + (run.matchedCount() - runs));
      System.out.println(executor.getKey() + ".shutdown=" + (shutdown.matchedCount() - shutdowns));
    }
    java.util.concurrent.ForkJoinPool.commonPool().submit(() -> {}).get(5L, TimeUnit.SECONDS);
    System.out.println("forkJoin=" + forkJoin.matchedCount());
  }

  /**
   * A day-ahead CLOCK_READ / INSTANT_NOW skew and an hour-long SYSTEM_CLOCK_MILLIS skew read
   * through {@code ChaosRuntime.adjustClockMillis}, the documented manual hook, outside any
   * dispatch: the hour's window must be read on the real clock, not the skewed one.
   */
  private static void skewedWindowManualRead() {
    final ChaosControlPlane plane = ChaosPlatform.current();
    plane.activate(
        ChaosScenario.builder("day-ahead")
            .selector(
                ChaosSelector.jvmRuntime(
                    Set.of(OperationType.CLOCK_READ, OperationType.INSTANT_NOW)))
            .effect(ChaosEffect.skewClock(Duration.ofDays(1), ChaosEffect.ClockSkewMode.FIXED))
            .activationPolicy(ActivationPolicy.always())
            .build());
    final ChaosActivationHandle hour =
        plane.activate(
            ChaosScenario.builder("hour")
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
                .effect(
                    ChaosEffect.skewClock(Duration.ofSeconds(5), ChaosEffect.ClockSkewMode.FIXED))
                .activationPolicy(ActivationPolicy.builder().activeFor(Duration.ofHours(1)).build())
                .build());
    final long real = 1_700_000_000_000L;
    System.out.println(
        "skewMillis="
            + (((com.macstab.chaos.jvm.core.ChaosRuntime) plane).adjustClockMillis(real) - real));
    System.out.println("hourState=" + hour.state());
  }

  /**
   * Installs the agent with reports on and a startup plan that does not parse: the status file
   * reports the error, with its position, before the start fails.
   */
  private static void startupPlanErrorStatus() throws Exception {
    final Path reports = Files.createTempDirectory("chaos-reports");
    try {
      ChaosAgentBootstrap.initialize(
          "configJson={\"scenarios\": [;reports=on;reportsDir=" + reports,
          net.bytebuddy.agent.ByteBuddyAgent.install(),
          Map.of());
      System.out.println("failed=false");
    } catch (final com.macstab.chaos.jvm.startup.ConfigLoadException failure) {
      System.out.println("failed=true");
    }
    final String status = Files.readString(reports.resolve(ChaosReports.STATUS_FILE));
    for (final String line : status.lines().toList()) {
      System.out.println("status." + line);
    }
    // No agent is installed, so there are no counts to print.
    System.out.flush();
    System.exit(0);
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

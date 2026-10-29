package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
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

  private static void printCounts() {
    final ChaosControlPlane controlPlane = ChaosPlatform.current();
    for (final ChaosDiagnostics.ScenarioReport report :
        controlPlane.diagnostics().snapshot().scenarios()) {
      System.out.println("matched." + report.id() + "=" + report.matchedCount());
      System.out.println("applied." + report.id() + "=" + report.appliedCount());
    }
  }
}

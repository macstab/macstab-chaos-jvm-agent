package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests of the agent: each runs {@link DefectProbeMain} in a child JVM started with the
 * agent jar and asserts on what the probe observed, so the full advice → bridge → dispatcher path
 * is exercised.
 */
@DisplayName("Agent in a child JVM (end-to-end)")
class AgentEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe probe;

  @BeforeEach
  void setUp() {
    probe = new ChildJvmProbe(tempDir);
  }

  @Nested
  @DisplayName("ScheduledThreadPoolExecutor")
  class Scheduling {

    @Test
    @DisplayName("an idle agent keeps a sub-millisecond schedule delay in its original unit")
    void idleAgentKeepsSubMillisecondDelay() throws Exception {
      // Arrange: a scenario that never matches schedule calls, so the agent is idle for them.
      final ChaosPlan plan =
          plan(
              scenario(
                  "unrelated",
                  ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)),
                  ChaosEffect.delay(Duration.ofMillis(1))));

      // Act
      final ChildJvmProbe.ProbeResult result =
          probe.run(plan, DefectProbeMain.class, "schedule-sub-millisecond-delay", null);

      // Assert: the executor was given 1900 µs, not 1 ms (a millisecond rewrite truncates).
      // remaining + elapsed is the delay given, independent of how loaded the machine is.
      assertThat(result.longValue("scheduledDelayNanos"))
          .as("delay given to the executor by schedule(r, 1900, MICROSECONDS)")
          .isGreaterThanOrEqualTo(1_800_000L);
    }

    @Test
    @DisplayName("an idle agent keeps a sub-millisecond fixed-rate period")
    void idleAgentKeepsSubMillisecondPeriod() throws Exception {
      // Arrange
      final ChaosPlan plan =
          plan(
              scenario(
                  "unrelated",
                  ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)),
                  ChaosEffect.delay(Duration.ofMillis(1))));

      // Act
      final ChildJvmProbe.ProbeResult result =
          probe.run(plan, DefectProbeMain.class, "schedule-sub-millisecond-period", null);

      // Assert: a 300 µs period ticks about 3.3 times per ms; a 1 ms rewrite at most once.
      assertThat(result.longValue("ticks"))
          .as("ticks of scheduleAtFixedRate(r, 0, 300, MICROSECONDS) per elapsed ms")
          .isGreaterThan(3L * result.longValue("elapsedMillis") / 2L);
    }

    @Test
    @DisplayName("a SCHEDULE_SUBMIT delay is applied once per schedule() call")
    void scheduleSubmitDelayIsEvaluatedOnce() throws Exception {
      // Arrange
      final ChaosPlan plan =
          plan(
              scenario(
                  "schedule-delay",
                  ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)),
                  ChaosEffect.delay(Duration.ofMillis(300))));

      // Act
      final ChildJvmProbe.ProbeResult result =
          probe.run(plan, DefectProbeMain.class, "schedule-submit-delay", null);

      // Assert
      assertThat(result.longValue("matched.schedule-delay"))
          .as("one schedule() call is one match")
          .isEqualTo(1L);
      assertThat(result.longValue("applied.schedule-delay")).isEqualTo(1L);
      assertThat(result.longValue("submitBlockedMillis"))
          .as("the registration is delayed, as for EXECUTOR_SUBMIT")
          .isGreaterThanOrEqualTo(240L);
      assertThat(result.longValue("taskStartMillis") - result.longValue("submitBlockedMillis"))
          .as("the task is not postponed a second 300 ms after the delayed registration")
          .isLessThan(300L);
    }
  }

  @Nested
  @DisplayName("one evaluation per logical call (nested instrumented calls)")
  class SingleEvaluation {

    @Test
    @DisplayName("InetAddress.getByName is one DNS_RESOLVE match")
    void dnsGetByName() throws Exception {
      assertSingleMatch(
          "dns",
          ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.exact("localhost")),
          "dns-get-by-name");
    }

    @Test
    @DisplayName("a GZIPOutputStream write is one ZIP_DEFLATE match per deflate call")
    void gzipDeflate() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "deflate",
                      ChaosSelector.jvmRuntime(Set.of(OperationType.ZIP_DEFLATE)),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "gzip-write",
              null);
      // DeflaterOutputStream.write + finish call deflate(b, off, len) a handful of times; each
      // public overload delegates to deflate(b, off, len, flush), and each call counts once.
      final long matched = result.longValue("matched.deflate");
      assertThat(matched).isPositive();
      assertThat(result.longValue("applied.deflate")).isEqualTo(matched);
      assertThat(matched).as("deflate calls of one small gzip write").isLessThanOrEqualTo(3L);
    }

    @Test
    @DisplayName("MBeanServer.getAttribute is one JMX_GET_ATTR match")
    void jmxGetAttribute() throws Exception {
      assertSingleMatch(
          "jmx", ChaosSelector.jvmRuntime(Set.of(OperationType.JMX_GET_ATTR)), "jmx-get-attribute");
    }

    @Test
    @DisplayName("DelayQueue.put through the BlockingQueue bridge is one QUEUE_PUT match")
    void delayQueuePutThroughBridge() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "put",
                      new ChaosSelector.QueueSelector(
                          Set.of(OperationType.QUEUE_PUT),
                          NamePattern.exact("java.util.concurrent.DelayQueue")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "delay-queue-put",
              null);
      assertThat(result.longValue("matched.put")).isEqualTo(1L);
      assertThat(result.longValue("queueSize")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a rejecting QUEUE_OFFER scenario does not drop the element of a put()")
    void rejectedInnerOfferDoesNotDropPut() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "offer-reject",
                      new ChaosSelector.QueueSelector(
                          Set.of(OperationType.QUEUE_OFFER),
                          NamePattern.exact("java.util.concurrent.DelayQueue")),
                      ChaosEffect.reject("full"))),
              DefectProbeMain.class,
              "delay-queue-put",
              null);
      assertThat(result.longValue("queueSize")).as("put() must enqueue the element").isEqualTo(1L);
      assertThat(result.longValue("matched.offer-reject"))
          .as("the offer inside put() is part of the put, not an application offer")
          .isZero();
    }

    @Test
    @DisplayName("PriorityBlockingQueue.put is not also counted as QUEUE_OFFER")
    void priorityQueuePutIsNotAnOffer() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "pbq",
                      new ChaosSelector.QueueSelector(
                          Set.of(OperationType.QUEUE_PUT, OperationType.QUEUE_OFFER),
                          NamePattern.exact("java.util.concurrent.PriorityBlockingQueue")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "priority-queue-put",
              null);
      assertThat(result.longValue("matched.pbq")).isEqualTo(1L);
    }

    @Test
    @DisplayName("URLClassLoader.loadClass is one CLASS_LOAD match despite parent delegation")
    void urlClassLoaderLoad() throws Exception {
      assertSingleMatch(
          "load",
          ChaosSelector.classLoading(
              Set.of(OperationType.CLASS_LOAD), NamePattern.exact("java.util.ArrayList")),
          "url-class-loader-load");
    }

    @Test
    @DisplayName("ClassLoader.getResource is one RESOURCE_LOAD match despite parent delegation")
    void getResource() throws Exception {
      assertSingleMatch(
          "resource",
          ChaosSelector.classLoading(
              Set.of(OperationType.RESOURCE_LOAD), NamePattern.exact("java/lang/Object.class")),
          "get-resource");
    }

    @Test
    @DisplayName("LockSupport.parkUntil(Object, long) is one THREAD_PARK match")
    void parkUntil() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "unrelated",
                      ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.exact("x")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "park-until-in-session",
              null);
      assertThat(result.longValue("matched.park-delay")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Statement.execute through a Hikari proxy is one JDBC_STATEMENT_EXECUTE match")
    void hikariProxyStatementExecute() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "execute",
                      new ChaosSelector.JdbcSelector(
                          Set.of(OperationType.JDBC_STATEMENT_EXECUTE),
                          NamePattern.exact("SELECT 1")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "jdbc-hikari-execute",
              System.getProperty("chaos.test.jdbcClasspath"));
      assertThat(result.longValue("matched.execute")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a JDBC selector on the Hikari pool name matches that pool's connection acquire")
    void hikariPoolNameFilter() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "named-pool",
                      new ChaosSelector.JdbcSelector(
                          Set.of(OperationType.JDBC_CONNECTION_ACQUIRE),
                          NamePattern.exact("defect-pool")),
                      ChaosEffect.delay(Duration.ofMillis(1))),
                  scenario(
                      "other-pool",
                      new ChaosSelector.JdbcSelector(
                          Set.of(OperationType.JDBC_CONNECTION_ACQUIRE),
                          NamePattern.exact("other-pool")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "jdbc-hikari-execute",
              System.getProperty("chaos.test.jdbcClasspath"));
      assertThat(result.longValue("matched.named-pool")).isEqualTo(1L);
      assertThat(result.longValue("matched.other-pool")).isZero();
    }

    private void assertSingleMatch(final String id, final ChaosSelector selector, final String mode)
        throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(scenario(id, selector, ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              mode,
              null);
      assertThat(result.longValue("matched." + id)).as("matches of one call").isEqualTo(1L);
      assertThat(result.longValue("applied." + id)).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("call paths intercepted end to end")
  class CallPaths {

    private final String httpClasspath = System.getProperty("chaos.test.httpClasspath");

    @Test
    @DisplayName("PreparedStatement.executeQuery() with no arguments is intercepted")
    void preparedStatementExecuteQuery() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "execute",
                      ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "jdbc-prepared-execute",
              System.getProperty("chaos.test.jdbcClasspath"));
      assertThat(result.longValue("matched.execute")).isEqualTo(1L);
    }

    @Test
    @DisplayName("threads started by newVirtualThreadPerTaskExecutor are intercepted")
    void virtualThreadPerTaskExecutor() throws Exception {
      assertSingleMatch(
          "vt-start",
          ChaosSelector.thread(
              Set.of(OperationType.VIRTUAL_THREAD_START), ChaosSelector.ThreadKind.VIRTUAL),
          "virtual-thread-executor",
          null);
    }

    @Test
    @DisplayName("Thread.ofVirtual().start() is one VIRTUAL_THREAD_START match")
    void virtualThreadStart() throws Exception {
      assertSingleMatch(
          "vt-start",
          ChaosSelector.thread(
              Set.of(OperationType.VIRTUAL_THREAD_START), ChaosSelector.ThreadKind.VIRTUAL),
          "virtual-thread-start",
          null);
    }

    @Test
    @DisplayName("Runtime.exit is intercepted and can be vetoed")
    void runtimeExit() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "exit",
                      ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_EXIT_REQUEST)),
                      ChaosEffect.suppress())),
              DefectProbeMain.class,
              "runtime-exit",
              null);
      assertThat(result.value("exitSuppressed")).isEqualTo("true");
      assertThat(result.longValue("matched.exit")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Selector.select(Consumer, long) is intercepted")
    void selectWithConsumer() throws Exception {
      assertSingleMatch(
          "select",
          ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT)),
          "select-consumer",
          null);
    }

    @Test
    @DisplayName("Thread.sleep(Duration) is intercepted")
    void sleepWithDuration() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "sleep",
                      ChaosSelector.thread(
                          Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
                      ChaosEffect.suppress())),
              DefectProbeMain.class,
              "sleep-duration",
              null);
      assertThat(result.longValue("elapsedMillis"))
          .as("a suppressed Thread.sleep(Duration.ofSeconds(2)) returns before the 2 s it sleeps")
          .isLessThan(2_000L);
    }

    @Test
    @DisplayName("java.net.HttpURLConnection is one HTTP_CLIENT_SEND match per request")
    void httpUrlConnection() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "url-connection",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "http-url-connection",
              null);
      assertThat(result.value("body")).isEqualTo("ok");
      assertThat(result.longValue("matched.url-connection"))
          .as("getResponseCode() and getInputStream() drive one request")
          .isEqualTo(1L);
    }

    @Test
    @DisplayName("a suppressed HttpURLConnection request fails with ConnectException")
    void httpUrlConnectionSuppressed() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "url-connection-suppressed",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
                      ChaosEffect.suppress())),
              DefectProbeMain.class,
              "http-url-connection",
              null);
      assertThat(result.value("connectException")).contains("suppressed by chaos agent");
    }

    @Test
    @DisplayName("HttpClient.sendAsync(request, handler) is one HTTP_CLIENT_SEND_ASYNC match")
    void jdkSendAsync() throws Exception {
      assertSingleMatch(
          "send-async",
          ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC)),
          "jdk-send-async",
          null);
    }

    @Test
    @DisplayName("a URL filter matches JDK HttpClient requests")
    void jdkHttpClientUrlFilter() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "probe-url",
                      ChaosSelector.httpClient(
                          Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC),
                          com.macstab.chaos.jvm.api.NamePattern.glob("http://127.0.0.1:*/probe")),
                      ChaosEffect.delay(Duration.ofMillis(1))),
                  scenario(
                      "other-url",
                      ChaosSelector.httpClient(
                          Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC),
                          com.macstab.chaos.jvm.api.NamePattern.prefix("http://elsewhere/")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "jdk-send-async",
              null);
      assertThat(result.longValue("matched.probe-url")).isEqualTo(1L);
      assertThat(result.longValue("matched.other-url")).isZero();
    }

    @Test
    @DisplayName("OkHttp 4 Call.execute() is intercepted")
    void okHttp4Execute() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "okhttp",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
                      ChaosEffect.suppress())),
              HttpLibraryProbeMain.class,
              "okhttp-execute",
              httpClasspath);
      assertThat(result.value("suppressed")).isEqualTo("true");
      assertThat(result.longValue("matched.okhttp")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Apache HC5 execute(request) with one argument is one HTTP_CLIENT_SEND match")
    void hc5ExecuteOneArgument() throws Exception {
      assertSingleMatch(
          "hc5",
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("http://127.0.0.1:*/probe")),
          "hc5-execute",
          httpClasspath,
          HttpLibraryProbeMain.class);
    }

    @Test
    @DisplayName("Apache HC5 execute(HttpHost, request, handler) matches on the full URL")
    void hc5ExecuteWithHost() throws Exception {
      assertSingleMatch(
          "hc5-host",
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("http://127.0.0.1:*/probe")),
          "hc5-execute-host",
          httpClasspath,
          HttpLibraryProbeMain.class);
    }

    @Test
    @DisplayName("Apache HC4 execute(request) is one HTTP_CLIENT_SEND match on the full URL")
    void hc4Execute() throws Exception {
      assertSingleMatch(
          "hc4",
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("http://127.0.0.1:*/probe")),
          "hc4-execute",
          System.getProperty("chaos.test.coverageClasspath"),
          Hc4ProbeMain.class);
    }

    @Test
    @DisplayName("Apache HC4 execute(HttpHost, request) matches on the full URL")
    void hc4ExecuteWithHost() throws Exception {
      assertSingleMatch(
          "hc4-host",
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("http://127.0.0.1:*/probe")),
          "hc4-execute-host",
          System.getProperty("chaos.test.coverageClasspath"),
          Hc4ProbeMain.class);
    }

    @Test
    @DisplayName("Reactor Netty requests are intercepted")
    void reactorNetty() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "reactor",
                      ChaosSelector.httpClient(
                          Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC),
                          NamePattern.glob("http://127.0.0.1:*/probe")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              HttpLibraryProbeMain.class,
              "reactor-netty-get",
              httpClasspath);
      assertThat(result.value("body")).isEqualTo("ok");
      assertThat(result.longValue("matched.reactor")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a host-filtered socket read scenario matches reads from that host only")
    void socketReadHostFilter() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "read-loopback",
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_READ), NamePattern.exact("127.0.0.1")),
                      ChaosEffect.delay(Duration.ofMillis(1))),
                  scenario(
                      "read-elsewhere",
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_READ), NamePattern.exact("10.255.255.1")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "socket-read-loopback",
              null);
      assertThat(result.longValue("byte")).isEqualTo(42L);
      assertThat(result.longValue("matched.read-loopback")).isPositive();
      assertThat(result.longValue("matched.read-elsewhere")).isZero();
    }

    @Test
    @DisplayName("stopping a direct-buffer stressor frees its native memory at once")
    void directBufferStressorFreesOnStop() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "unrelated",
                      ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.exact("x")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "direct-buffer-release",
              null);
      assertThat(result.longValue("directDuringMiB")).isGreaterThanOrEqualTo(60L);
      assertThat(result.longValue("directAfterMiB"))
          .as("freed by the cleaner on close, not left for a later GC")
          .isLessThan(8L);
    }

    @Test
    @DisplayName("CLOCK_READ skews the system Clock and every now() without an operation")
    void clockReadsAreSkewed() throws Exception {
      final ChildJvmProbe.ProbeResult result = runClockReads(Set.of(OperationType.CLOCK_READ));
      for (final String read :
          List.of("clockInstant", "clockMillis", "instantSource", "offsetDateTime", "localTime")) {
        assertThat(result.longValue(read)).as(read).isBetween(3590L, 3610L);
      }
      // These have operations of their own, which the scenario does not select.
      assertThat(result.longValue("instantNow")).isBetween(-5L, 5L);
      assertThat(result.longValue("localDateTime")).isBetween(-5L, 5L);
    }

    @Test
    @DisplayName("a now() with its own operation is skewed once, not again by CLOCK_READ")
    void clockReadsAreNotSkewedTwice() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runClockReads(Set.of(OperationType.CLOCK_READ, OperationType.LOCAL_DATE_TIME_NOW));
      assertThat(result.longValue("localDateTime")).isBetween(3590L, 3610L);
      assertThat(result.longValue("offsetDateTime")).isBetween(3590L, 3610L);
    }

    private ChildJvmProbe.ProbeResult runClockReads(final Set<OperationType> operations)
        throws Exception {
      return probe.run(
          plan(
              scenario(
                  "skew",
                  ChaosSelector.jvmRuntime(operations),
                  ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED))),
          DefectProbeMain.class,
          "clock-reads",
          null);
    }

    @Test
    @DisplayName("an injected clock skew cannot move a scenario's time bounds")
    void timeBoundsUseTheRealClock() throws Exception {
      final ChaosScenario bounded =
          ChaosScenario.builder("bounded")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(
                  ActivationPolicy.builder()
                      .activeFor(Duration.ofHours(1))
                      .activeUntil(java.time.Instant.now().plus(Duration.ofHours(1)))
                      .build())
              .build();
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "skew",
                      ChaosSelector.jvmRuntime(
                          Set.of(OperationType.CLOCK_READ, OperationType.INSTANT_NOW)),
                      ChaosEffect.skewClock(Duration.ofDays(1), ChaosEffect.ClockSkewMode.FIXED)),
                  bounded),
              DefectProbeMain.class,
              "skewed-time-bounds",
              null);

      assertThat(result.longValue("clockAhead"))
          .as("the skew is in force")
          .isBetween(86_390L, 86_410L);
      assertThat(result.longValue("applied.bounded"))
          .as("a day ahead lies past activeUntil and activeFor; the bounds read the real clock")
          .isEqualTo(1L);
      assertThat(result.longValue("eventAhead"))
          .as("event timestamps read the real clock, not the skewed one")
          .isBetween(-5L, 5L);
    }

    @Test
    @DisplayName("LocalDateTime.now(ZoneId) and ZonedDateTime.now(ZoneId) are clock-skewed")
    void nowWithZoneIsSkewed() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "skew",
                      ChaosSelector.jvmRuntime(
                          Set.of(
                              OperationType.LOCAL_DATE_TIME_NOW,
                              OperationType.ZONED_DATE_TIME_NOW)),
                      ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED))),
              DefectProbeMain.class,
              "clock-now-in-zone",
              null);
      assertThat(result.longValue("localSkewSeconds")).isBetween(3590L, 3610L);
      assertThat(result.longValue("zonedSkewSeconds")).isBetween(3590L, 3610L);
    }

    private void assertSingleMatch(
        final String id, final ChaosSelector selector, final String mode, final String classpath)
        throws Exception {
      assertSingleMatch(id, selector, mode, classpath, DefectProbeMain.class);
    }

    private void assertSingleMatch(
        final String id,
        final ChaosSelector selector,
        final String mode,
        final String classpath,
        final Class<?> probeMain)
        throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(scenario(id, selector, ChaosEffect.delay(Duration.ofMillis(1)))),
              probeMain,
              mode,
              classpath);
      assertThat(result.longValue("matched." + id)).as("matches of one call").isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("agent initialization")
  class Initialization {

    @Test
    @DisplayName("a failed initialization is rolled back completely before a retry")
    void failedInitializationIsRolledBack() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(DefectProbeMain.class, "initialize-retry");

      assertThat(result.value("firstFailed")).isEqualTo("true");
      // Two sets of transformers would evaluate the sleep twice.
      assertThat(result.longValue("applied.sleep-delay"))
          .as("one set of transformers: the 300 ms delay applies once")
          .isEqualTo(1L);
      assertThat(result.longValue("sleepMillis")).isGreaterThanOrEqualTo(250L);
      assertThat(result.value("mbeanReportsRetry"))
          .as("the diagnostics MBean belongs to the runtime that was installed")
          .isEqualTo("true");
    }

    @Test
    @DisplayName("the bridge jar is removed from the temp directory once it is on the class path")
    void bridgeJarIsNotLeftOnDisk() throws Exception {
      final Path privateTmp = Files.createDirectories(tempDir.resolve("tmp"));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "sleep",
                      ChaosSelector.thread(
                          Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
                      ChaosEffect.delay(Duration.ofMillis(200)))),
              DefectProbeMain.class,
              "bridge-jars-on-disk",
              null,
              "-Djava.io.tmpdir=" + privateTmp);
      assertThat(result.longValue("bridgeJars")).isZero();
      assertThat(result.longValue("sleepMillis"))
          .as("instrumentation still works without the file")
          .isGreaterThanOrEqualTo(150L);
    }

    @Test
    @DisplayName("bridge jars older than a day, left by earlier JVMs, are swept; others stay")
    void staleBridgeJarsAreSwept() throws Exception {
      final Path privateTmp = Files.createDirectories(tempDir.resolve("tmp"));
      final Path stale =
          Files.createFile(privateTmp.resolve("macstab-chaos-bootstrap-bridge-stale.jar"));
      final Path recent =
          Files.createFile(privateTmp.resolve("macstab-chaos-bootstrap-bridge-recent.jar"));
      final Path directory =
          Files.createDirectory(privateTmp.resolve("macstab-chaos-bootstrap-bridge-dir.jar"));
      final java.nio.file.attribute.FileTime twoDaysAgo =
          java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(Duration.ofDays(2)));
      Files.setLastModifiedTime(stale, twoDaysAgo);
      Files.setLastModifiedTime(directory, twoDaysAgo);

      probe.run(
          plan(
              scenario(
                  "sleep",
                  ChaosSelector.thread(
                      Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
                  ChaosEffect.delay(Duration.ofMillis(1)))),
          DefectProbeMain.class,
          "bridge-jars-on-disk",
          null,
          "-Djava.io.tmpdir=" + privateTmp);

      assertThat(stale).as("a stale bridge jar").doesNotExist();
      assertThat(recent).as("a bridge jar of a JVM that may still run").exists();
      assertThat(directory).as("not a jar file").exists();
    }

    @Test
    @DisplayName(
        "the installer retries a failed bridge injection, rebinds to the last runtime and"
            + " uninstalls the hooks it wove")
    void installerLifecycle() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "lifecycle");

      assertThat(result.value("uninstallBeforeInstall")).isEqualTo("false");
      assertThat(result.value("injectFailure")).isEqualTo("failed to inject bootstrap bridge");
      assertThat(result.longValue("installedSleepMillis"))
          .as("the hooks of the retried install apply")
          .isGreaterThanOrEqualTo(250L);
      assertThat(result.longValue("appliedFirst")).as("the replaced runtime").isZero();
      assertThat(result.longValue("appliedSecond")).as("the runtime installed last").isEqualTo(1L);
      assertThat(result.value("uninstalled")).isEqualTo("true");
      assertThat(result.longValue("uninstalledSleepMillis"))
          .as("no hook is left after the uninstall")
          .isLessThan(250L);
      assertThat(result.value("uninstalledAgain")).isEqualTo("false");
      assertThat(result.value("sweepSkippedLogged")).isEqualTo("true");
      assertThat(result.value("wovenLogged")).isEqualTo("true");
    }

    @Test
    @DisplayName("an install that fails after installing the delegate leaves no delegate behind")
    void failedInstallClearsTheDelegate() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "session-hosts-failure");

      assertThat(result.value("installFailed")).isEqualTo("true");
      assertThat(result.value("delegateLeft")).isEqualTo("false");
    }

    @Test
    @DisplayName("a refused second copy appends no bridge jar and opens no module")
    void refusedCopyLeavesNoTrace() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "foreign-copy");

      assertThat(result.value("foreignRefused")).isEqualTo("true");
      assertThat(result.longValue("foreignAppends")).as("bridge jars appended").isZero();
      assertThat(result.longValue("foreignModuleRedefinitions")).as("modules opened").isZero();
    }

    @Test
    @DisplayName(
        "a runtime used before the install raises the dispatch depth once installed: a clock"
            + " skew does not move a scenario's start")
    void runtimeUsedBeforeInstallSeesTheDispatcher() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "dispatcher-before-install");

      assertThat(result.longValue("windowedSleepMillis"))
          .as("the activeFor window is still open")
          .isGreaterThanOrEqualTo(250L);
    }

    @Test
    @DisplayName("reports created during shutdown fail and leave no writer thread and no listener")
    void reportsDuringShutdownLeaveNothing() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "reports-during-shutdown");

      assertThat(result.value("reportsFailure")).isEqualTo("Shutdown in progress");
      assertThat(result.value("writerAlive")).isEqualTo("false");
      assertThat(result.value("listenersAdded")).isEqualTo("0");
    }

    @Test
    @DisplayName(
        "a group whose retransformation the JVM refuses fails the activation, is reported and"
            + " woven by the next activation")
    void refusedRetransformationFailsTheActivation() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "retransform-failure");

      assertThat(result.value("refusedActivation")).isEqualTo("ChaosInstrumentationException");
      assertThat(result.value("wovenAfterRefusal")).doesNotContain("THREAD_SLEEP");
      assertThat(result.value("hookFailureNamesGroup")).isEqualTo("true");
      assertThat(result.longValue("threadRetransforms")).as("tried again").isEqualTo(2L);
      assertThat(result.longValue("retriedSleepMillis")).isGreaterThanOrEqualTo(250L);
    }

    @Test
    @DisplayName("an uninstall whose retransformation the JVM refuses reports it and still clears")
    void refusedResetIsReported() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "reset-failure");

      assertThat(result.value("uninstallFailure")).startsWith("Could not transform any of");
      assertThat(result.value("uninstalledAgain")).isEqualTo("false");
    }

    @Test
    @DisplayName("the classes that escape an installation are retransformed in one call")
    void escapeesInOneCall() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "escapees");

      assertThat(result.longValue("escapeeCalls")).isEqualTo(1L);
      assertThat(result.longValue("batchCalls")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a refused escapee batch is retried one class at a time")
    void refusedEscapeeBatchIsRetriedPerClass() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "escapee-batch-failure");

      assertThat(result.longValue("batchCalls")).isEqualTo(1L);
      assertThat(result.value("socketAlone")).isEqualTo("true");
    }

    @Test
    @DisplayName(
        "premain with a startup plan whose weave the JVM refuses aborts and writes the error"
            + " status")
    void premainAbortsOnARefusedWeave() throws Exception {
      final Path reports = Files.createDirectories(tempDir.resolve("reports"));
      final Path planFile = tempDir.resolve("refused-plan.json");
      Files.writeString(
          planFile,
          com.macstab.chaos.jvm.startup.ChaosPlanMapper.write(
              plan(
                  scenario(
                      "refused-sleep",
                      ChaosSelector.thread(
                          Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
                      ChaosEffect.delay(Duration.ofMillis(1))))));

      final ChildJvmProbe.Failure failure =
          probe.runWithoutAgentExpectingFailure(
              InstallerProbeMain.class,
              "premain-retransform-failure",
              "configFile=" + planFile + ";reports=on;reportsDir=" + reports);

      assertThat(failure.exitValue()).as("premain aborts: %s", failure.output()).isNotZero();
      assertThat(failure.output())
          .contains("ChaosInstrumentationException")
          .contains("Could not install class file transformer")
          .doesNotContain("premainReturned=true");
      assertThat(Files.readString(reports.resolve(ChaosReports.STATUS_FILE)))
          .contains("state=error");
    }

    @Test
    @DisplayName("a second copy of the agent in another class loader is refused, not hijacking")
    void secondAgentCopyIsRefused() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(DefectProbeMain.class, "second-agent-copy");

      assertThat(result.value("secondRefused")).isEqualTo("true");
      assertThat(result.value("refusalMentionsClassLoader")).isEqualTo("true");
      assertThat(result.value("secondUninstalled"))
          .as("the refused copy has nothing of its own to uninstall")
          .isEqualTo("false");
      assertThat(result.longValue("firstCopySleepMillis"))
          .as("the first copy's scenarios still apply")
          .isGreaterThanOrEqualTo(250L);
    }
  }

  @Nested
  @DisplayName("I/O call-context filters")
  class IoContextFilters {

    @Test
    @DisplayName("a socket write is matched by peer CIDR and byte count")
    void socketWriteByCidrAndSize() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "big-loopback-write",
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_WRITE),
                          null,
                          "127.0.0.0/8",
                          null,
                          1000L,
                          null),
                      ChaosEffect.delay(Duration.ofMillis(1))),
                  scenario(
                      "write-elsewhere",
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_WRITE), null, "10.0.0.0/8", null, null, null),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "socket-write-sizes",
              null);
      assertThat(result.longValue("received")).isEqualTo(2010L);
      assertThat(result.longValue("matched.big-loopback-write")).isEqualTo(1L);
      assertThat(result.longValue("matched.write-elsewhere")).isZero();
    }

    @Test
    @DisplayName("a file write is rejected only under the selected directory")
    void fileWriteByPath() throws Exception {
      final Path root = Files.createDirectories(tempDir.resolve("files"));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "reject-under-a",
                      new ChaosSelector.FileIoSelector(
                          Set.of(OperationType.FILE_IO_WRITE),
                          PathPattern.under(root.resolve("a").toString()),
                          null,
                          null),
                      ChaosEffect.reject("disk full"))),
              DefectProbeMain.class,
              "file-writes-by-path",
              null,
              "-Dchaos.test.dir=" + root);
      assertThat(result.value("a")).isEqualTo("rejected");
      assertThat(result.value("b")).isEqualTo("ok");
    }

    @Test
    @DisplayName("ENOSPC on a real FileOutputStream write is a FileSystemException with the path")
    void fileWriteNoSpace() throws Exception {
      final Path root = Files.createDirectories(tempDir.resolve("errno"));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "no-space-under-a",
                      new ChaosSelector.FileIoSelector(
                          Set.of(OperationType.FILE_IO_WRITE),
                          PathPattern.under(root.resolve("a").toString()),
                          null,
                          null),
                      ChaosEffect.errno(ChaosEffect.Errno.ENOSPC))),
              DefectProbeMain.class,
              "file-writes-by-path",
              null,
              "-Dchaos.test.dir=" + root);
      assertThat(result.value("a.type")).isEqualTo("java.nio.file.FileSystemException");
      assertThat(result.value("a.message"))
          .isEqualTo(root.resolve("a").resolve("f") + ": No space left on device");
      assertThat(result.value("b")).isEqualTo("ok");
    }

    @Test
    @DisplayName(
        "clockCallSites skews System.currentTimeMillis and nanoTime in the selected package")
    void clockCallSites() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "skew-intrinsics",
                      ChaosSelector.jvmRuntime(
                          Set.of(
                              OperationType.SYSTEM_CLOCK_MILLIS, OperationType.SYSTEM_CLOCK_NANOS)),
                      ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED))),
              DefectProbeMain.class,
              "clock-call-sites",
              null,
              "-Dmacstab.chaos.clockCallSites=com.example.chaostarget.");
      assertThat(result.longValue("millisSkewSeconds")).isEqualTo(3600L);
      assertThat(result.longValue("nanosSkewSeconds")).isEqualTo(3600L);
    }

    @Test
    @DisplayName("a pool worker created inside a session does not run later tasks in it")
    void poolWorkerDoesNotInheritSession() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "pool-worker-session");
      assertThat(result.value("inSession")).isEqualTo("injected");
      assertThat(result.value("outsideSession")).isEqualTo("ok");
    }

    @Test
    @DisplayName("a session without scenarios leaves a priority pool's Comparable tasks unwrapped")
    void sessionWithoutScenariosKeepsPriorityPoolTasks() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "session-priority-pool");
      assertThat(result.value("queued")).isEqualTo("ran");
    }

    @Test
    @DisplayName("a session-scoped SCHEDULE_TICK reaches every tick of a fixed-rate task")
    void sessionScheduleTickReachesEveryTick() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "session-schedule-tick");
      assertThat(result.value("fiveRuns")).isEqualTo("true");
      assertThat(result.longValue("ticks")).isGreaterThanOrEqualTo(result.longValue("runs"));
    }

    @Test
    @DisplayName("X-Chaos-Session carries a session to the server handling the request")
    void sessionHeader() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "session-header");
      assertThat(result.value("httpClient")).isEqualTo("session|injected");
      assertThat(result.value("urlConnection")).isEqualTo("session|injected");
      assertThat(result.value("afterClose")).as("no session: no header").isEqualTo("null|ok");
    }

    @Test
    @DisplayName("a process start fails with the JDK's own message, only for the selected program")
    void processStart() throws Exception {
      final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "fork-limit",
                      ChaosSelector.process(com.macstab.chaos.jvm.api.NamePattern.exact(java)),
                      ChaosEffect.errno(ChaosEffect.Errno.EAGAIN))),
              DefectProbeMain.class,
              "process-start",
              null);
      final String failure =
          "Cannot run program \""
              + java
              + "\": error="
              + ChaosEffect.Errno.EAGAIN.code()
              + ", Resource temporarily unavailable";
      assertThat(result.value("builder")).isEqualTo(failure);
      assertThat(result.value("exec")).isEqualTo(failure);
      assertThat(result.value("other"))
          .as("not selected: the real start fails on its own")
          .startsWith("Cannot run program \"definitely-not-a-program-xyz\"");
      assertThat(result.longValue("applied.fork-limit")).isEqualTo(2L);
    }

    @Test
    @DisplayName("truncate, move and delete fail only under the selected directory")
    void fileOperationsByPath() throws Exception {
      final Path root = Files.createDirectories(tempDir.resolve("ops"));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "read-only-under-a",
                      new ChaosSelector.FileIoSelector(
                          Set.of(
                              OperationType.FILE_IO_TRUNCATE,
                              OperationType.FILE_IO_DELETE,
                              OperationType.FILE_IO_MOVE),
                          PathPattern.under(root.resolve("a").toString()),
                          null,
                          null),
                      ChaosEffect.errno(ChaosEffect.Errno.EROFS))),
              DefectProbeMain.class,
              "file-ops",
              null,
              "-Dchaos.test.dir=" + root);
      final String failure = "java.nio.file.FileSystemException";
      assertThat(result.value("a.truncate")).isEqualTo(failure);
      assertThat(result.value("a.setLength")).isEqualTo(failure);
      assertThat(result.value("a.move")).isEqualTo(failure);
      assertThat(result.value("a.renameTo"))
          .as("java.io reports failure as false")
          .isEqualTo("false");
      assertThat(result.value("a.delete")).isEqualTo(failure);
      assertThat(result.value("a.fileDelete")).isEqualTo("false");
      assertThat(result.value("a.exists")).isEqualTo("true");
      assertThat(result.value("b.truncate")).isEqualTo("ok");
      assertThat(result.value("b.setLength")).isEqualTo("ok");
      assertThat(result.value("b.move")).isEqualTo("ok");
      assertThat(result.value("b.renameTo")).isEqualTo("true");
      assertThat(result.value("b.delete")).isEqualTo("ok");
      assertThat(result.value("b.fileDelete")).isEqualTo("true");
      assertThat(result.value("b.exists")).isEqualTo("false");
      assertThat(result.value("b.deleteIfExists")).isEqualTo("false");
    }

    @Test
    @DisplayName("FILE_IO_OPEN fails every open by path: java.io as FileNotFoundException")
    void fileOpens() throws Exception {
      final Path root = Files.createDirectories(tempDir.resolve("opens"));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "emfile-under-a",
                      new ChaosSelector.FileIoSelector(
                          Set.of(OperationType.FILE_IO_OPEN),
                          PathPattern.under(root.resolve("a").toString()),
                          null,
                          null),
                      ChaosEffect.errno(ChaosEffect.Errno.EMFILE))),
              DefectProbeMain.class,
              "file-open",
              null,
              "-Dchaos.test.dir=" + root);
      final String file = root.resolve("a").resolve("f").toString();
      for (final String javaIo :
          List.of("fileInputStream", "fileOutputStream", "randomAccessFile")) {
        assertThat(result.value("a." + javaIo))
            .isEqualTo("java.io.FileNotFoundException: " + file + " (Too many open files)");
      }
      for (final String nio : List.of("newInputStream", "newBufferedReader", "fileChannel")) {
        assertThat(result.value("a." + nio))
            .isEqualTo("java.nio.file.FileSystemException: " + file + ": Too many open files");
        assertThat(result.value("b." + nio)).isEqualTo("ok");
      }
      assertThat(result.value("b.fileInputStream")).isEqualTo("ok");
      // each open is one event: six under a
      assertThat(result.longValue("applied.emfile-under-a")).isEqualTo(6L);
    }

    @Test
    @DisplayName("maps, msync, transfers and Files.copy are file operations")
    void mapsTransfersAndCopies() throws Exception {
      final Path root = Files.createDirectories(tempDir.resolve("mapped"));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "map-enomem",
                      fileOps(OperationType.FILE_IO_MAP, root, "map"),
                      ChaosEffect.errno(ChaosEffect.Errno.ENOMEM)),
                  scenario(
                      "msync-eio",
                      new ChaosSelector.FileIoSelector(
                          Set.of(OperationType.FILE_IO_SYNC), null, null, null),
                      ChaosEffect.errno(ChaosEffect.Errno.EIO)),
                  scenario(
                      "torn-transfer",
                      fileOps(OperationType.FILE_IO_WRITE, root, "torn"),
                      ChaosEffect.tornWrite()),
                  scenario(
                      "corrupt-transfer",
                      fileOps(OperationType.FILE_IO_READ, root, "corrupt"),
                      ChaosEffect.corruptRead()),
                  scenario(
                      "copy-denied",
                      fileOps(OperationType.FILE_IO_OPEN, root, "denied"),
                      ChaosEffect.errno(ChaosEffect.Errno.EACCES))),
              DefectProbeMain.class,
              "file-map-transfer",
              null,
              "-Dchaos.test.dir=" + root);
      assertThat(result.value("map")).isEqualTo("Map failed / java.lang.OutOfMemoryError");
      assertThat(result.value("force")).isEqualTo("java.io.SyncFailedException");
      assertThat(result.longValue("transferFrom")).isBetween(1L, 1023L);
      // a transfer's bytes never reach a buffer: corruptRead does not apply and counts nothing
      assertThat(result.longValue("transferTo")).isEqualTo(1024L);
      assertThat(result.value("transferIntact")).isEqualTo("true");
      assertThat(result.longValue("applied.corrupt-transfer")).isZero();
      // one transfer is one event, however the JDK carries it out
      assertThat(result.longValue("applied.torn-transfer")).isEqualTo(1L);
      assertThat(result.value("copy"))
          .startsWith("java.nio.file.AccessDeniedException: ")
          .endsWith("copy");
    }

    private ChaosSelector fileOps(
        final OperationType operation, final Path root, final String directory) {
      return new ChaosSelector.FileIoSelector(
          Set.of(operation), PathPattern.under(root.resolve(directory).toString()), null, null);
    }

    @Test
    @DisplayName("closes, move targets, half-closes, select and process-wait timeouts")
    void closesShutdownsAndWaits() throws Exception {
      final Path root = Files.createDirectories(tempDir.resolve("lifecycle"));
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(
              DefectProbeMain.class, "close-shutdown-wait", "-Dchaos.test.dir=" + root);
      // a failing close still releases the descriptor, as close(2) does
      assertThat(result.value("streamClose")).isEqualTo("java.io.IOException: Input/output error");
      assertThat(result.value("streamFdValid")).isEqualTo("false");
      assertThat(result.value("channelClose")).isEqualTo("java.nio.file.FileSystemException");
      assertThat(result.value("channelOpen")).isEqualTo("false");
      assertThat(result.value("plainClose")).isEqualTo("ok");
      // a stream and its channel are one close
      assertThat(result.longValue("applied.jvm::close-eio")).isEqualTo(2L);
      assertThat(result.value("moveInto")).isEqualTo("java.nio.file.AccessDeniedException");
      assertThat(result.value("renameInto")).isEqualTo("false");
      assertThat(result.value("renameElsewhere")).isEqualTo("true");
      assertThat(result.longValue("applied.jvm::move-into-locked")).isEqualTo(2L);
      assertThat(result.value("socketShutdown")).isEqualTo("Transport endpoint is not connected");
      assertThat(result.value("channelShutdown")).isEqualTo("Transport endpoint is not connected");
      // a stalled peer: the ready channel is not reported until the select timed out
      assertThat(result.longValue("selectReady")).isZero();
      assertThat(result.longValue("selectMillis")).isGreaterThanOrEqualTo(250L);
      assertThat(result.longValue("selectNowReady")).isEqualTo(1L);
      assertThat(result.longValue("applied.jvm::select-stalls")).isEqualTo(1L);
      // a child that hangs: the timed wait reports it has not exited
      assertThat(result.value("waitExited")).isEqualTo("false");
      assertThat(result.longValue("waitMillis")).isGreaterThanOrEqualTo(250L);
      assertThat(result.longValue("exitCode")).isZero();
    }

    @Test
    @DisplayName("ENOENT on Files.deleteIfExists returns false, as for a missing file")
    void deleteIfExistsMissing() throws Exception {
      final Path root = Files.createDirectories(tempDir.resolve("missing"));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "gone-under-a",
                      new ChaosSelector.FileIoSelector(
                          Set.of(OperationType.FILE_IO_DELETE),
                          PathPattern.under(root.resolve("a").toString()),
                          null,
                          null),
                      ChaosEffect.errno(ChaosEffect.Errno.ENOENT))),
              DefectProbeMain.class,
              "file-ops",
              null,
              "-Dchaos.test.dir=" + root);
      assertThat(result.value("a.deleteIfExists")).isEqualTo("false");
      assertThat(result.value("a.delete")).isEqualTo("java.nio.file.NoSuchFileException");
    }
  }

  @Nested
  @DisplayName("client-library hooks")
  class ClientLibraryHooks {

    private final String clientClasspath = System.getProperty("chaos.test.clientClasspath");

    private ChildJvmProbe.ProbeResult runReject(
        final String id, final ChaosSelector selector, final String mode) throws Exception {
      return probe.run(
          plan(scenario(id, selector, ChaosEffect.reject(id + " down"))),
          ClientLibraryProbeMain.class,
          mode,
          clientClasspath);
    }

    @Test
    @DisplayName("Kafka send: a rejected topic throws Kafka's TimeoutException, others are sent")
    void kafkaSend() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject(
              "kafka",
              ChaosSelector.messaging(
                  Set.of(OperationType.MESSAGE_SEND), NamePattern.exact("orders")),
              "kafka-send");
      assertThat(result.value("orders.type"))
          .isEqualTo("org.apache.kafka.common.errors.TimeoutException");
      assertThat(result.value("orders.message")).isEqualTo("kafka down");
      assertThat(result.value("audit.type"))
          .as(
              "an unmatched topic is handed to Kafka, which reports its own failures via the Future")
          .isEqualTo("sent");
      assertThat(result.longValue("matched.kafka")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Kafka poll: matched by subscribed topic")
    void kafkaPoll() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject(
              "kafka-poll",
              ChaosSelector.messaging(
                  Set.of(OperationType.MESSAGE_RECEIVE), NamePattern.exact("orders")),
              "kafka-poll");
      assertThat(result.value("poll.type"))
          .isEqualTo("org.apache.kafka.common.errors.TimeoutException");
    }

    @Test
    @DisplayName("gRPC call: a reject fails the call with UNAVAILABLE")
    void grpcCall() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject("grpc", ChaosSelector.rpc(NamePattern.exact("probe.Echo/Say")), "grpc-call");
      assertThat(result.value("call.type")).isEqualTo("io.grpc.StatusRuntimeException");
      assertThat(result.value("call.message")).isEqualTo("UNAVAILABLE: grpc down");
    }

    @Test
    @DisplayName("gRPC call: an unmatched scenario leaves the call alone")
    void grpcCallUnmatched() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject(
              "grpc-other", ChaosSelector.rpc(NamePattern.exact("probe.Echo/Other")), "grpc-call");
      assertThat(result.value("reply")).isEqualTo("echo hi");
    }

    @Test
    @DisplayName("Jedis: a rejected command throws JedisConnectionException, others run")
    void jedis() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject("redis", ChaosSelector.redis(NamePattern.exact("SET")), "jedis-set");
      assertThat(result.value("set.type"))
          .isEqualTo("redis.clients.jedis.exceptions.JedisConnectionException");
      assertThat(result.value("ping")).isEqualTo("PONG");
    }

    @Test
    @DisplayName("Lettuce: a rejected command throws RedisCommandTimeoutException")
    void lettuce() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject("lettuce", ChaosSelector.redis(NamePattern.exact("SET")), "lettuce-set");
      assertThat(result.value("set.type"))
          .isEqualTo("io.lettuce.core.RedisCommandTimeoutException");
    }

    @Test
    @DisplayName("R2DBC: a rejected execute throws R2dbcTransientResourceException")
    void r2dbc() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject(
              "r2dbc",
              new ChaosSelector.JdbcSelector(Set.of(OperationType.R2DBC_STATEMENT_EXECUTE), null),
              "r2dbc-execute");
      assertThat(result.value("execute.type"))
          .isEqualTo("io.r2dbc.spi.R2dbcTransientResourceException");
      assertThat(result.longValue("matched.r2dbc")).isEqualTo(1L);
    }

    @Test
    @DisplayName("AWS SDK: a rejected operation throws SdkClientException before any I/O")
    void awsSdk() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject(
              "aws",
              ChaosSelector.awsSdk(NamePattern.exact("s3"), NamePattern.exact("ListBuckets")),
              "aws-list-buckets");
      assertThat(result.value("list.type"))
          .isEqualTo("software.amazon.awssdk.core.exception.SdkClientException");
      assertThat(result.value("list.message")).isEqualTo("aws down");
      assertThat(result.longValue("matched.aws")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Netty: a channel flush is matched by peer CIDR")
    void nettyWrite() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "netty",
                      new ChaosSelector.NettySelector(
                          Set.of(OperationType.NETTY_CHANNEL_WRITE), "127.0.0.0/8", null),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              ClientLibraryProbeMain.class,
              "netty-write",
              clientClasspath);
      assertThat(result.longValue("received")).isEqualTo(3L);
      assertThat(result.longValue("matched.netty")).isPositive();
    }
  }

  @Nested
  @DisplayName("method selector weaving")
  class MethodSelectorWeaving {

    @Test
    @DisplayName("activating weaves the named methods, closing restores them")
    void weavesAndRestores() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "method-weaving");

      assertThat(result.value("before")).isEqualTo("hello a|42");
      assertThat(result.value("during.greet")).isEqualTo("injected");
      assertThat(result.value("during.overload"))
          .as("the signature pattern leaves the other overload alone")
          .isEqualTo("hello a hello a");
      assertThat(result.value("during.count")).isEqualTo("0");
      assertThat(result.value("during.lateCount"))
          .as("a class loaded while the scenario is active is woven at load time")
          .isEqualTo("0");
      assertThat(result.value("during.woven")).contains("METHODS");
      assertThat(result.value("after")).isEqualTo("hello a|42");
      assertThat(result.value("after.woven")).doesNotContain("METHODS");
    }

    @Test
    @DisplayName("only methods with a body of their own are evaluated: no bridge, no lambda body")
    void bridgeAndSyntheticMethodsAreNotWoven() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "method-kinds");

      assertThat(result.value("compared")).isEqualTo("-1");
      assertThat(result.value("label")).isEqualTo("area 4");
      // compareTo(Shape) and area() twice; label() and area() once. The bridge compareTo(Object)
      // and the lambda's synthetic body would each count once more.
      assertThat(result.longValue("matched.shape-methods")).isEqualTo(5L);
      assertThat(result.value("wovenLog")).isEqualTo("chaos method hooks woven for 1 selector(s)");
    }
  }

  @Nested
  @DisplayName("data-delivery effects")
  class DataDeliveryEffects {

    private ChildJvmProbe.ProbeResult run(
        final ChaosSelector selector, final ChaosEffect effect, final String mode)
        throws Exception {
      return probe.run(
          plan(scenario("effect", selector, effect)), DefectProbeMain.class, mode, null);
    }

    private ChaosSelector socketReads() {
      return new ChaosSelector.NetworkSelector(
          Set.of(OperationType.SOCKET_READ), null, "127.0.0.0/8", null, null, null);
    }

    @Test
    @DisplayName("trickle caps a socket read")
    void trickle() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run(socketReads(), ChaosEffect.trickle(10), "socket-read-effects");
      assertThat(result.longValue("read")).isEqualTo(10L);
      assertThat(result.longValue("differingBits")).isZero();
    }

    @Test
    @DisplayName("corruptRead flips exactly one bit of a socket read")
    void corruptSocketRead() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run(socketReads(), ChaosEffect.corruptRead(), "socket-read-effects");
      assertThat(result.longValue("read")).isEqualTo(64L);
      assertThat(result.longValue("differingBits")).isEqualTo(1L);
    }

    @Test
    @DisplayName("corruptRead flips exactly one bit of a file read")
    void corruptFileRead() throws Exception {
      // Scoped to the probe's temporary directory: without a path filter the scenario would
      // corrupt every file read, including the class files the JVM loads from directories.
      final Path tmp = Files.createDirectories(tempDir.resolve("corrupt-tmp"));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "effect",
                      new ChaosSelector.FileIoSelector(
                          Set.of(OperationType.FILE_IO_READ),
                          PathPattern.under(tmp.toString()),
                          null,
                          null),
                      ChaosEffect.corruptRead())),
              DefectProbeMain.class,
              "file-corrupt-read",
              null,
              "-Djava.io.tmpdir=" + tmp);
      assertThat(result.longValue("read")).isEqualTo(64L);
      assertThat(result.longValue("differingBits")).isEqualTo(1L);
      // the single-byte and the channel read paths flip one bit too
      assertThat(result.longValue("singleByteDifferingBits")).isEqualTo(1L);
      assertThat(result.longValue("channelRead")).isEqualTo(64L);
      assertThat(result.longValue("channelDifferingBits")).isEqualTo(1L);
    }

    private ChildJvmProbe.ProbeResult runInTmp(
        final String directory,
        final Set<OperationType> operations,
        final ChaosEffect effect,
        final String mode)
        throws Exception {
      final Path tmp = Files.createDirectories(tempDir.resolve(directory));
      return probe.run(
          plan(
              scenario(
                  "effect",
                  new ChaosSelector.FileIoSelector(
                      operations, PathPattern.under(tmp.toString()), null, null),
                  effect)),
          DefectProbeMain.class,
          mode,
          null,
          "-Djava.io.tmpdir=" + tmp);
    }

    @Test
    @DisplayName("tornWrite makes a FileChannel write succeed short; the caller writes the rest")
    void tornChannelWrite() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runInTmp(
              "torn",
              Set.of(OperationType.FILE_IO_WRITE),
              ChaosEffect.tornWrite(),
              "file-channel-write");
      assertThat(result.longValue("firstWrite")).isBetween(1L, 99L);
      assertThat(result.longValue("size")).isEqualTo(100L);
    }

    @Test
    @DisplayName("EIO on FileChannel.force is a SyncFailedException")
    void syncFailure() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runInTmp(
              "sync",
              Set.of(OperationType.FILE_IO_SYNC),
              ChaosEffect.errno(ChaosEffect.Errno.EIO),
              "file-channel-write");
      assertThat(result.value("force"))
          .isEqualTo("java.io.SyncFailedException: Input/output error");
      assertThat(result.longValue("size")).isEqualTo(100L);
    }

    @Test
    @DisplayName("ENOSPC on a RandomAccessFile write is a FileSystemException with the path")
    void randomAccessFileNoSpace() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runInTmp(
              "raf",
              Set.of(OperationType.FILE_IO_WRITE),
              ChaosEffect.errno(ChaosEffect.Errno.ENOSPC),
              "random-access-write");
      assertThat(result.value("raf.type")).isEqualTo("java.nio.file.FileSystemException");
      assertThat(result.value("raf.message")).endsWith(": No space left on device");
    }

    @Test
    @DisplayName("dnsOverride answers a lookup that would fail")
    void dnsOverride() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run(
              ChaosSelector.dns(
                  Set.of(OperationType.DNS_ANSWER), NamePattern.exact("chaos-probe.invalid")),
              ChaosEffect.dnsOverride("192.0.2.7"),
              "dns-lookups");
      assertThat(result.value("chaos-probe.invalid")).isEqualTo("192.0.2.7");
    }

    @Test
    @DisplayName("dnsFamily keeps only IPv4 addresses")
    void dnsFamily() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run(
              ChaosSelector.dns(Set.of(OperationType.DNS_ANSWER), NamePattern.exact("localhost")),
              ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV4),
              "dns-lookups");
      assertThat(result.value("localhost")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("dnsRewrite resolves another name instead")
    void dnsRewrite() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run(
              ChaosSelector.dns(
                  Set.of(OperationType.DNS_RESOLVE), NamePattern.exact("chaos-probe.invalid")),
              ChaosEffect.dnsRewrite("localhost"),
              "dns-lookups");
      assertThat(result.value("chaos-probe.invalid")).contains("127.0.0.1");
    }

    @Test
    @DisplayName("timeout waits for SO_TIMEOUT, then throws SocketTimeoutException")
    void timeout() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run(socketReads(), ChaosEffect.timeout(), "socket-read-effects");
      assertThat(result.value("timeout")).isEqualTo("Read timed out");
      assertThat(result.longValue("waitedMillis")).isGreaterThanOrEqualTo(190L);
      assertThat(result.longValue("applied.effect")).isEqualTo(1L);
    }

    @Test
    @DisplayName("timeout never fires on a socket without a timeout")
    void timeoutInfinite() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(scenario("effect", socketReads(), ChaosEffect.timeout())),
              DefectProbeMain.class,
              "socket-read-effects",
              null,
              "-Dchaos.test.soTimeout=0");
      assertThat(result.longValue("read")).isEqualTo(64L);
      assertThat(result.longValue("applied.effect")).as("it did not fire").isZero();
    }

    @Test
    @DisplayName("timeout on connect waits for the connect timeout, then throws")
    void connectTimeout() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run(
              ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)),
              ChaosEffect.timeout(),
              "socket-connect-timeout");
      assertThat(result.value("timeout")).isEqualTo("Connect timed out");
      assertThat(result.longValue("waitedMillis")).isGreaterThanOrEqualTo(190L);
      assertThat(result.longValue("applied.effect")).isEqualTo(1L);
    }

    @Test
    @DisplayName("connectionReset makes the peer see a reset instead of end of stream")
    void connectionReset() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run(
              ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
              ChaosEffect.connectionReset(),
              "socket-reset");
      assertThat(result.value("clientSaw")).isEqualTo("Connection reset");
    }

    @Test
    @DisplayName(
        "tornWrite sends a prefix then really resets a stream, and shortens a channel write")
    void tornSocketWrites() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "torn-stream",
                      ChaosSelector.network(Set.of(OperationType.SOCKET_WRITE)),
                      ChaosEffect.tornWrite()),
                  scenario(
                      "torn-channel",
                      ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_WRITE)),
                      ChaosEffect.tornWrite())),
              DefectProbeMain.class,
              "socket-torn-write",
              null);
      assertThat(result.value("streamWrite")).startsWith("Connection reset (chaos: torn write, ");
      final long received = result.longValue("received");
      assertThat(received).isBetween(1L, 999L);
      assertThat(result.value("streamWrite")).contains(received + " of 1000 bytes sent");
      // A real reset: the peer reads a reset, not a clean end of stream, and the socket is closed.
      assertThat(result.value("peerEnd")).isEqualTo("reset");
      assertThat(result.value("afterTorn")).isEqualTo("Socket closed");
      assertThat(result.longValue("channelFirstWrite")).isBetween(1L, 999L);
      // SOCKET_WRITE matches the stream write and, a socket write too, the channel write
      assertThat(result.longValue("applied.torn-stream")).isEqualTo(2L);
      assertThat(result.longValue("applied.torn-channel")).isEqualTo(1L);
    }

    @Test
    @DisplayName("SOCKET_BIND fails binds by local address; EMFILE fails an accept")
    void bindsAndAccepts() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "bind-in-use",
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_BIND),
                          NamePattern.any(),
                          "127.0.0.1",
                          null,
                          null,
                          null),
                      ChaosEffect.errno(ChaosEffect.Errno.EADDRINUSE)),
                  scenario(
                      "accept-emfile",
                      ChaosSelector.network(Set.of(OperationType.SOCKET_ACCEPT)),
                      ChaosEffect.errno(ChaosEffect.Errno.EMFILE))),
              DefectProbeMain.class,
              "socket-bind",
              null);
      final String inUse = "java.net.BindException: Address already in use";
      assertThat(result.value("serverSocket.127.0.0.1")).isEqualTo(inUse);
      assertThat(result.value("serverChannel.127.0.0.1")).isEqualTo(inUse);
      assertThat(result.value("serverSocket.::1")).isEqualTo("ok");
      assertThat(result.value("serverChannel.::1")).isEqualTo("ok");
      assertThat(result.value("accept")).isEqualTo("java.io.IOException: Too many open files");
    }

    @Test
    @DisplayName("EAGAIN on a platform thread start is the JDK's native-thread OutOfMemoryError")
    void threadStartEagain() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "thread-eagain");
      assertThat(result.value("during")).startsWith("unable to create native thread");
      assertThat(result.value("after")).isEqualTo("started");
    }

    @Test
    @DisplayName("UDP: dropped and corrupted datagrams, a refused send; a failed reverse lookup")
    void datagramsAndReverseLookups() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "udp-dns");
      // the first datagram was dropped on the way, the second arrived
      assertThat(result.value("udpFirst")).isEqualTo("two");
      assertThat(result.longValue("applied.jvm::udp-drop-first")).isEqualTo(1L);
      assertThat(result.longValue("applied.jvm::udp-send-once")).isEqualTo(1L);
      assertThat(result.longValue("udpSocketBits")).isEqualTo(1L);
      assertThat(result.longValue("udpChannelBits")).isEqualTo(1L);
      assertThat(result.longValue("applied.jvm::udp-corrupt")).isEqualTo(2L);
      assertThat(result.value("udpRefused")).isEqualTo("java.net.PortUnreachableException");
      // the JDK reports a failed reverse lookup by returning the IP literal
      assertThat(result.value("reverseName")).isEqualTo("127.0.0.1");
      assertThat(result.longValue("applied.jvm::reverse-fails")).isEqualTo(1L);
    }

    @Test
    @DisplayName(
        "a reject on a pool's take or poll is refused; a delay slows the worker, not kills it")
    void poolWorkQueueReject() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "pool-queue-reject");
      assertThat(result.value("queue_take.reject")).isEqualTo("ChaosValidationException");
      assertThat(result.value("queue_poll.reject")).isEqualTo("ChaosValidationException");
      assertThat(result.value("delay.ran")).isEqualTo("true");
      assertThat(result.longValue("delay.threadsCreated")).isEqualTo(1L);
      assertThat(result.value("delay.applied")).isEqualTo("true");
    }

    @Test
    @DisplayName("a rejected offer does not drop a scheduled pool's tasks: its queue is unbounded")
    void rejectedOfferKeepsScheduledTasks() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "scheduled-offer-reject");
      assertThat(result.value("scheduledResult")).isEqualTo("42");
      assertThat(result.value("fixedRateTicked")).isEqualTo("true");
      assertThat(result.longValue("applied.jvm::offer-reject")).isZero();
    }

    @Test
    @DisplayName("a suppressed timed poll waits its timeout: a polling worker does not spin")
    void suppressedTimedPollWaits() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "queue-poll-suppress");
      assertThat(result.value("timedPollNull")).isEqualTo("true");
      assertThat(result.longValue("timedPollMillis")).isGreaterThanOrEqualTo(200L);
      assertThat(result.value("interruptedPoll")).isEqualTo("InterruptedException");
      // keep-alive 50 ms over 300 ms: a handful of polls, not hundreds of thousands
      assertThat(result.longValue("poolPollsIn300ms")).isBetween(1L, 20L);
      assertThat(result.longValue("poolThreadsCreated")).isEqualTo(1L);
    }

    @Test
    @DisplayName("redirect sends a socket's and a channel's connect to another endpoint")
    void redirectsConnects() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "redirect");
      assertThat(result.value("socketPeer")).isEqualTo("true");
      assertThat(result.value("channelPeer")).isEqualTo("true");
      assertThat(result.longValue("applied.jvm::failover")).isEqualTo(2L);
    }

    @Test
    @DisplayName("socket channels are sockets: peer filters, trickle, corrupt, torn, reset")
    void socketChannels() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "socket-channel");
      // trickle(16) on a 1000-byte read, matched by the channel's peer address and port
      assertThat(result.longValue("trickledRead")).isEqualTo(16L);
      // connectionReset on the channel's close: the peer reads a reset
      assertThat(result.value("closePeer")).isEqualTo("reset");
      // corruptRead flips one bit per scattering read across both buffers
      assertThat(result.longValue("bitsFlipped")).isEqualTo(result.longValue("scatterReads"));
      assertThat(result.longValue("tornWrite")).isBetween(1L, 599L);
      assertThat(result.longValue("tornGather")).isBetween(1L, 1199L);
      assertThat(result.value("secondPeer")).isEqualTo("eof");
      // only the client's side matched: the server's stream calls have another peer port
      assertThat(result.longValue("applied.jvm::channel-trickle")).isEqualTo(1L);
      assertThat(result.longValue("applied.jvm::channel-reset")).isEqualTo(1L);
      assertThat(result.longValue("applied.jvm::channel-torn")).isEqualTo(2L);
      // a Unix-domain channel's peer is its path: the host pattern matches the client's read only
      assertThat(result.longValue("unixRead")).isEqualTo(4L);
      assertThat(result.longValue("applied.jvm::unix-trickle")).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("status and hits files")
  class Reports {

    private Map<String, String> readKeyValues(final Path file) throws Exception {
      final Map<String, String> values = new java.util.LinkedHashMap<>();
      for (final String line : Files.readAllLines(file)) {
        final int separator = line.indexOf('=');
        if (separator > 0) {
          values.put(line.substring(0, separator), line.substring(separator + 1));
        }
      }
      return values;
    }

    @Test
    @DisplayName("a loaded plan is reported as state=ok and applications land in the hits file")
    void statusOkAndHits() throws Exception {
      final Path reports = Files.createDirectories(tempDir.resolve("reports-ok"));
      final Path root = Files.createDirectories(tempDir.resolve("hits-files"));
      probe.run(
          plan(
              scenario(
                  "slow-writes",
                  new ChaosSelector.FileIoSelector(
                      Set.of(OperationType.FILE_IO_WRITE),
                      PathPattern.under(root.toString()),
                      null,
                      null),
                  ChaosEffect.delay(Duration.ofMillis(1)))),
          DefectProbeMain.class,
          "file-writes-by-path",
          null,
          "-Dchaos.test.dir=" + root,
          "-Dmacstab.chaos.reports=on",
          "-Dmacstab.chaos.reportsDir=" + reports);

      final Map<String, String> status = readKeyValues(reports.resolve(".chaos-jvm.status"));
      assertThat(status).containsEntry("state", "ok").containsEntry("scenarios", "1");
      assertThat(status.get("source")).startsWith("file:");
      assertThat(status.get("pid")).isNotBlank();

      final Path hits = reports.resolve(".chaos-jvm.hits." + status.get("pid"));
      assertThat(Files.readAllLines(hits)).containsExactly("2\tslow-writes");
    }

    @Test
    @DisplayName("a malformed plan is reported as state=error with line, column and reason")
    void statusError() throws Exception {
      final Path reports = Files.createDirectories(tempDir.resolve("reports-error"));
      final Path planFile = tempDir.resolve("broken-plan.json");
      Files.writeString(
          planFile,
          "{\n  \"metadata\": {\"name\": \"p\"},\n  \"scenarios\": [ { \"idd\": 1 } ]\n}\n");
      final Process process =
          new ProcessBuilder(
                  withCoverage(
                      java.util.List.of(
                          Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                          "-javaagent:"
                              + System.getProperty("chaos.bootstrap.agentJar")
                              + "=configFile="
                              + planFile,
                          "-Dmacstab.chaos.reports=on",
                          "-Dmacstab.chaos.reportsDir=" + reports,
                          "-cp",
                          System.getProperty("java.class.path"),
                          DefectProbeMain.class.getName(),
                          "woven-groups")))
              .redirectErrorStream(true)
              .start();
      process.getInputStream().readAllBytes();
      assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).as("the agent refuses a malformed plan").isNotZero();

      final Map<String, String> status = readKeyValues(reports.resolve(".chaos-jvm.status"));
      assertThat(status).containsEntry("state", "error").containsEntry("line", "3");
      assertThat(status.get("reason")).contains("scenarios[0]");
      assertThat(status.get("source")).isEqualTo("file:" + planFile);
    }
  }

  @Nested
  @DisplayName("session hand-off")
  class SessionHandoff {

    @Test
    @DisplayName(
        "ForkJoin tasks, started threads, virtual threads and parallel streams run in the session")
    void propagates() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "session-handoff");
      assertThat(result.value("caller")).isEqualTo("injected");
      assertThat(result.value("forkJoin"))
          .as("CompletableFuture.supplyAsync")
          .isEqualTo("injected");
      assertThat(result.value("thread")).as("Thread.start").isEqualTo("injected");
      assertThat(result.value("virtual")).as("virtual thread").isEqualTo("injected");
      assertThat(result.value("parallel"))
          .as("every parallel-stream element")
          .isEqualTo("injected");
      assertThat(result.value("outside"))
          .as("a thread started after the session closed is not in it")
          .isEqualTo("ok");
    }
  }

  @Nested
  @DisplayName("on-demand weaving")
  class OnDemandWeaving {

    @Test
    @DisplayName("an agent without scenarios weaves no hooks")
    void idleAgentWeavesNothing() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "woven-groups");
      assertThat(result.value("woven")).isEmpty();
    }

    @Test
    @DisplayName("activating a scenario weaves only the group it needs")
    void activationWeavesItsGroup() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "woven-groups-after-thread-local");
      assertThat(result.value("before")).isEmpty();
      assertThat(result.value("woven")).isEqualTo("THREAD_LOCAL");
    }

    @Test
    @DisplayName("instrumentation=eager weaves every group at start")
    void eagerWeavesEverything() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(
              DefectProbeMain.class, "woven-groups", "-Dmacstab.chaos.instrumentation=eager");
      assertThat(result.value("woven").split(",")).hasSizeGreaterThan(25).contains("TIME_API");
    }
  }

  @Nested
  @DisplayName("Completion effects")
  class CompletionEffects {

    @Test
    @DisplayName("the dependents of a future the agent completes exceptionally are evaluated")
    void dependentsOfAnInjectedCompletionAreEvaluated() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "completion-dependents");
      assertThat(result.value("completedExceptionally")).isEqualTo("true");
      assertThat(result.longValue("sleepApplied")).isEqualTo(1L);
      assertThat(result.longValue("completionMillis")).isGreaterThanOrEqualTo(150L);
      // The outer application was committed before the dependent ran, so the dependent's
      // completion finds the budget of one spent and completes normally.
      assertThat(result.longValue("onceApplied")).isEqualTo(1L);
      assertThat(result.value("other")).isEqualTo("other");
    }
  }

  @Nested
  @DisplayName("Stressor resources")
  class StressorResources {

    @Test
    @DisplayName("direct-buffer pressure above MaxDirectMemorySize is refused as unsupported")
    void directBufferAboveTheLimitIsRefused() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(
              DefectProbeMain.class, "direct-buffer-above-limit", "-XX:MaxDirectMemorySize=8m");
      assertThat(result.value("directBuffer")).isEqualTo("ChaosUnsupportedFeatureException");
      assertThat(result.value("category")).isEqualTo("UNSUPPORTED_RUNTIME");
    }
  }

  @Nested
  @DisplayName("FORK_JOIN_TASK_RUN")
  class ForkJoinTaskRun {

    @Test
    @DisplayName("is not evaluated when a virtual thread mounts on its carrier")
    void virtualThreadMountsAreNotEvaluated() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "fj",
                      ChaosSelector.executor(Set.of(OperationType.FORK_JOIN_TASK_RUN)),
                      ChaosEffect.delay(Duration.ofMillis(200)))),
              DefectProbeMain.class,
              "virtual-thread-resumes",
              null);

      assertThat(result.longValue("matched.fj")).isZero();
    }

    @Test
    @DisplayName("is evaluated for the tasks of a parallel stream")
    void parallelStreamTasksAreEvaluated() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "fj",
                      ChaosSelector.executor(Set.of(OperationType.FORK_JOIN_TASK_RUN)),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "parallel-stream",
              null);

      assertThat(result.longValue("parallelSum")).isEqualTo(4_999_950_000L);
      assertThat(result.longValue("matched.fj")).isPositive();
    }
  }

  @Nested
  @DisplayName("The agent's own work (L3)")
  class AgentOwnedWork {

    @Test
    @DisplayName(
        "a THREAD_START reject and a THREAD_SLEEP delay leave every thread stressor running")
    void threadStressorsIgnoreUserThreadScenarios() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "agent-owned-stressors");
      for (final String stressor :
          List.of(
              "keepAlive",
              "threadLeak",
              "deadlock",
              "monitorContention",
              "gcPressure",
              "referenceQueueFlood",
              "safepointStorm",
              "codeCachePressure",
              "carrierPinning")) {
        assertThat(result.value("activated." + stressor)).as(stressor).isEqualTo("ACTIVE");
      }
      // The participants sleep 10 ms before the second lock, not the 2 s the user delay adds.
      assertThat(result.longValue("deadlockMillis")).isLessThan(1_500L);
      // A collection every 20 ms, not one per 2 s.
      assertThat(result.longValue("collectionsPerSecond")).isGreaterThanOrEqualTo(5L);
    }

    @Test
    @DisplayName("a FORK_JOIN_TASK_RUN gate does not hold the thread-local-leak planting")
    void threadLocalLeakIgnoresForkJoinGate() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "thread-local-leak-fork-join-gate");
      assertThat(result.value("threadLocalLeak")).isEqualTo("activated");
      assertThat(result.longValue("threadLocalLeakMillis")).isLessThan(2_000L);
    }

    @Test
    @DisplayName("a THREAD_LOCAL_SET delay does not slow the thread-local-leak planting")
    void threadLocalLeakIgnoresThreadLocalSetDelay() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "thread-local-leak-set-delay");
      assertThat(result.longValue("threadLocalLeakMillis")).isLessThan(1_000L);
    }

    @Test
    @DisplayName("a THREAD_LOCAL_GET suppress in a session leaves the session visible")
    void sessionSurvivesThreadLocalGetSuppress() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "session-thread-local-suppress");
      assertThat(result.value("sessionVisible")).isEqualTo("true");
    }
  }

  private static ChaosPlan plan(final ChaosScenario... scenarios) {
    return new ChaosPlan(new ChaosPlan.Metadata("end-to-end", ""), null, List.of(scenarios));
  }

  private static ChaosScenario scenario(
      final String id, final ChaosSelector selector, final ChaosEffect effect) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(selector)
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  /**
   * {@code command} with the coverage agent after the java launcher (see {@link ChildJvmProbe}).
   */
  private static java.util.List<String> withCoverage(final java.util.List<String> command) {
    final java.util.List<String> withAgent = new java.util.ArrayList<>(command);
    withAgent.addAll(1, ChildJvmProbe.coverageAgent());
    return withAgent;
  }
}

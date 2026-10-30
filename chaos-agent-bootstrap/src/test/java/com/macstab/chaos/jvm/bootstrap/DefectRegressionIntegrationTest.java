package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end regression tests for defects in the 1.0.0 instrumentation. Every test runs {@link
 * DefectProbeMain} in a child JVM started with the agent jar and asserts on what the probe
 * observed, so the full advice → bridge → dispatcher path is exercised.
 */
@DisplayName("1.0.0 defect regressions (end-to-end)")
class DefectRegressionIntegrationTest {

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

      // Assert: 1900 µs truncated to 1 ms by a millisecond rewrite would leave at most 1 ms.
      assertThat(result.longValue("remainingNanos"))
          .as("remaining delay of schedule(r, 1900, MICROSECONDS)")
          .isGreaterThan(1_200_000L);
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

      // Assert: a 300 µs period yields about 1000 ticks in 300 ms; a 1 ms rewrite at most ~300.
      assertThat(result.longValue("ticks"))
          .as("ticks of scheduleAtFixedRate(r, 0, 300, MICROSECONDS) within 300 ms")
          .isGreaterThan(450L);
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
          .as("the registration is delayed once, as for EXECUTOR_SUBMIT")
          .isBetween(240L, 550L);
      assertThat(result.longValue("taskStartMillis"))
          .as("the task is not postponed a second time after the delayed registration")
          .isLessThan(560L);
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
      // public overload delegates to deflate(b, off, len, flush), which used to count twice.
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
  @DisplayName("call paths that 1.0.0 never intercepted")
  class PreviouslyUnhooked {

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
          .as("a suppressed Thread.sleep(Duration.ofSeconds(2)) returns at once")
          .isLessThan(1_000L);
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
      assertThat(result.longValue("sleepMillis"))
          .as("one set of transformers: the 300 ms delay applies once, not twice")
          .isBetween(250L, 550L);
      assertThat(result.value("mbeanReportsRetry"))
          .as("the diagnostics MBean belongs to the runtime that was installed")
          .isEqualTo("true");
    }

    @Test
    @DisplayName("a second copy of the agent in another class loader is refused, not hijacking")
    void secondAgentCopyIsRefused() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(DefectProbeMain.class, "second-agent-copy");

      assertThat(result.value("secondRefused")).isEqualTo("true");
      assertThat(result.value("refusalMentionsClassLoader")).isEqualTo("true");
      assertThat(result.longValue("firstCopySleepMillis"))
          .as("the first copy's scenarios still apply")
          .isGreaterThanOrEqualTo(250L);
    }
  }

  private static ChaosPlan plan(final ChaosScenario... scenarios) {
    return new ChaosPlan(new ChaosPlan.Metadata("defect-regression", ""), null, List.of(scenarios));
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
}

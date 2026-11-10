package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
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

    @Test
    @DisplayName("Files.newInputStream is one FILE_IO_OPEN match (newByteChannel → newFileChannel)")
    void filesNewInputStream() throws Exception {
      assertSingleFileMatch(OperationType.FILE_IO_OPEN, "files-new-input-stream");
    }

    @Test
    @DisplayName("a truncate through SeekableByteChannel is one FILE_IO_TRUNCATE match (bridge)")
    void seekableTruncate() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          assertSingleFileMatch(OperationType.FILE_IO_TRUNCATE, "seekable-truncate");
      assertThat(result.longValue("size")).isEqualTo(4L);
    }

    @Test
    @DisplayName("FileInputStream.readAllBytes is one FILE_IO_READ match")
    void fileInputStreamReadAllBytes() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          assertSingleFileMatch(OperationType.FILE_IO_READ, "fis-read-all-bytes");
      assertThat(result.longValue("length")).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("FileInputStream.readNBytes(int) is one FILE_IO_READ match")
    void fileInputStreamReadNBytes() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          assertSingleFileMatch(OperationType.FILE_IO_READ, "fis-read-n-bytes");
      assertThat(result.longValue("length")).isEqualTo(15_000L);
    }

    @Test
    @DisplayName("RandomAccessFile.readLine is one FILE_IO_READ match")
    void randomAccessFileReadLine() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          assertSingleFileMatch(OperationType.FILE_IO_READ, "raf-read-line");
      assertThat(result.value("line")).isEqualTo("0123456789");
    }

    @Test
    @DisplayName(
        "writing and reading a list of 100 elements is one OBJECT_SERIALIZE and one"
            + " OBJECT_DESERIALIZE match")
    void serializeList() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "serialize",
                      ChaosSelector.jvmRuntime(Set.of(OperationType.OBJECT_SERIALIZE)),
                      ChaosEffect.delay(Duration.ofMillis(1))),
                  scenario(
                      "deserialize",
                      ChaosSelector.jvmRuntime(Set.of(OperationType.OBJECT_DESERIALIZE)),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "serialize-list",
              null);
      assertThat(result.longValue("size")).isEqualTo(100L);
      assertThat(result.longValue("matched.serialize")).isEqualTo(1L);
      assertThat(result.longValue("matched.deserialize")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a put into a scheduled pool's queue through its bridge is one QUEUE_PUT match")
    void delayedWorkQueuePutThroughBridge() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "put",
                      new ChaosSelector.QueueSelector(
                          Set.of(OperationType.QUEUE_PUT),
                          NamePattern.exact(
                              "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "delayed-work-queue-put",
              null);
      assertThat(result.longValue("matched.put")).isEqualTo(1L);
      assertThat(result.longValue("queueSize")).isEqualTo(1L);
    }

    /**
     * One call of {@code mode} on a file under the probe's directory is one match of {@code op}.
     */
    private ChildJvmProbe.ProbeResult assertSingleFileMatch(
        final OperationType operation, final String mode) throws Exception {
      final Path root = Files.createDirectories(tempDir.resolve("single-" + mode));
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "file",
                      new ChaosSelector.FileIoSelector(
                          Set.of(operation), PathPattern.under(root.toString()), null, null),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              mode,
              null,
              "-Dchaos.test.dir=" + root);
      assertThat(result.longValue("matched.file")).as("matches of one call").isEqualTo(1L);
      assertThat(result.longValue("applied.file")).isEqualTo(1L);
      return result;
    }

    @Test
    @DisplayName(
        "an IP literal and getByName(null) are not a DNS lookup; names the JDK looks up are"
            + " evaluated")
    void dnsLiteralIsNotALookup() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "dns-down",
                      ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
                      ChaosEffect.reject("dns down"))),
              DefectProbeMain.class,
              "dns-literal",
              null);
      for (final String literal : List.of("127.0.0.1", "::1", "[::1]", "null")) {
        assertThat(result.value("resolve." + literal)).as(literal).isEqualTo("ok");
      }
      assertThat(result.value("connect")).isEqualTo("true");
      for (final String name : List.of("localhost", "1.2.3.4.5", "999.1.1.1")) {
        assertThat(result.value("resolve." + name))
            .as(name)
            .isEqualTo("java.net.UnknownHostException");
      }
      // localhost, 1.2.3.4.5 and 999.1.1.1 (all looked up by the JDK), and 0x7f.0.0.1, which the
      // JDK refuses without a lookup but which is still evaluated (documented)
      assertThat(result.longValue("matched.dns-down")).isEqualTo(4L);
    }

    @Test
    @DisplayName("the answer of an IP literal or of getAllByName(null) is not a DNS_ANSWER")
    void dnsLiteralAnswerIsNotEvaluated() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "answers",
                      ChaosSelector.dns(Set.of(OperationType.DNS_ANSWER)),
                      ChaosEffect.dnsOverride("10.9.9.9"))),
              DefectProbeMain.class,
              "dns-literal-answers",
              null);
      assertThat(result.value("answer.127.0.0.1")).isEqualTo("127.0.0.1");
      assertThat(result.value("answer.null")).isEqualTo("127.0.0.1");
      assertThat(result.longValue("matched.answers")).isZero();
    }

    @Test
    @DisplayName("closing a closed socket again is not a SOCKET_CLOSE")
    void socketDoubleClose() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "close",
                      ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "socket-double-close",
              null);
      assertThat(result.value("closed")).isEqualTo("true");
      assertThat(result.longValue("matched.close")).isEqualTo(1L);
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
    @DisplayName("a JDBC reject on Statement.execute fails with the driver's SQLException type")
    void jdbcRejectIsSqlException() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "reject",
                      ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE),
                      ChaosEffect.reject("database unavailable"))),
              DefectProbeMain.class,
              "jdbc-statement-reject",
              System.getProperty("chaos.test.jdbcClasspath"));
      assertThat(result.value("sqlException")).isEqualTo("true");
      assertThat(result.longValue("applied.reject")).isEqualTo(1L);
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
    @DisplayName("a rejected HttpURLConnection request fails with IOException")
    void httpUrlConnectionRejected() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "url-connection-rejected",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
                      ChaosEffect.reject("service down"))),
              DefectProbeMain.class,
              "http-url-connection",
              null);
      assertThat(result.value("ioException")).isEqualTo("java.io.IOException: service down");
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
    @DisplayName(
        "HttpClient.sendAsync: a reject fails the returned future, the call does not throw")
    void jdkSendAsyncReject() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "send-async",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC)),
                      ChaosEffect.reject("service down"))),
              DefectProbeMain.class,
              "jdk-send-async-reject",
              null);
      assertThat(result.values()).doesNotContainKey("call.type");
      assertThat(result.value("future.type")).isEqualTo("java.io.IOException");
      assertThat(result.value("future.message")).isEqualTo("service down");
      assertThat(result.longValue("matched.send-async")).isEqualTo(1L);
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
    @DisplayName(
        "OkHttp 4 Call.execute() is intercepted; a reject ends the call as a network failure does"
            + " (EventListener.callFailed)")
    void okHttp4Execute() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "okhttp",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
                      ChaosEffect.reject("service down"))),
              HttpLibraryProbeMain.class,
              "okhttp-execute",
              httpClasspath);
      assertThat(result.value("rejected")).isEqualTo("java.io.IOException");
      assertThat(result.value("events"))
          .as("OkHttp's noMoreExchanges ran: the listener saw the failure")
          .isEqualTo("callStart,callFailed");
      assertThat(result.longValue("matched.okhttp")).isEqualTo(1L);
    }

    @Test
    @DisplayName(
        "an OkHttp reject still ends the call (callFailed) while a REFLECTION_INVOKE reject is"
            + " active: the agent's own call of noMoreExchanges is no Method.invoke")
    void okHttpRejectBesideReflectionReject() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "okhttp",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
                      ChaosEffect.reject("service down"))),
              HttpLibraryProbeMain.class,
              "okhttp-execute-reflection-reject",
              httpClasspath);
      assertThat(result.value("rejected")).isEqualTo("java.io.IOException");
      assertThat(result.value("events"))
          .as("the reflection reject did not skip OkHttp's noMoreExchanges")
          .isEqualTo("callStart,callFailed");
      assertThat(result.longValue("matched.okhttp")).isEqualTo(1L);
      assertThat(result.longValue("reflection.matched"))
          .as("the agent's own call is invisible to REFLECTION_INVOKE")
          .isZero();
    }

    @Test
    @DisplayName(
        "OkHttp enqueue: a reject reaches Callback.onFailure on OkHttp's dispatcher thread, enqueue"
            + " does not throw, and EventListener.callFailed runs")
    void okHttpEnqueueReject() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "okhttp-async",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC)),
                      ChaosEffect.reject("service down"))),
              HttpLibraryProbeMain.class,
              "okhttp-enqueue-reject",
              httpClasspath);
      assertThat(result.values()).doesNotContainKey("call.type");
      assertThat(result.value("callback")).isEqualTo("failure");
      assertThat(result.value("callback.type")).isEqualTo("java.io.IOException");
      assertThat(result.value("callback.message")).isEqualTo("service down");
      assertThat(result.value("callback.thread")).startsWith("OkHttp");
      assertThat(result.value("events")).isEqualTo("callStart,callFailed");
      assertThat(result.longValue("matched.okhttp-async")).isEqualTo(1L);
    }

    @Test
    @DisplayName("an OkHttp request sent from an interceptor is evaluated too")
    void okHttpInterceptorNestedRequest() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "okhttp-nested",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              HttpLibraryProbeMain.class,
              "okhttp-interceptor-nested-request",
              httpClasspath);
      assertThat(result.value("nested.status")).isEqualTo("200");
      assertThat(result.value("status")).isEqualTo("200");
      assertThat(result.longValue("matched.okhttp-nested"))
          .as("outer and nested call")
          .isEqualTo(2L);
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
    @DisplayName("Apache HC5 executeOpen(null, request, null) matches on the full URL")
    void hc5ExecuteOpenWithoutHost() throws Exception {
      assertSingleMatch(
          "hc5-open",
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("http://127.0.0.1:*/probe")),
          "hc5-execute-open-null-host",
          httpClasspath,
          HttpLibraryProbeMain.class);
    }

    @Test
    @DisplayName("an Apache HC5 request sent from a response handler is evaluated too")
    void hc5HandlerNestedRequest() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "hc5-nested",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              HttpLibraryProbeMain.class,
              "hc5-handler-nested-request",
              httpClasspath);
      assertThat(result.value("status")).isEqualTo("200");
      assertThat(result.longValue("matched.hc5-nested")).as("outer and nested call").isEqualTo(2L);
    }

    @Test
    @DisplayName("Apache HC4 execute(host, request with an absolute URI) reports that URI once")
    void hc4HostAndAbsoluteUri() throws Exception {
      assertSingleMatch(
          "hc4-absolute",
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND),
              NamePattern.regex("http://127\\.0\\.0\\.1:[0-9]+/probe")),
          "hc4-host-absolute-uri",
          System.getProperty("chaos.test.coverageClasspath"),
          Hc4ProbeMain.class);
    }

    @Test
    @DisplayName("Apache HC4 DefaultHttpClient (an AbstractHttpClient) is one HTTP_CLIENT_SEND")
    void hc4DefaultHttpClient() throws Exception {
      assertSingleMatch(
          "hc4-default",
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("http://127.0.0.1:*/probe")),
          "hc4-default-http-client",
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
    @DisplayName(
        "Reactor Netty: a reject is an error signal, and each retry's subscription is evaluated")
    void reactorRetryReject() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "reactor-retry",
                      ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC)),
                      ChaosEffect.reject("service down"))),
              HttpLibraryProbeMain.class,
              "reactor-retry-reject",
              httpClasspath);
      assertThat(result.value("error.type")).isEqualTo("java.io.IOException");
      assertThat(result.value("error.message")).isEqualTo("service down");
      assertThat(result.longValue("matched.reactor-retry"))
          .as("the first subscription and two retries")
          .isEqualTo(3L);
    }

    @Test
    @DisplayName("Reactor Netty: a baseUrl and a deferred uri are matched on the full URL")
    void reactorBaseUrlDeferredUri() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "reactor-deferred",
                      ChaosSelector.httpClient(
                          Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC),
                          NamePattern.glob("http://127.0.0.1:*/probe")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              HttpLibraryProbeMain.class,
              "reactor-base-url-deferred-uri",
              httpClasspath);
      assertThat(result.value("body")).isEqualTo("ok");
      assertThat(result.longValue("matched.reactor-deferred")).isEqualTo(1L);
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
    @DisplayName(
        "startup-plan-error-status: a plan that does not parse is reported in the status file")
    void startupPlanErrorIsReported() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(DefectProbeMain.class, "startup-plan-error-status");

      assertThat(result.value("failed")).isEqualTo("true");
      assertThat(result.value("status.state")).isEqualTo("error");
      assertThat(result.value("status.scenarios")).isEqualTo("0");
      assertThat(result.longValue("status.line")).isPositive();
      assertThat(result.longValue("status.column")).isPositive();
    }

    @Test
    @DisplayName("a failed initialization is rolled back completely before a retry")
    void failedInitializationIsRolledBack() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(DefectProbeMain.class, "initialize-retry");

      assertThat(result.value("malformedFailed"))
          .as("a malformed plan fails with a ConfigLoadException without the parser's cause")
          .isEqualTo("true");
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
        "a second copy overtaken between its foreign-copy check and its install is refused; the"
            + " first copy's delegate stays")
    void overtakenCopyRefused() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "foreign-copy-race");

      assertThat(result.value("raceRefused")).isEqualTo("true");
      assertThat(result.value("delegateOfFirstCopy")).isEqualTo("true");
    }

    @Test
    @DisplayName(
        "a bridge claimed by another agent revision refuses the install before it touches the"
            + " JVM")
    void bridgeOfAnotherRevisionIsRefused() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "bridge-revision-claimed");

      assertThat(result.value("preClaimed")).isEqualTo("true");
      assertThat(result.value("installRefusal"))
          .startsWith("a chaos agent of revision 0123456789abcdef is already installed")
          .endsWith("restart with one agent version");
      assertThat(result.value("delegateLeft")).isEqualTo("false");
      assertThat(result.longValue("appends")).as("bridge jars appended").isZero();
      assertThat(result.longValue("moduleRedefinitions")).as("modules opened").isZero();
    }

    @Test
    @DisplayName(
        "a bridge on the boot class path that no agent claimed is of an unknown revision: the"
            + " install is refused")
    void unclaimedBridgeIsRefused() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "bridge-revision-unclaimed");

      assertThat(result.value("installRefusal"))
          .startsWith("a chaos agent of revision unknown is already installed");
      assertThat(result.value("delegateLeft")).isEqualTo("false");
      assertThat(result.longValue("appends")).as("bridge jars appended").isZero();
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
    @DisplayName(
        "an expiry thread started before the install is the agent's own once installed: a"
            + " THREAD_PARK delay never applies on it")
    void expiryThreadStartedBeforeInstallIsAgentWork() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "expiry-thread-before-install");

      assertThat(result.value("expiryThreadApplied")).isEqualTo("false");
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
      assertThat(result.value("escapeeFailureRecorded"))
          .as("the refused escapee is listed in hookFailures")
          .isEqualTo("true");
    }

    @Test
    @DisplayName("the escapees are retransformed also when a later hook group of the weave fails")
    void escapeesAfterAPartialWeave() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(InstallerProbeMain.class, "escapees-partial-weave");

      assertThat(result.value("partialActivation")).isEqualTo("ChaosInstrumentationException");
      assertThat(result.value("wovenAfterFailure")).contains("DATAGRAM");
      assertThat(result.longValue("escapeeCalls")).isPositive();
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
    @DisplayName("ENOSPC on a real FileOutputStream write is an IOException with the system's text")
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
      // a write of an open file reports the error as IOUtil does: no path
      assertThat(result.value("a.type")).isEqualTo("java.io.IOException");
      assertThat(result.value("a.message")).isEqualTo("No space left on device");
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
                          Set.of(OperationType.FILE_IO_DELETE, OperationType.FILE_IO_MOVE),
                          PathPattern.under(root.resolve("a").toString()),
                          null,
                          null),
                      ChaosEffect.errno(ChaosEffect.Errno.EROFS)),
                  // ftruncate of an open file never reports EROFS; a failing disk is EIO
                  scenario(
                      "failing-disk-under-a",
                      new ChaosSelector.FileIoSelector(
                          Set.of(OperationType.FILE_IO_TRUNCATE),
                          PathPattern.under(root.resolve("a").toString()),
                          null,
                          null),
                      ChaosEffect.errno(ChaosEffect.Errno.EIO))),
              DefectProbeMain.class,
              "file-ops",
              null,
              "-Dchaos.test.dir=" + root);
      final String failure = "java.nio.file.FileSystemException";
      assertThat(result.value("a.truncate")).isEqualTo("java.io.IOException");
      assertThat(result.value("a.setLength")).isEqualTo("java.io.IOException");
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
    @DisplayName("a child is waited for under the program it was started as, also once reaped")
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void processWaitMatchesTheStartedProgram() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "process-wait-name");
      assertThat(result.value("childAlive")).as("reaped before the wait").isEqualTo("false");
      assertThat(result.longValue("exitCode")).isZero();
      assertThat(result.longValue("matched.process-sh")).as("the start and the wait").isEqualTo(2L);
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
      assertThat(result.value("channelClose")).isEqualTo("java.io.IOException");
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
    @DisplayName(
        "Kafka send: a rejected topic fails the send's future, callback and interceptor with"
            + " Kafka's TimeoutException; partitionsFor is not a send")
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
      assertThat(result.longValue("orders.callbacks")).isEqualTo(1L);
      assertThat(result.longValue("orders.interceptorErrors")).isEqualTo(1L);
      assertThat(result.value("audit.type"))
          .as("an unmatched topic is handed to Kafka, which times out waiting for metadata")
          .isEqualTo("org.apache.kafka.common.errors.TimeoutException");
      assertThat(result.value("audit.message")).isNotEqualTo("kafka down");
      assertThat(result.value("partitions.message")).isNotEqualTo("kafka down");
      assertThat(result.longValue("matched.kafka"))
          .as("the matched send only; partitionsFor waits for metadata outside a send")
          .isEqualTo(1L);
    }

    @Test
    @DisplayName(
        "Kafka in a loader that reaches only the platform and boot loaders: the hook links and"
            + " matches")
    void kafkaInIsolatedLoader() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "kafka",
                      ChaosSelector.messaging(
                          Set.of(OperationType.MESSAGE_SEND), NamePattern.exact("orders")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              "kafka-isolated-loader",
              null,
              "-Dchaos.test.isolatedClasspath=" + clientClasspath);
      assertThat(result.value("loader.isolated")).isEqualTo("true");
      assertThat(result.value("orders.type"))
          .as("the advice inlined into KafkaProducer.send resolves every agent class it names")
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
    @DisplayName("gRPC call: a unary call is one RPC_CALL")
    void grpcUnaryOnce() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "grpc-once",
                      ChaosSelector.rpc(NamePattern.exact("probe.Echo/Say")),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              ClientLibraryProbeMain.class,
              "grpc-call",
              clientClasspath);
      assertThat(result.value("reply")).isEqualTo("echo hi");
      assertThat(result.longValue("matched.grpc-once")).isEqualTo(1L);
    }

    @Test
    @DisplayName(
        "gRPC calls started before the name resolves: rejected with UNAVAILABLE through the"
            + " call, no stream opened, and the channel keeps working")
    void grpcRejectBeforeResolution() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  ChaosScenario.builder("grpc-late")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.rpc(NamePattern.exact("probe.Echo/Say")))
                      .effect(ChaosEffect.reject("grpc down"))
                      .activationPolicy(ActivationPolicy.builder().maxApplications(2L).build())
                      .build()),
              ClientLibraryProbeMain.class,
              "grpc-reject-before-resolution",
              clientClasspath);
      assertThat(result.value("blocking.code")).isEqualTo("UNAVAILABLE");
      assertThat(result.value("blocking.description")).isEqualTo("grpc down");
      assertThat(result.value("future.code")).isEqualTo("UNAVAILABLE");
      assertThat(result.value("later.reply")).isEqualTo("echo hi");
      assertThat(result.longValue("received")).as("calls that reached the server").isEqualTo(1L);
      assertThat(result.longValue("applied.grpc-late")).isEqualTo(2L);
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
    @DisplayName("Jedis: a pool's connection set-up and validation PING are not REDIS_COMMAND")
    void jedisPooledSetUpIsNotACommand() throws Exception {
      for (final String mode : List.of("jedis-pooled-set", "jedis-pool-set")) {
        final ChildJvmProbe.ProbeResult result =
            probe.run(
                plan(
                    scenario(
                        "redis",
                        ChaosSelector.redis(NamePattern.any()),
                        ChaosEffect.delay(Duration.ofMillis(1)))),
                ClientLibraryProbeMain.class,
                mode,
                clientClasspath);
        assertThat(result.value("set")).isEqualTo("OK");
        assertThat(result.longValue("matched.redis"))
            .as("commands of one set (%s)", mode)
            .isEqualTo(1L);
      }
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
    @DisplayName(
        "Lettuce: a rejected asynchronous command and a rejected batch fail their futures, the"
            + " batch evaluated once")
    void lettuceAsyncReject() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject(
              "lettuce", ChaosSelector.redis(NamePattern.exact("SET")), "lettuce-async-reject");
      assertThat(result.values()).doesNotContainKeys("single.call.type", "batch.call.type");
      for (final String command : List.of("single", "batch.0", "batch.1")) {
        assertThat(result.value(command + ".type"))
            .as(command)
            .isEqualTo("io.lettuce.core.RedisCommandTimeoutException");
        assertThat(result.value(command + ".message")).as(command).isEqualTo("lettuce down");
      }
      assertThat(result.longValue("matched.lettuce")).as("one command and one batch").isEqualTo(2L);
    }

    @Test
    @DisplayName(
        "R2DBC: a rejected execute signals R2dbcTransientResourceException through its publisher")
    void r2dbc() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject(
              "r2dbc",
              new ChaosSelector.JdbcSelector(Set.of(OperationType.R2DBC_STATEMENT_EXECUTE), null),
              "r2dbc-execute");
      assertThat(result.values())
          .as("execute() returns its publisher; the failure is signalled through it")
          .doesNotContainKey("call.type");
      assertThat(result.value("execute.type"))
          .isEqualTo("io.r2dbc.spi.R2dbcTransientResourceException");
      assertThat(result.value("execute.message")).isEqualTo("r2dbc down");
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
    @DisplayName(
        "Netty: a rejected write fails its future with an IOException and closes a socket"
            + " channel; a datagram channel is not evaluated and stays open; an empty flush is not"
            + " evaluated")
    void nettyWriteReject() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runReject(
              "netty",
              new ChaosSelector.NettySelector(
                  Set.of(OperationType.NETTY_CHANNEL_WRITE), "127.0.0.0/8", null),
              "netty-write-reject");
      assertThat(result.value("socket.done")).isEqualTo("true");
      assertThat(result.value("socket.type")).isEqualTo("java.io.IOException");
      assertThat(result.value("socket.message")).isEqualTo("netty down");
      assertThat(result.value("socket.closed")).isEqualTo("true");
      assertThat(result.value("datagram.done")).isEqualTo("true");
      assertThat(result.values())
          .as("a real datagram write error fails one message; a reject must not close the channel")
          .doesNotContainKey("datagram.type");
      assertThat(result.value("datagram.closed")).isEqualTo("false");
      assertThat(result.longValue("matched.netty"))
          .as("one evaluation for the socket's buffer, none for the empty flush or the datagram")
          .isEqualTo(1L);
      assertThat(result.output()).doesNotContain("Unexpected exception in the selector loop");
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
    @DisplayName("method-weaving-jdk: a JDK class is never woven, an application class is")
    void jdkClassesAreNotWoven() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "method-weaving-jdk");
      assertThat(result.value("map")).isEqualTo("v");
      assertThat(result.longValue("matched.jdk")).as("HashMap.get").isZero();
      assertThat(result.longValue("matched.user")).as("UserMap.get").isEqualTo(1L);
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
      // a compound read is one read: one bit of all the bytes it returns
      assertThat(result.longValue("readAllBytesDifferingBits")).isEqualTo(1L);
      assertThat(result.longValue("readLineDifferingBits")).isEqualTo(1L);
      assertThat(result.longValue("channelRead")).isEqualTo(64L);
      assertThat(result.longValue("channelDifferingBits")).isEqualTo(1L);
      // a channel read into a read-only buffer fails in the JDK and counts no application
      assertThat(result.value("readOnlyChannelRead")).isEqualTo("IllegalArgumentException");
      assertThat(result.longValue("readOnlyChannelApplied")).isZero();
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
    @DisplayName("ENOSPC on a RandomAccessFile write is an IOException with the system's text")
    void randomAccessFileNoSpace() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          runInTmp(
              "raf",
              Set.of(OperationType.FILE_IO_WRITE),
              ChaosEffect.errno(ChaosEffect.Errno.ENOSPC),
              "random-access-write");
      assertThat(result.value("raf.type")).isEqualTo("java.io.IOException");
      assertThat(result.value("raf.message")).isEqualTo("No space left on device");
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
    @DisplayName("dnsFamily keeps only IPv4 addresses of a name that has both")
    void dnsFamily() throws Exception {
      final ChildJvmProbe.ProbeResult result = dualStackLookups("dual.test");
      assertThat(result.value("dual.test")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("without dnsFamily the dual-stack name resolves to both families")
    void dnsFamilyControl() throws Exception {
      final ChildJvmProbe.ProbeResult result = dualStackLookups("other.test");
      assertThat(result.value("dual.test").split(","))
          .containsExactlyInAnyOrder("127.0.0.1", "0:0:0:0:0:0:0:1");
    }

    /**
     * Runs the lookups with a hosts file that gives {@code dual.test} an IPv4 and an IPv6 address,
     * and a dnsFamily(IPV4) scenario on {@code name}. The DNS hook sits above the hosts-file
     * resolver, so the answer the scenario filters is the one the file gives.
     */
    private ChildJvmProbe.ProbeResult dualStackLookups(final String name) throws Exception {
      final Path hosts = tempDir.resolve("hosts-" + name);
      Files.writeString(hosts, "127.0.0.1 dual.test\n::1 dual.test\n");
      return probe.run(
          plan(
              scenario(
                  "effect",
                  ChaosSelector.dns(Set.of(OperationType.DNS_ANSWER), NamePattern.exact(name)),
                  ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV4))),
          DefectProbeMain.class,
          "dns-lookups",
          null,
          "-Djdk.net.hosts.file=" + hosts);
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
      // The other address and the accept use the IPv6 loopback, which not every host has.
      org.junit.jupiter.api.Assumptions.assumeTrue(
          ipv6LoopbackAvailable(), "this host has no IPv6 loopback");
      assertThat(result.value("serverSocket.::1")).isEqualTo("ok");
      assertThat(result.value("serverChannel.::1")).isEqualTo("ok");
      assertThat(result.value("accept")).isEqualTo("java.io.IOException: Too many open files");
    }

    private static boolean ipv6LoopbackAvailable() {
      try (java.net.ServerSocket server =
          new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("::1"))) {
        return server.isBound();
      } catch (final java.io.IOException unavailable) {
        return false;
      }
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
    @DisplayName(
        "a reject raises the real call's exception, thrown from the intercepted method itself")
    void rejectRaisesTheRealException() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "reject-types");
      assertSoftly(
          softly -> {
            softly
                .assertThat(result.value("dnsAnswer.type"))
                .isEqualTo("java.net.UnknownHostException");
            softly
                .assertThat(result.value("dnsAnswer.top"))
                .isEqualTo("java.net.InetAddress.getAllByName");
            softly
                .assertThat(result.value("reflection.type"))
                .isEqualTo("java.lang.reflect.InvocationTargetException");
            softly
                .assertThat(result.value("reflection.top"))
                .isEqualTo("java.lang.reflect.Method.invoke");
            softly
                .assertThat(result.value("reflection.cause"))
                .isEqualTo("java.lang.RuntimeException");
            softly
                .assertThat(result.value("scheduleSubmit.type"))
                .isEqualTo("java.util.concurrent.RejectedExecutionException");
            softly
                .assertThat(result.value("scheduleSubmit.top"))
                .isEqualTo("java.util.concurrent.ScheduledThreadPoolExecutor.schedule");
            softly
                .assertThat(result.value("awaitTermination.type"))
                .isEqualTo("java.lang.InterruptedException");
            softly
                .assertThat(result.value("awaitTermination.top"))
                .isEqualTo("java.util.concurrent.ThreadPoolExecutor.awaitTermination");
            softly
                .assertThat(result.value("queuePut.type"))
                .isEqualTo("java.lang.InterruptedException");
            softly
                .assertThat(result.value("queuePut.top"))
                .isEqualTo("java.util.concurrent.ArrayBlockingQueue.put");
            softly
                .assertThat(result.value("threadStart.type"))
                .isEqualTo("java.lang.OutOfMemoryError");
            softly.assertThat(result.value("threadStart.top")).isEqualTo("java.lang.Thread.start");
            softly
                .assertThat(result.value("shutdownHook.type"))
                .isEqualTo("java.lang.IllegalStateException");
            softly
                .assertThat(result.value("shutdownHook.top"))
                .isEqualTo("java.lang.Runtime.addShutdownHook");
            softly.assertThat(result.value("select.type")).isEqualTo("java.io.IOException");
            softly
                .assertThat(result.value("select.top"))
                .isEqualTo("sun.nio.ch.SelectorImpl.select");
            softly
                .assertThat(result.value("nioOpen.type"))
                .isEqualTo("java.nio.file.FileSystemException");
            softly.assertThat(result.value("nioOpen.top")).doesNotStartWith("com.macstab.");
            softly
                .assertThat(result.value("channelWriteEnospc.type"))
                .isEqualTo("java.io.IOException");
            softly
                .assertThat(result.value("channelWriteEnospc.top"))
                .isEqualTo("sun.nio.ch.FileChannelImpl.write");
            softly
                .assertThat(result.value("socketRead.type"))
                .isEqualTo("java.net.SocketException");
            softly.assertThat(result.value("socketRead.top")).doesNotStartWith("com.macstab.");
            softly
                .assertThat(result.value("socketWrite.type"))
                .isEqualTo("java.net.SocketException");
            softly.assertThat(result.value("socketWrite.top")).doesNotStartWith("com.macstab.");
            softly
                .assertThat(result.value("zipInflate.type"))
                .isEqualTo("java.util.zip.DataFormatException");
            softly
                .assertThat(result.value("zipInflate.top"))
                .isEqualTo("java.util.zip.Inflater.inflate");
          });
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
    @DisplayName("a redirect of every connect leaves a Unix-domain connect alone")
    void redirectSkipsUnixDomainConnects() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "redirect-unix-domain");
      assertThat(result.value("unixConnect")).isEqualTo("ok");
      assertThat(result.value("tcpRedirected")).isEqualTo("true");
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
      return ChildJvmProbe.parse(Files.readAllLines(file));
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
      final ChildJvmProbe.BoundedRun run =
          ChildJvmProbe.runBounded(
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
                      "woven-groups")),
              tempDir.resolve("broken-plan.out"),
              java.time.Duration.ofSeconds(60));
      assertThat(run.finished()).as("probe JVM should finish: " + run.output()).isTrue();
      assertThat(run.exitValue()).as("the agent refuses a malformed plan").isNotZero();

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
    @DisplayName("a class whose superclass is missing is no target: no hook failure, no WARNING")
    void missingSuperclassIsNoTarget() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(
              DefectProbeMain.class, "missing-superclass", "-Dmacstab.chaos.instrumentation=eager");
      assertThat(result.value("loaded")).isEqualTo("java.lang.NoClassDefFoundError");
      assertThat(result.value("orphanFailures")).isEmpty();
      assertThat(result.value("orphanWarnings")).isEmpty();
    }

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
      // The participants sleep 10 ms before the second lock, not the 30 s the user delay adds.
      assertThat(result.value("deadlocked"))
          .as("deadlocked within 10 s, after %d ms", result.longValue("deadlockMillis"))
          .isEqualTo("true");
      // A collection every 20 ms, not one per 30 s.
      assertThat(result.longValue("collectionsPerSecond")).isGreaterThanOrEqualTo(5L);
    }

    @Test
    @DisplayName(
        "a suppressed tick of a one-shot Callable cancels its future, as the JDK cancels a task")
    void suppressedCallableTickCancelsItsFuture() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "scheduled-callable-suppress");
      assertThat(result.value("delayed")).isEqualTo("cancelled");
      assertThat(result.value("immediate")).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("a THREAD_SLEEP delay on the Finalizer thread never matches the backlog's sleeps")
    void finalizerBacklogSleepIsAgentWork() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "finalizer-backlog-sleep");
      assertThat(result.value("finalizable")).isEqualTo("ACTIVE");
      assertThat(result.longValue("finalizerSleepsMatched")).isZero();
    }

    @Test
    @DisplayName("the agent's WARNING for a suppressed tick is not seen by FILE_IO_WRITE")
    void tickWarningIsAgentWork() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "tick-warning-to-log-file");
      assertThat(result.longValue("suppressedTicks")).isGreaterThanOrEqualTo(6L);
      assertThat(result.longValue("rise"))
          .as("FILE_IO_WRITE matches between the first and the sixth suppressed tick")
          .isZero();
    }

    @Test
    @DisplayName("a FILE_IO_READ reject does not fail the plan file's reloads")
    void planReloadIgnoresFileReadReject() throws Exception {
      final String mode = "plan-reload-under-file-reject";
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              plan(
                  scenario(
                      "initial",
                      ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)),
                      ChaosEffect.delay(Duration.ofMillis(1)))),
              DefectProbeMain.class,
              mode,
              null,
              "-Dmacstab.chaos.configWatchInterval=50",
              "-Dchaos.test.planFile=" + tempDir.resolve(mode + "-plan.json"));
      assertThat(result.value("reloaded")).isEqualTo("ACTIVE");
      assertThat(result.output()).doesNotContain("config reload failed");
    }

    @Test
    @DisplayName("a periodic SCHEDULE_TICK suppress does not stop the plan file's reloads")
    void planReloadIgnoresTickSuppress() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          watchedPlanReload(
              scenario(
                  "tick-suppress",
                  new ChaosSelector.SchedulingSelector(
                      Set.of(OperationType.SCHEDULE_TICK), NamePattern.any(), true),
                  ChaosEffect.suppress()));
      assertThat(result.value("reloaded")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a THREAD_START reject on chaos- names fails neither the start nor the reloads")
    void planWatchIgnoresThreadStartReject() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          watchedPlanReload(
              scenario(
                  "chaos-thread-reject",
                  new ChaosSelector.ThreadSelector(
                      Set.of(OperationType.THREAD_START),
                      ChaosSelector.ThreadKind.ANY,
                      NamePattern.prefix("chaos-"),
                      null),
                  ChaosEffect.reject("no threads")));
      assertThat(result.value("reloaded")).isEqualTo("ACTIVE");
    }

    /** Runs {@code plan-reload} with {@code initial} as the watched startup plan. */
    private ChildJvmProbe.ProbeResult watchedPlanReload(final ChaosScenario initial)
        throws Exception {
      final String mode = "plan-reload";
      return probe.run(
          plan(initial),
          DefectProbeMain.class,
          mode,
          null,
          "-Dmacstab.chaos.configWatchInterval=50",
          "-Dchaos.test.planFile=" + tempDir.resolve(mode + "-plan.json"));
    }

    @Test
    @DisplayName("a FORK_JOIN_TASK_RUN gate does not hold the thread-local-leak planting")
    void threadLocalLeakIgnoresForkJoinGate() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "thread-local-leak-fork-join-gate");
      assertThat(result.value("threadLocalLeak"))
          .as(
              "activated while the gate is closed, after %d ms",
              result.longValue("threadLocalLeakMillis"))
          .isEqualTo("activated");
    }

    @Test
    @DisplayName("a THREAD_LOCAL_SET delay does not slow the thread-local-leak planting")
    void threadLocalLeakIgnoresThreadLocalSetDelay() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "thread-local-leak-set-delay");
      // Bounded well apart from the 30 s one delayed set would add.
      assertThat(result.longValue("threadLocalLeakMillis")).isLessThan(10_000L);
    }

    @Test
    @DisplayName("a THREAD_LOCAL_GET suppress in a session leaves the session visible")
    void sessionSurvivesThreadLocalGetSuppress() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "session-thread-local-suppress");
      assertThat(result.value("sessionVisible")).isEqualTo("true");
    }
  }

  @Nested
  @DisplayName("the JDK's own use of a primitive is not a target (L3a)")
  class JdkOwnUse {

    private ChildJvmProbe.ProbeResult run(final String mode, final String... jvmArgs)
        throws Exception {
      return probe.runWithoutPlan(JdkOwnUseProbeMain.class, mode, jvmArgs);
    }

    @Test
    @DisplayName(
        "file-read-reject-loads-classes: classes load and UUIDs are drawn, a user read fails")
    void fileReadRejectLoadsClasses() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("file-read-reject");
      assertSoftly(
          softly -> {
            softly.assertThat(result.value("classLoaded")).isEqualTo("true");
            softly.assertThat(result.value("uuid")).isEqualTo("36");
            softly.assertThat(result.value("userRead")).endsWith("Exception");
          });
    }

    @Test
    @DisplayName("zip-inflate-reject-loads-classes: a deflated jar class loads, a user GZIP fails")
    void zipInflateRejectLoadsClasses() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("zip-inflate-reject");
      assertSoftly(
          softly -> {
            softly.assertThat(result.value("classLoaded")).isEqualTo("true");
            softly.assertThat(result.value("gzip")).isEqualTo("java.util.zip.ZipException");
          });
    }

    @Test
    @DisplayName("nio-select-reject-httpclient: the HttpClient selector runs, a user select fails")
    void nioSelectRejectSparesTheHttpClient() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("nio-select-reject");
      assertSoftly(
          softly -> {
            softly.assertThat(result.value("httpClient")).isEqualTo("200");
            softly.assertThat(result.value("userSelect")).isEqualTo("java.io.IOException");
          });
    }

    @Test
    @DisplayName("async-suppress-or-timeout: orTimeout still times out")
    void asyncSuppressKeepsOrTimeout() throws Exception {
      assertThat(run("async-suppress-or-timeout").value("orTimeout"))
          .isEqualTo("java.util.concurrent.TimeoutException");
    }

    @Test
    @EnabledOnJre(JRE.JAVA_21)
    @DisplayName("schedule-suppress-or-timeout: a SCHEDULE_SUBMIT suppress keeps orTimeout")
    void scheduleSuppressKeepsOrTimeout() throws Exception {
      assertThat(run("schedule-suppress-or-timeout").value("orTimeout"))
          .isEqualTo("java.util.concurrent.TimeoutException");
    }

    @Test
    @EnabledOnJre(JRE.JAVA_21)
    @DisplayName("schedule-tick-suppress-or-timeout: a SCHEDULE_TICK suppress keeps orTimeout")
    void scheduleTickSuppressKeepsOrTimeout() throws Exception {
      assertThat(run("schedule-tick-suppress-or-timeout").value("orTimeout"))
          .isEqualTo("java.util.concurrent.TimeoutException");
    }

    @Test
    @DisplayName(
        "delayed-executor-still-evaluated: delayedExecutor is evaluated once, the orTimeout timer"
            + " not")
    void delayedExecutorStaysEvaluated() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("delayed-executor");
      assertSoftly(
          softly -> {
            softly
                .assertThat(result.value("orTimeout"))
                .isEqualTo("java.util.concurrent.TimeoutException");
            softly.assertThat(result.value("delayedRan")).isEqualTo("true");
            // 21: SCHEDULE_SUBMIT of the Delayer; 25: the FORK_JOIN_TASK_RUN of its timer task.
            softly
                .assertThat(result.longValue("matched.sched") + result.longValue("matched.fj"))
                .isEqualTo(1L);
          });
    }

    @Test
    @DisplayName(
        "timeout-marker-cleared: a JDK timer on a done future leaves no marker for the next"
            + " completion")
    void timeoutMarkerIsCleared() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run("timeout-marker-cleared", "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
      assertThat(result.value("userComplete")).isEqualTo("true");
      assertThat(result.longValue("matched")).isEqualTo(1L);
    }

    @Test
    @DisplayName("jmx-reject-agent-mbean: the agent's MBean answers, the platform's do not")
    void jmxRejectSparesTheAgentMBean() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("jmx-reject");
      assertSoftly(
          softly -> {
            softly.assertThat(result.value("agentInvoke")).isEqualTo("true");
            softly.assertThat(result.value("userGetAttribute")).endsWith("Exception");
            softly
                .assertThat(result.value("userGetAttributes"))
                .as("getAttributes(ObjectName, String[]) is JMX_GET_ATTR too")
                .endsWith("Exception");
          });
    }

    @Test
    @DisplayName("thread-local-suppress-rrwl: a read-write lock read by two threads keeps working")
    void threadLocalSuppressSparesTheReadWriteLock() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("thread-local-suppress");
      assertThat(result.value("rrwl")).isEqualTo("ok");
      assertThat(result.value("plain")).isEqualTo("null");
    }

    @Test
    @DisplayName("thread-local-with-initial-still-evaluated: ThreadLocal.withInitial is suppressed")
    void threadLocalWithInitialStaysEvaluated() throws Exception {
      assertThat(run("thread-local-suppress").value("withInitial")).isEqualTo("null");
    }

    @Test
    @DisplayName("reflection-reject-serialization: writeObject runs, a user Method.invoke fails")
    void reflectionRejectSparesSerialization() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("reflection-reject-serialization");
      assertThat(result.value("serialize")).isEqualTo("true");
      assertThat(result.value("userInvoke"))
          .isEqualTo("java.lang.reflect.InvocationTargetException");
    }

    @Test
    @EnabledOnJre(JRE.JAVA_21)
    @DisplayName("direct-buffer-reject-socket-read: a socket read still gets its temporary buffer")
    void directBufferRejectSparesSocketReads() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("direct-buffer-reject-socket-read");
      assertThat(result.value("socketRead")).isEqualTo("4");
      assertThat(result.value("userAllocate")).isEqualTo("java.lang.OutOfMemoryError");
    }

    @Test
    @DisplayName("gc-suppress-bits: direct memory is reclaimed, a user System.gc() is evaluated")
    void gcSuppressSparesDirectMemoryReservation() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          run("gc-suppress-bits", "-XX:MaxDirectMemorySize=16m");
      assertThat(result.value("allocations")).isEqualTo("64");
      assertThat(result.longValue("matched")).isEqualTo(1L);
    }

    @Test
    @DisplayName("the class-loading bracket is woven with FILE_IO and ZIP only (ADR 0002)")
    void classLoadingBracketIsWovenOnDemand() throws Exception {
      final ChildJvmProbe.ProbeResult fileIo = run("woven-after-file-io");
      assertThat(fileIo.value("woven")).contains("FILE_IO").contains("JDK_INTERNAL_CLASS_LOAD");
      final ChildJvmProbe.ProbeResult threadLocal = run("woven-after-thread-local");
      assertThat(threadLocal.value("woven")).isEqualTo("THREAD_LOCAL");
      assertThat(threadLocal.value("matchedHooks")).doesNotContain("JdkInternalScopeAdvice");
    }
  }

  @Nested
  @DisplayName("every funnel of an operation reaches its hook")
  class Funnels {

    @Test
    @DisplayName(
        "socket-adaptor: a socket channel's adaptor connects, reads and writes as SOCKET_*")
    void socketAdaptor() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "socket-adaptor");
      assertSoftly(
          softly -> {
            softly.assertThat(result.value("read")).isEqualTo("true");
            softly.assertThat(result.longValue("connect")).as("SOCKET_CONNECT").isEqualTo(1L);
            softly.assertThat(result.longValue("readMatched")).as("SOCKET_READ").isEqualTo(1L);
            softly.assertThat(result.longValue("write")).as("SOCKET_WRITE").isEqualTo(1L);
          });
    }

    @Test
    @DisplayName("ssl-server-accept: SSLServerSocket.accept is one SOCKET_ACCEPT")
    void sslServerAccept() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "ssl-server-accept");
      assertThat(result.value("accepted")).isEqualTo("SSLSocketImpl");
      assertThat(result.longValue("accept")).isEqualTo(1L);
    }

    @Test
    @DisplayName("adaptor-accept-timeout: an adaptor accept is one SOCKET_ACCEPT, timed or not")
    void adaptorAcceptTimeout() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "adaptor-accept-timeout");
      assertThat(result.longValue("timed")).as("SO_TIMEOUT 5 s").isEqualTo(1L);
      assertThat(result.longValue("untimed")).as("SO_TIMEOUT 0").isEqualTo(1L);
    }

    @Test
    @DisplayName("jdbc-batch: executeBatch and executeLargeBatch are one JDBC_STATEMENT_EXECUTE")
    void jdbcBatch() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlanOnClasspath(
              DefectProbeMain.class, "jdbc-batch", System.getProperty("chaos.test.jdbcClasspath"));
      assertSoftly(
          softly -> {
            softly.assertThat(result.value("statementRows")).isEqualTo("2");
            softly.assertThat(result.longValue("statementBatch")).isEqualTo(1L);
            softly.assertThat(result.value("preparedRows")).isEqualTo("2");
            softly.assertThat(result.longValue("preparedBatch")).isEqualTo(1L);
            softly.assertThat(result.value("largeRows")).isEqualTo("2");
            softly.assertThat(result.longValue("largeBatch")).isEqualTo(1L);
          });
    }

    @Test
    @DisplayName(
        "executor-submit-kinds: STPE and virtual-thread executor execute/submit are one"
            + " EXECUTOR_SUBMIT, no SCHEDULE_SUBMIT")
    void executorSubmitKinds() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "executor-submit-kinds");
      assertSoftly(
          softly -> {
            for (final String call :
                List.of(
                    "stpeSubmit",
                    "stpeSubmitCallable",
                    "stpeExecute",
                    "vtSubmit",
                    "vtSubmitCallable",
                    "vtExecute")) {
              softly.assertThat(result.longValue(call + ".submit")).as(call).isEqualTo(1L);
              softly.assertThat(result.longValue(call + ".schedule")).as(call).isZero();
              softly
                  .assertThat(result.longValue(call + ".vtStart"))
                  .as(call)
                  .isEqualTo(call.startsWith("vt") ? 1L : 0L);
            }
          });
    }

    @Test
    @DisplayName(
        "executor-submit-suppress: a suppressed STPE or virtual-thread submit completes with null")
    void executorSubmitSuppress() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "executor-submit-suppress");
      assertSoftly(
          softly -> {
            softly.assertThat(result.value("stpeRunnable")).isEqualTo("null");
            softly.assertThat(result.value("stpeCallable")).isEqualTo("null");
            softly.assertThat(result.value("vtRunnable")).isEqualTo("null");
            softly.assertThat(result.value("vtCallable")).isEqualTo("null");
            softly.assertThat(result.value("cancelled")).isEqualTo("false");
            softly.assertThat(result.value("ran")).isEqualTo("0");
            softly.assertThat(result.longValue("applied")).isEqualTo(4L);
          });
    }

    @Test
    @DisplayName(
        "session-tick-stpe-submit: a session's stpe.submit is one EXECUTOR_SUBMIT and one"
            + " SCHEDULE_TICK")
    void sessionTickOnStpeSubmit() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "session-tick-stpe-submit");
      assertThat(result.longValue("submit")).isEqualTo(1L);
      assertThat(result.longValue("tick")).as("the task still runs in the session").isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("scheduledOnly and periodicOnly")
  class ScheduledAndPeriodic {

    @Test
    @DisplayName("schedule-periodic-only: periodicOnly matches scheduleAtFixedRate, not schedule")
    void periodicOnly() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "schedule-periodic-only");
      assertSoftly(
          softly -> {
            softly.assertThat(result.longValue("oneShotSubmit")).as("schedule").isZero();
            softly.assertThat(result.longValue("oneShotTick")).as("one-shot tick").isZero();
            softly.assertThat(result.value("periodicRan")).isEqualTo("true");
            softly
                .assertThat(result.longValue("periodicSubmit"))
                .as("scheduleAtFixedRate (SCHEDULE_SUBMIT)")
                .isEqualTo(1L);
            softly.assertThat(result.value("periodicTicks")).as("periodic ticks").isEqualTo("true");
          });
    }

    @Test
    @DisplayName(
        "executor-scheduled-only: scheduledOnly matches a ScheduledThreadPoolExecutor only")
    void scheduledOnly() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "executor-scheduled-only");
      assertSoftly(
          softly -> {
            softly.assertThat(result.longValue("stpe.submit")).isEqualTo(1L);
            softly.assertThat(result.longValue("stpe.run")).isPositive();
            softly.assertThat(result.longValue("stpe.shutdown")).isPositive();
            softly.assertThat(result.longValue("tpe.submit")).isZero();
            softly.assertThat(result.longValue("tpe.run")).isZero();
            softly.assertThat(result.longValue("tpe.shutdown")).isZero();
            softly
                .assertThat(result.longValue("forkJoin"))
                .as("FORK_JOIN_TASK_RUN carries no executor")
                .isZero();
          });
    }
  }

  @Nested
  @DisplayName("time bounds")
  class TimeBounds {

    @Test
    @DisplayName(
        "skewed-window-manual-read: a window read outside any dispatch uses the real clock")
    void windowReadOutsideDispatchIsNotSkewed() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(DefectProbeMain.class, "skewed-window-manual-read");
      assertThat(result.value("hourState")).isEqualTo("ACTIVE");
      assertThat(result.longValue("skewMillis")).isEqualTo(5_000L);
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

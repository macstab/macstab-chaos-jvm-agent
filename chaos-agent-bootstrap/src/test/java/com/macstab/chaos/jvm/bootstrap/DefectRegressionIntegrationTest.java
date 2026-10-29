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

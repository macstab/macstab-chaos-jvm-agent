package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
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

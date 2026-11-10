package com.macstab.chaos.jvm.spring.boot4.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.WithChaos;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * Two test classes under {@link ChaosAgentExtension} run at the same time, in one JVM, with JUnit's
 * parallel execution ({@code junit.jupiter.execution.parallel.*}, classes concurrent): each has a
 * session of its own, and the one's session scenario reaches only its own thread. The fixture
 * classes are run by an engine of their own ({@link EngineTestKit}), so the parallel configuration
 * stays inside this test; when the build discovers them they are skipped.
 */
@DisplayName("Parallel test classes (ChaosAgentExtension)")
class ParallelExecutionTest {

  /** Set only for the engine run below; the fixtures run only when it is present. */
  static final String FIXTURE = "macstab.chaos.test.parallel-fixture";

  private static final String SCENARIO_ID = "sb4-parallel-sleep";

  /** Both fixture tests wait here, so each one's timed call happens while the other runs. */
  static volatile CyclicBarrier both = new CyclicBarrier(2);

  static final Map<String, Long> SLEEP_MILLIS = new ConcurrentHashMap<>();
  static final Map<String, String> SESSION_IDS = new ConcurrentHashMap<>();

  @Test
  @DisplayName("two classes overlap, each in its own session; a session scenario stays in its own")
  void classesRunConcurrentlyAndStayIsolated() {
    both = new CyclicBarrier(2);
    SLEEP_MILLIS.clear();
    SESSION_IDS.clear();

    final EngineExecutionResults results =
        EngineTestKit.engine("junit-jupiter")
            .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
            .configurationParameter("junit.jupiter.execution.parallel.mode.default", "same_thread")
            .configurationParameter(
                "junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
            .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
            .configurationParameter(
                "junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
            .configurationParameter(FIXTURE, "true")
            .selectors(selectClass(DelayedFixture.class), selectClass(UndelayedFixture.class))
            .execute();

    final List<Throwable> failures =
        results.allEvents().executions().failed().stream()
            .map(e -> e.getTerminationInfo().getExecutionResult().getThrowable().orElseThrow())
            .toList();
    assertThat(failures).isEmpty();
    results.testEvents().assertStatistics(stats -> stats.started(2).succeeded(2));

    assertThat(SLEEP_MILLIS.get("delayed"))
        .as("the delayed class's sleep, in its session")
        .isGreaterThanOrEqualTo(250L);
    assertThat(SLEEP_MILLIS.get("undelayed"))
        .as("the other class's sleep, at the same time, outside that session")
        .isLessThan(150L);
    assertThat(SESSION_IDS.get("delayed")).isNotNull().isNotEqualTo(SESSION_IDS.get("undelayed"));
    assertThat(
            ChaosPlatform.current()
                .diagnostics()
                .scenario(SCENARIO_ID)
                .filter(report -> report.state() == ChaosDiagnostics.ScenarioState.ACTIVE))
        .as("the fixture's scenario was released with its test")
        .isEmpty();
  }

  private static long timedSleep() throws InterruptedException {
    final long start = System.nanoTime();
    Thread.sleep(1);
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /** Runs a fixture only inside {@link #classesRunConcurrentlyAndStayIsolated}'s engine. */
  static final class OnlyInTheEngineRun implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
      return context.getConfigurationParameter(FIXTURE).isPresent()
          ? ConditionEvaluationResult.enabled("inside the parallel engine run")
          : ConditionEvaluationResult.disabled("a fixture of ParallelExecutionTest");
    }
  }

  @ExtendWith(OnlyInTheEngineRun.class)
  @ExtendWith(ChaosAgentExtension.class)
  static class DelayedFixture {
    @Test
    @WithChaos(
        """
        {"id": "sb4-parallel-sleep", "scope": "SESSION",
         "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
         "effect": {"type": "delay", "minDelay": "PT0.3S", "maxDelay": "PT0.3S"}}
        """)
    void sleepsInItsSession(final ChaosSession session) throws Exception {
      SESSION_IDS.put("delayed", session.id());
      both.await(30, TimeUnit.SECONDS);
      SLEEP_MILLIS.put("delayed", timedSleep());
      both.await(30, TimeUnit.SECONDS);
    }
  }

  @ExtendWith(OnlyInTheEngineRun.class)
  @ExtendWith(ChaosAgentExtension.class)
  static class UndelayedFixture {
    @Test
    void sleepsOutsideTheOtherSession(final ChaosSession session) throws Exception {
      SESSION_IDS.put("undelayed", session.id());
      both.await(30, TimeUnit.SECONDS);
      SLEEP_MILLIS.put("undelayed", timedSleep());
      both.await(30, TimeUnit.SECONDS);
    }
  }
}

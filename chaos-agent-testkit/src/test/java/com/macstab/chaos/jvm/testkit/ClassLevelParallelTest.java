package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * Two {@link ChaosAgentExtension} classes with different class-level {@link WithChaos} scenarios
 * run at the same time ({@code junit.jupiter.execution.parallel.*}, classes concurrent): each
 * test's session scenario reaches only the calls of its own test thread, the first class's teardown
 * leaves the second class's scenario running, and both are released. The fixture classes are run by
 * an engine of their own ({@link EngineTestKit}), so the parallel configuration stays inside this
 * test; when the build discovers them they are skipped.
 */
@DisplayName("Parallel test classes with class-level @WithChaos (ChaosAgentExtension)")
class ClassLevelParallelTest {

  /** Set only for the engine run below; the fixtures run only when it is present. */
  static final String FIXTURE = "macstab.chaos.test.class-level-parallel-fixture";

  private static final String FIRST_ID = "tk-class-first";
  private static final String SECOND_ID = "tk-class-second";

  /** Both fixture tests wait here, so each one's calls happen while the other runs. */
  static volatile CyclicBarrier both = new CyclicBarrier(2);

  /** Counted down after the first class's chaos extension has torn it down. */
  static volatile CountDownLatch firstEnded = new CountDownLatch(1);

  static final Map<String, Long> APPLIED = new ConcurrentHashMap<>();
  static final Map<String, Boolean> ACTIVE = new ConcurrentHashMap<>();

  @Test
  @DisplayName(
      "each class's scenario reaches its own calls, outlives the other's teardown and is released")
  void classScenariosStayIsolated() {
    both = new CyclicBarrier(2);
    firstEnded = new CountDownLatch(1);
    APPLIED.clear();
    ACTIVE.clear();

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
            .selectors(selectClass(FirstFixture.class), selectClass(SecondFixture.class))
            .execute();

    final List<Throwable> failures =
        results.allEvents().executions().failed().stream()
            .map(e -> e.getTerminationInfo().getExecutionResult().getThrowable().orElseThrow())
            .toList();
    assertThat(failures).isEmpty();
    results.testEvents().assertStatistics(stats -> stats.started(2).succeeded(2));

    assertThat(APPLIED)
        .as("each class's scenario counted only its own test's calls")
        .containsEntry("first", 2L)
        .containsEntry("second", 3L);
    assertThat(ACTIVE)
        .as("after the first class ended")
        .containsEntry("first's class scenario", false)
        .containsEntry("second's class scenario", true);
    assertThat(APPLIED)
        .as("the second class's scenario still applies after the first class's teardown")
        .containsEntry("second, after the first ended", 4L);

    final ChaosDiagnostics diagnostics = ChaosPlatform.current().diagnostics();
    for (final String id : List.of(FIRST_ID, SECOND_ID)) {
      assertThat(active(diagnostics, id)).as("%s is released at the end", id).isEmpty();
    }
  }

  private static Optional<ChaosDiagnostics.ScenarioReport> active(
      final ChaosDiagnostics diagnostics, final String id) {
    return diagnostics
        .scenario(id)
        .filter(report -> report.state() == ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  private static long applied(final ChaosControlPlane controlPlane, final String id) {
    return controlPlane
        .diagnostics()
        .scenario(id)
        .map(ChaosDiagnostics.ScenarioReport::appliedCount)
        .orElse(-1L);
  }

  /** Runs a fixture only inside {@link #classScenariosStayIsolated}'s engine. */
  static final class OnlyInTheEngineRun implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
      return context.getConfigurationParameter(FIXTURE).isPresent()
          ? ConditionEvaluationResult.enabled("inside the class-level parallel engine run")
          : ConditionEvaluationResult.disabled("a fixture of ClassLevelParallelTest");
    }
  }

  /**
   * Registered before the chaos extension, so its {@code afterAll} runs after the chaos
   * extension's.
   */
  static final class SignalsItsEnd implements AfterAllCallback {
    @Override
    public void afterAll(final ExtensionContext context) {
      firstEnded.countDown();
    }
  }

  @ExtendWith(SignalsItsEnd.class)
  @ExtendWith(OnlyInTheEngineRun.class)
  @ExtendWith(ChaosAgentExtension.class)
  @WithChaos(
      """
      {"id": "tk-class-first", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.01S", "maxDelay": "PT0.01S"}}
      """)
  static class FirstFixture {
    @Test
    void sleepsInItsSession(final ChaosControlPlane controlPlane) throws Exception {
      both.await(30, TimeUnit.SECONDS);
      Thread.sleep(1);
      Thread.sleep(1);
      both.await(30, TimeUnit.SECONDS);
      APPLIED.put("first", applied(controlPlane, FIRST_ID));
    }
  }

  @ExtendWith(OnlyInTheEngineRun.class)
  @ExtendWith(ChaosAgentExtension.class)
  @WithChaos(
      """
      {"id": "tk-class-second", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.01S", "maxDelay": "PT0.01S"}}
      """)
  static class SecondFixture {
    @Test
    void sleepsInItsSession(final ChaosControlPlane controlPlane) throws Exception {
      both.await(30, TimeUnit.SECONDS);
      Thread.sleep(1);
      Thread.sleep(1);
      Thread.sleep(1);
      both.await(30, TimeUnit.SECONDS);
      APPLIED.put("second", applied(controlPlane, SECOND_ID));

      assertThat(firstEnded.await(30, TimeUnit.SECONDS)).as("the first class ended").isTrue();
      final ChaosDiagnostics diagnostics = controlPlane.diagnostics();
      ACTIVE.put("first's class scenario", active(diagnostics, FIRST_ID).isPresent());
      ACTIVE.put("second's class scenario", active(diagnostics, SECOND_ID).isPresent());
      Thread.sleep(1);
      APPLIED.put("second, after the first ended", applied(controlPlane, SECOND_ID));
    }
  }
}

package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.WithChaos;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Two {@link MicronautChaosTest} classes with different class-level {@link WithChaos} scenarios run
 * at the same time ({@code junit.jupiter.execution.parallel.*}, classes concurrent). Micronaut
 * starts an application context per test class, so they share the JVM-wide control plane but not a
 * context: the thread that runs a class's tests is bound to the class's session (the chaos
 * extension opened it on that thread in {@code beforeAll}), so each class's session scenario
 * reaches only its own class's calls, the first class's teardown leaves the second class's scenario
 * running, and what the first class activated through its context's control plane bean ends with
 * that context. The fixture classes are run by an engine of their own ({@link EngineTestKit}), so
 * the parallel configuration stays inside this test; when the build discovers them they are
 * skipped.
 */
@DisplayName("Parallel @MicronautChaosTest classes with class-level @WithChaos")
class ConcurrentContextsParallelTest {

  /** Set only for the engine run below; the fixtures run only when it is present. */
  static final String FIXTURE = "macstab.chaos.test.concurrent-contexts-fixture";

  private static final String FIRST_ID = "mn-concurrent-first";
  private static final String SECOND_ID = "mn-concurrent-second";
  private static final String BEAN_ID = "mn-concurrent-bean";

  /** Both fixture tests wait here, so each one's calls happen while the other runs. */
  static volatile CyclicBarrier both = new CyclicBarrier(2);

  /** Counted down after the first class's extensions have torn it down. */
  static volatile CountDownLatch firstEnded = new CountDownLatch(1);

  static final Map<String, Object> CONTEXTS = new ConcurrentHashMap<>();
  static final Map<String, Long> APPLIED = new ConcurrentHashMap<>();
  static final Map<String, Boolean> ACTIVE = new ConcurrentHashMap<>();

  @Test
  @DisplayName(
      "each class's scenario reaches its own calls, outlives the other's teardown and is released")
  void classScenariosStayIsolated() {
    both = new CyclicBarrier(2);
    firstEnded = new CountDownLatch(1);
    CONTEXTS.clear();
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

    assertThat(CONTEXTS.get("first"))
        .as("each class runs on an application context of its own")
        .isNotNull()
        .isNotSameAs(CONTEXTS.get("second"));
    assertThat(APPLIED)
        .as("each class's scenario counted only its own class's calls")
        .containsEntry("first", 2L)
        .containsEntry("second", 3L);
    assertThat(ACTIVE)
        .as("after the first class ended")
        .containsEntry("first's class scenario", false)
        .containsEntry("second's class scenario", true)
        .containsEntry("the first context's bean scenario", false);
    assertThat(APPLIED)
        .as("the second class's scenario still applies after the first class's teardown")
        .containsEntry("second, after the first ended", 4L);

    final ChaosDiagnostics diagnostics = ChaosPlatform.current().diagnostics();
    for (final String id : List.of(FIRST_ID, SECOND_ID, BEAN_ID)) {
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

  /** An application bean that sleeps: the call both classes' scenarios select. */
  @Singleton
  static class Napper {
    void nap() throws InterruptedException {
      Thread.sleep(1);
    }
  }

  /** Runs a fixture only inside {@link #classScenariosStayIsolated}'s engine. */
  static final class OnlyInTheEngineRun implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
      return context.getConfigurationParameter(FIXTURE).isPresent()
          ? ConditionEvaluationResult.enabled("inside the concurrent-contexts engine run")
          : ConditionEvaluationResult.disabled("a fixture of ConcurrentContextsParallelTest");
    }
  }

  /**
   * Registered before the Micronaut and chaos extensions, so its {@code afterAll} runs after
   * theirs.
   */
  static final class SignalsItsEnd implements AfterAllCallback {
    @Override
    public void afterAll(final ExtensionContext context) {
      firstEnded.countDown();
    }
  }

  @ExtendWith(SignalsItsEnd.class)
  @ExtendWith(OnlyInTheEngineRun.class)
  @MicronautChaosTest
  @WithChaos(
      """
      {"id": "mn-concurrent-first", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.01S", "maxDelay": "PT0.01S"}}
      """)
  static class FirstFixture {
    @Inject ApplicationContext context;
    @Inject Napper napper;
    @Inject ChaosControlPlane bean;

    @Test
    void napsInItsSession() throws Exception {
      CONTEXTS.put("first", context);
      bean.activate(
          ChaosScenario.builder(BEAN_ID)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build());
      both.await(30, TimeUnit.SECONDS);
      napper.nap();
      napper.nap();
      both.await(30, TimeUnit.SECONDS);
      APPLIED.put("first", applied(bean, FIRST_ID));
    }
  }

  @ExtendWith(OnlyInTheEngineRun.class)
  @MicronautChaosTest
  @WithChaos(
      """
      {"id": "mn-concurrent-second", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.01S", "maxDelay": "PT0.01S"}}
      """)
  static class SecondFixture {
    @Inject ApplicationContext context;
    @Inject Napper napper;
    @Inject ChaosControlPlane bean;

    @Test
    void napsInItsSession() throws Exception {
      CONTEXTS.put("second", context);
      both.await(30, TimeUnit.SECONDS);
      napper.nap();
      napper.nap();
      napper.nap();
      both.await(30, TimeUnit.SECONDS);
      APPLIED.put("second", applied(bean, SECOND_ID));

      assertThat(firstEnded.await(30, TimeUnit.SECONDS)).as("the first class ended").isTrue();
      final ChaosDiagnostics diagnostics = bean.diagnostics();
      ACTIVE.put("first's class scenario", active(diagnostics, FIRST_ID).isPresent());
      ACTIVE.put("second's class scenario", active(diagnostics, SECOND_ID).isPresent());
      ACTIVE.put("the first context's bean scenario", active(diagnostics, BEAN_ID).isPresent());
      napper.nap();
      APPLIED.put("second, after the first ended", applied(bean, SECOND_ID));
    }
  }
}

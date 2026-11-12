package com.macstab.chaos.jvm.examples.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.quarkus.QuarkusChaosTest;
import com.macstab.chaos.jvm.testkit.ChaosTestKit;
import com.macstab.chaos.jvm.testkit.WithChaos;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * Two {@link QuarkusChaosTest} classes with different class-level {@link WithChaos} scenarios run
 * on one Quarkus application. Quarkus's test extension runs one test class at a time (it keeps the
 * current test class in static state, so concurrent classes fail inside it): the classes run one
 * after the other. The chaos extension binds the thread that runs a class's tests to the class's
 * session around each test, so each class's session scenario reaches only its own class's calls and
 * ends with its class, and what the first class activated through the shared control plane bean
 * ends with it, the last running chaos test class. Quarkus runs a test class in a class loader of
 * its own, so the fixtures and this test share their state through a JDK map in the system
 * properties. The fixtures run in an engine of their own ({@link EngineTestKit}) that starts an
 * application of its own; Quarkus's test extension keeps the running application in static state,
 * so the build runs this test in a test JVM of its own ({@code testSharedApplication}).
 */
@DisplayName("@QuarkusChaosTest classes sharing one application")
class SharedApplicationClassesTest {

  /** Set only for the engine run below; the fixtures run only when it is present. */
  static final String FIXTURE = "macstab.chaos.test.shared-application-fixture";

  private static final String FIRST_ID = "qk-shared-first";
  private static final String SECOND_ID = "qk-shared-second";
  private static final String BEAN_ID = "qk-shared-bean";

  private static final String RESULTS = FIXTURE + ".results";

  @SuppressWarnings("unchecked")
  private static void record(final String key, final Object value) {
    ((Map<String, Object>) System.getProperties().get(RESULTS)).put(key, value);
  }

  @Test
  @DisplayName("each class's scenario reaches its own calls and ends with its class")
  void classScenariosStayIsolatedOnASharedApplication() {
    final Map<String, Object> results = new ConcurrentHashMap<>();
    System.getProperties().put(RESULTS, results);
    final EngineExecutionResults run;
    try {
      run =
          EngineTestKit.engine("junit-jupiter")
              .configurationParameter(
                  "junit.jupiter.testclass.order.default", ClassOrderer.ClassName.class.getName())
              .configurationParameter(FIXTURE, "true")
              .selectors(selectClass(SecondFixture.class), selectClass(FirstFixture.class))
              .execute();
    } finally {
      System.getProperties().remove(RESULTS);
    }

    final List<Throwable> failures =
        run.allEvents().executions().failed().stream()
            .map(e -> e.getTerminationInfo().getExecutionResult().getThrowable().orElseThrow())
            .toList();
    assertThat(failures).isEmpty();
    run.testEvents().assertStatistics(stats -> stats.started(2).succeeded(2));

    assertThat(results)
        .as("each class's scenario counted only its own class's calls; after the first ended")
        .containsEntry("first applied", 2L)
        .containsEntry("second applied", 3L)
        .containsEntry("first active", false)
        .containsEntry("bean active", false)
        .containsEntry("same application", true);

    final ChaosDiagnostics diagnostics = ChaosTestKit.install().diagnostics();
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

  /** Runs a fixture only inside the engine run above. */
  static final class OnlyInTheEngineRun implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
      return context.getConfigurationParameter(FIXTURE).isPresent()
          ? ConditionEvaluationResult.enabled("inside the shared-application engine run")
          : ConditionEvaluationResult.disabled("a fixture of SharedApplicationClassesTest");
    }
  }

  @ExtendWith(OnlyInTheEngineRun.class)
  @QuarkusChaosTest
  @WithChaos(
      """
      {"id": "qk-shared-first", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.01S", "maxDelay": "PT0.01S"}}
      """)
  static class FirstFixture {
    @Inject NapService naps;
    @Inject ChaosControlPlane bean;

    @Test
    void napsInItsSession() throws Exception {
      record("first naps", System.identityHashCode(naps.getClass().getClassLoader()));
      bean.activate(
          ChaosScenario.builder(BEAN_ID)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build());
      naps.nap();
      naps.nap();
      record("first applied", applied(bean, FIRST_ID));
    }
  }

  @ExtendWith(OnlyInTheEngineRun.class)
  @QuarkusChaosTest
  @WithChaos(
      """
      {"id": "qk-shared-second", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.01S", "maxDelay": "PT0.01S"}}
      """)
  static class SecondFixture {
    @Inject NapService naps;
    @Inject ChaosControlPlane bean;

    @Test
    void napsInItsSession() throws Exception {
      @SuppressWarnings("unchecked")
      final Map<String, Object> results = (Map<String, Object>) System.getProperties().get(RESULTS);
      record(
          "same application",
          results
              .get("first naps")
              .equals(System.identityHashCode(naps.getClass().getClassLoader())));
      final ChaosDiagnostics diagnostics = bean.diagnostics();
      record("first active", active(diagnostics, FIRST_ID).isPresent());
      record("bean active", active(diagnostics, BEAN_ID).isPresent());
      naps.nap();
      naps.nap();
      naps.nap();
      // A nap outside any session (a thread started here would inherit this class's session):
      // a session scenario does not reach it, as a JVM-wide one would.
      try (com.macstab.chaos.jvm.api.ChaosSession.ScopeBinding outside = bean.bindSession(null)) {
        naps.nap();
      }
      record("second applied", applied(bean, SECOND_ID));
    }
  }
}

package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * {@link WithChaos} scenarios are active, and the test thread is bound to the session, around the
 * test method only: not while the test's {@code @BeforeEach} and {@code @AfterEach} methods run,
 * and on the thread that runs the test body.
 */
@ExtendWith(ChaosAgentExtension.class)
@DisplayName("ChaosAgentExtension around the test method")
class ChaosAgentExtensionLifecycleTest {

  private static final String SCENARIO_ID = "lifecycle-submit";

  private static final String SUBMIT_DELAY =
      """
      {"id": "lifecycle-submit", "scope": "SESSION",
       "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
       "effect": {"type": "delay", "minDelay": "PT0.3S", "maxDelay": "PT0.3S"}}
      """;

  private static final long DELAYED_MILLIS = 250L;

  /** Submits a no-op task from the calling thread and returns how long the submit took. */
  private static long timedSubmitMillis() throws Exception {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final long start = System.nanoTime();
      final Future<?> done = executor.submit(() -> {});
      final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      done.get(10, TimeUnit.SECONDS);
      return elapsed;
    } finally {
      executor.shutdownNow();
    }
  }

  @Nested
  @DisplayName("set-up and tear-down")
  class SetUpAndTearDown {

    @BeforeEach
    void setUp(final ChaosControlPlane controlPlane) throws Exception {
      assertThat(controlPlane.diagnostics().scenario(SCENARIO_ID))
          .as("registered while @BeforeEach runs")
          .isEmpty();
      // A submit the scenario would count if it were active and the thread bound here.
      timedSubmitMillis();
    }

    @AfterEach
    void tearDown(final ChaosControlPlane controlPlane) {
      assertThat(controlPlane.diagnostics().scenario(SCENARIO_ID))
          .as("registered while @AfterEach runs")
          .isEmpty();
    }

    @Test
    @WithChaos(SUBMIT_DELAY)
    @DisplayName("the scenario counts nothing from @BeforeEach and is gone in @AfterEach")
    void activeAroundTheMethodOnly(final ChaosControlPlane controlPlane) {
      assertThat(
              controlPlane
                  .diagnostics()
                  .scenario(SCENARIO_ID)
                  .map(ChaosDiagnostics.ScenarioReport::appliedCount))
          .contains(0L);
    }
  }

  @Nested
  @DisplayName("test bodies on other threads")
  class OtherThreads {

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @WithChaos(SUBMIT_DELAY)
    @DisplayName("a body run on the timeout's separate thread is bound to the session")
    void separateThreadBodyIsBound() throws Exception {
      assertThat(timedSubmitMillis()).isGreaterThanOrEqualTo(DELAYED_MILLIS);
    }

    @RepeatedTest(1)
    @WithChaos(SUBMIT_DELAY)
    @DisplayName("a repeated test runs in the session")
    void repeatedTestIsBound() throws Exception {
      assertThat(timedSubmitMillis()).isGreaterThanOrEqualTo(DELAYED_MILLIS);
    }

    @TestFactory
    @WithChaos(SUBMIT_DELAY)
    @DisplayName("a dynamic test runs in the session")
    Stream<DynamicTest> dynamicTestIsBound() {
      return Stream.of(
          dynamicTest(
              "delayed",
              () -> assertThat(timedSubmitMillis()).isGreaterThanOrEqualTo(DELAYED_MILLIS)));
    }
  }
}

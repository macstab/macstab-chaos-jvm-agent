package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.testkit.ChaosAssertions;
import com.macstab.chaos.jvm.testkit.WithChaos;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Class- and method-level {@code @WithChaos} under {@link ChaosQuarkusExtension} reach real calls:
 * the class's JVM scenario delays {@code ThreadPoolExecutor.shutdown()} for every test of the
 * class, the method's session scenario delays the test thread's {@code Thread.sleep} during its
 * method only.
 */
@ExtendWith(ChaosQuarkusExtension.class)
@WithChaos(
    """
    {"id": "qk-class", "scope": "JVM",
     "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
     "effect": {"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"}}
    """)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("@WithChaos with ChaosQuarkusExtension")
class WithChaosIntegrationTest {

  private static final long DELAY_MILLIS = 200;

  /** Below the delay with room for a slow machine, far above an undelayed call. */
  private static final long DELAYED_MILLIS = 180;

  @Test
  @Order(1)
  @WithChaos(
      """
      {"id": "qk-method", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"}}
      """)
  @DisplayName("class- and method-level scenarios are active and delay real calls")
  void activatesBothLevels(final ChaosControlPlane controlPlane) throws Exception {
    ChaosAssertions.assertThat(controlPlane.diagnostics())
        .hasNoFailuresFor("qk-class", "qk-method")
        .scenario("qk-class")
        .hasState(ChaosDiagnostics.ScenarioState.ACTIVE);
    ChaosAssertions.assertThat(controlPlane.diagnostics())
        .scenario("qk-method")
        .hasState(ChaosDiagnostics.ScenarioState.ACTIVE);

    assertThat(sleepMillis())
        .as("Thread.sleep(1) delayed by the method's scenario")
        .isGreaterThanOrEqualTo(DELAYED_MILLIS);
    assertThat(applied(controlPlane, "qk-method")).isPositive();
    assertShutdownDelayed(controlPlane);
  }

  @Test
  @Order(2)
  @DisplayName("stops method-level scenarios after their method, keeps class-level ones")
  void methodLevelEndsWithMethod(final ChaosControlPlane controlPlane) throws Exception {
    assertThat(
            controlPlane
                .diagnostics()
                .scenario("qk-method")
                .filter(r -> r.state() == ChaosDiagnostics.ScenarioState.ACTIVE))
        .isEmpty();
    ChaosAssertions.assertThat(controlPlane.diagnostics())
        .scenario("qk-class")
        .hasState(ChaosDiagnostics.ScenarioState.ACTIVE);

    assertThat(sleepMillis()).as("Thread.sleep(1) no longer delayed").isLessThan(DELAY_MILLIS);
    assertShutdownDelayed(controlPlane);
  }

  /** A real {@code shutdown()} waits for the class scenario's delay, which counts it. */
  private static void assertShutdownDelayed(final ChaosControlPlane controlPlane) {
    final long appliedBefore = applied(controlPlane, "qk-class");
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final long start = System.nanoTime();
    executor.shutdown();
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(elapsed)
        .as("shutdown() delayed by the class's scenario")
        .isGreaterThanOrEqualTo(DELAYED_MILLIS);
    assertThat(applied(controlPlane, "qk-class")).isGreaterThan(appliedBefore);
  }

  private static long sleepMillis() throws InterruptedException {
    final long start = System.nanoTime();
    Thread.sleep(1);
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  private static long applied(final ChaosControlPlane controlPlane, final String id) {
    return controlPlane.diagnostics().scenario(id).orElseThrow().appliedCount();
  }
}

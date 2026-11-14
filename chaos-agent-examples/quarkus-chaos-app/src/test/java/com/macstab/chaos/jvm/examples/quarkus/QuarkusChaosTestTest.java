package com.macstab.chaos.jvm.examples.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.quarkus.QuarkusChaosTest;
import com.macstab.chaos.jvm.testkit.WithChaos;
import io.quarkus.arc.ClientProxy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * {@code @QuarkusChaosTest} on a real {@code @QuarkusTest}: the injected {@code ChaosControlPlane}
 * is the tracking plane the test extension releases, and a method-level {@code @WithChaos} delays
 * the application bean's real sleep during its method only.
 */
@QuarkusChaosTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("@QuarkusChaosTest in a booted Quarkus application")
class QuarkusChaosTestTest {

  @Inject ChaosControlPlane controlPlane;

  @Inject NapService naps;

  @Test
  @Order(1)
  @WithChaos(
      """
      {"id": "qk-app-sleep", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"}}
      """)
  @DisplayName("injects the tracking plane, and the method's scenario delays Thread.sleep")
  void methodScenarioDelaysASleep() throws Exception {
    // The bean comes from the system class loader's test extension (the api is parent-first, the
    // testkit is not): compare the class by name, behind the CDI client proxy.
    assertThat(ClientProxy.unwrap(controlPlane).getClass().getName())
        .isEqualTo("com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane");

    assertThat(naps.nap()).isGreaterThanOrEqualTo(180);
    assertThat(controlPlane.diagnostics().scenario("qk-app-sleep").orElseThrow().appliedCount())
        .isPositive();
  }

  @Test
  @Order(2)
  @DisplayName("the method's scenario ended with its method")
  void methodScenarioEnded() throws Exception {
    assertThat(
            controlPlane
                .diagnostics()
                .scenario("qk-app-sleep")
                .filter(report -> report.state() == ChaosDiagnostics.ScenarioState.ACTIVE))
        .isEmpty();
    assertThat(naps.nap()).isLessThan(150);
  }
}

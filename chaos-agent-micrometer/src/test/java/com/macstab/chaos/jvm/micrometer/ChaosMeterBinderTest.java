package com.macstab.chaos.jvm.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosMeterBinder")
class ChaosMeterBinderTest {

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("counts applications per scenario and gauges scenarios per state")
  void countsAndGauges() throws Exception {
    new ChaosMeterBinder(runtime).bindTo(registry);
    runtime.activate(
        ChaosScenario.builder("metered")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build());

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    // Events are counted asynchronously; wait for both applications.
    final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (appliedCount() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }

    assertThat(
            registry
                .get("chaos.events")
                .tags("type", "applied", "scenario", "metered")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(registry.get("chaos.scenarios").tags("state", "active").gauge().value())
        .isEqualTo(1.0);
  }

  private double appliedCount() {
    final io.micrometer.core.instrument.Counter counter =
        registry.find("chaos.events").tags("type", "applied", "scenario", "metered").counter();
    return counter == null ? 0.0 : counter.count();
  }
}

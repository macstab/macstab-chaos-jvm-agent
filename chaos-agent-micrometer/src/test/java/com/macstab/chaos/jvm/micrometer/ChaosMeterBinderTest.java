package com.macstab.chaos.jvm.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

    // Events are counted asynchronously; wait for both applications. The delay is the last meter
    // an application updates, so once it holds both, every other meter does too.
    await().atMost(Duration.ofSeconds(5)).until(() -> delayCount() >= 2);

    assertThat(
            registry
                .get("chaos.events")
                .tags("type", "applied", "scenario", "metered")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(registry.get("chaos.scenarios").tags("state", "active").gauge().value())
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("chaos.applications")
                .tags(
                    "scenario", "metered", "operation", "EXECUTOR_SUBMIT", "effect", "DelayEffect")
                .counter()
                .count())
        .isEqualTo(2.0);
    final io.micrometer.core.instrument.Timer delay =
        registry.get("chaos.injected.delay").tags("scenario", "metered").timer();
    assertThat(delay.count()).isEqualTo(2L);
    assertThat(delay.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(2.0);
    assertThat(registry.get("chaos.hooks.woven").gauge().value()).isGreaterThanOrEqualTo(0.0);
    assertThat(registry.get("chaos.events.dropped").functionCounter().count()).isZero();
  }

  @Test
  @DisplayName("uses one tag key set per meter name, so Prometheus accepts every event type")
  void consistentTagKeys() throws Exception {
    final io.micrometer.core.instrument.MeterRegistry strict =
        new SimpleMeterRegistry() {
          private final java.util.Map<String, java.util.Set<String>> keys =
              new java.util.concurrent.ConcurrentHashMap<>();

          @Override
          protected io.micrometer.core.instrument.Counter newCounter(
              final io.micrometer.core.instrument.Meter.Id id) {
            final java.util.Set<String> tagKeys =
                id.getTags().stream()
                    .map(io.micrometer.core.instrument.Tag::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            final java.util.Set<String> previous = keys.putIfAbsent(id.getName(), tagKeys);
            if (previous != null && !previous.equals(tagKeys)) {
              throw new IllegalArgumentException(id.getName() + " tag keys " + tagKeys);
            }
            return super.newCounter(id);
          }
        };
    final ChaosMeterBinder binder = new ChaosMeterBinder(runtime);
    binder.bindTo(strict);
    runtime.activate(
        ChaosScenario.builder("half")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.builder().probability(0.5).randomSeed(7L).build())
            .build());
    for (int i = 0; i < 20; i++) {
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    }
    await().atMost(Duration.ofSeconds(5)).until(() -> strict.find("chaos.skips").counter() != null);
    binder.close();

    assertThat(strict.get("chaos.events").tags("type", "skipped").counter().count()).isPositive();
    assertThat(strict.get("chaos.events").tags("type", "applied").counter().count()).isPositive();
  }

  @Test
  @DisplayName("counts failed activations by category and stops counting once closed")
  void failuresAndClose() throws Exception {
    final ChaosMeterBinder binder = new ChaosMeterBinder(runtime);
    binder.bindTo(registry);
    final ChaosScenario scenario =
        ChaosScenario.builder("twice")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .build();
    runtime.activate(scenario);
    try {
      runtime.activate(scenario);
    } catch (final RuntimeException conflict) {
      // the id is taken: the second activation fails
    }
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> registry.find("chaos.activation.failures").counter() != null);
    assertThat(
            registry
                .get("chaos.activation.failures")
                .tags("scenario", "twice", "category", "ACTIVATION_CONFLICT")
                .counter()
                .count())
        .isEqualTo(1.0);

    binder.close();
    binder.close();
    // close() removed the listener: this application is never handed to the binder.
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    assertThat(registry.find("chaos.applications").counter()).isNull();
  }

  private long delayCount() {
    final io.micrometer.core.instrument.Timer timer =
        registry.find("chaos.injected.delay").tags("scenario", "metered").timer();
    return timer == null ? 0L : timer.count();
  }
}

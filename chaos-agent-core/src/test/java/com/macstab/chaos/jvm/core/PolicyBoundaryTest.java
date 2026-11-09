package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exact boundaries of the activation policy: where a rate-limit window ends and how windows
 * align, how {@code maxApplications} counts reservations, the inclusive bounds of a delay range,
 * what an inactive or stopped gate does to its waiting callers, and what an application reports.
 * Mutation testing showed each of these could change without a test failing.
 */
@DisplayName("Activation-policy boundaries")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class PolicyBoundaryTest {

  private static final InvocationContext SUBMIT =
      new InvocationContext(
          OperationType.EXECUTOR_SUBMIT, "executor", "task", null, false, null, null, null);

  private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(100_000L));
  private final List<String> metrics = new CopyOnWriteArrayList<>();
  private final ChaosRuntime runtime =
      new ChaosRuntime(
          clock,
          new ChaosMetricsSink() {
            @Override
            public void increment(final String name, final Map<String, String> tags) {
              metrics.add(name);
            }

            @Override
            public void recordDuration(
                final String name, final Duration duration, final Map<String, String> tags) {}
          });

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("a rate-limit window grants exactly its permits and ends exactly at its length")
  void rateLimitWindowBoundaries() {
    final ScenarioController controller =
        controller(
            ChaosEffect.delay(Duration.ZERO),
            ActivationPolicy.builder().rateLimit(3, Duration.ofSeconds(1)).build());

    assertThat(granted(controller, 4)).as("three permits in the first window").isEqualTo(3);
    clock.set(Instant.ofEpochMilli(100_999L));
    assertThat(granted(controller, 1)).as("1 ms before the window ends").isZero();
    clock.set(Instant.ofEpochMilli(101_000L));
    assertThat(granted(controller, 4)).as("the next window starts at its length").isEqualTo(3);

    // Two windows pass at once: the window advances by whole lengths, staying aligned to the
    // start, so the one that began at 102_000 ends at 103_000, not 2_500 ms after this call.
    clock.set(Instant.ofEpochMilli(102_500L));
    assertThat(granted(controller, 4)).isEqualTo(3);
    clock.set(Instant.ofEpochMilli(102_999L));
    assertThat(granted(controller, 1)).isZero();
    clock.set(Instant.ofEpochMilli(103_000L));
    assertThat(granted(controller, 1)).isEqualTo(1);
  }

  @Test
  @DisplayName("maxApplications counts outstanding reservations; a released one is available again")
  void reservationsCountAgainstTheCap() {
    final ScenarioController controller =
        controller(ChaosEffect.suppress(), ActivationPolicy.count(2));

    final ScenarioContribution first = controller.evaluate(SUBMIT);
    final ScenarioContribution second = controller.evaluate(SUBMIT);
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(controller.evaluate(SUBMIT)).as("two reservations outstanding").isNull();
    assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

    first.release();
    final ScenarioContribution third = controller.evaluate(SUBMIT);
    assertThat(third).as("the released reservation is available").isNotNull();

    second.commit();
    assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    third.commit();
    assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
    assertThat(controller.evaluate(SUBMIT)).isNull();
  }

  @Test
  @DisplayName("a spent scenario is not evaluated any more: no further SKIPPED events")
  void spentScenarioStopsEvaluating() {
    final List<ChaosEvent> skipped = new CopyOnWriteArrayList<>();
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.SKIPPED) {
            skipped.add(event);
          }
        });
    runtime.activate(scenario("once", ChaosEffect.suppress(), ActivationPolicy.count(1)));

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    assertThat(skipped).isEmpty();
  }

  @Test
  @DisplayName("a delay range draws every value from its minimum to its maximum, both included")
  void delayRangeIsInclusive() {
    final ScenarioController controller =
        controller(
            ChaosEffect.delay(Duration.ofNanos(10), Duration.ofNanos(12)),
            ActivationPolicy.always());
    final Set<Long> drawn = new TreeSet<>();
    for (int match = 0; match < 300; match++) {
      final ScenarioContribution contribution = controller.evaluate(SUBMIT);
      drawn.add(contribution.delayNanos());
      contribution.release();
    }
    assertThat(drawn).containsExactly(10L, 11L, 12L);
  }

  @Test
  @DisplayName("a delay below a millisecond is drawn, and slept, to the nanosecond")
  void subMillisecondDelay() {
    final ScenarioController controller =
        controller(ChaosEffect.delay(Duration.ofNanos(500_000)), ActivationPolicy.always());
    final ScenarioContribution contribution = controller.evaluate(SUBMIT);
    assertThat(contribution.delayNanos()).isEqualTo(500_000L);
    contribution.release();

    try (ChaosRuntime runtime = new ChaosRuntime()) {
      runtime.activate(
          ChaosScenario.builder("sub-millisecond")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofNanos(500_000)))
              .build());
      final long start = System.nanoTime();
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
      assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(500_000L);
    }
  }

  @Test
  @DisplayName("the draws depend on the scenario id, not only on the seed")
  void drawsDependOnTheId() {
    final ActivationPolicy seeded =
        ActivationPolicy.builder().probability(0.5).randomSeed(7L).build();
    assertThat(decisions("a", seeded)).isEqualTo(decisions("a", seeded));
    assertThat(decisions("a", seeded)).isNotEqualTo(decisions("b", seeded));
  }

  @Test
  @DisplayName("a null randomSeed draws a fresh seed; a scenario without a policy has seed 0")
  void seedDefaults() {
    final ActivationPolicy unseeded =
        ActivationPolicy.builder().probability(0.5).randomSeed(null).build();
    final ActivationPolicy zero =
        ActivationPolicy.builder().probability(0.5).randomSeed(0L).build();

    assertThat(decisions("a", unseeded))
        .as("two controllers of an unseeded policy draw differently")
        .isNotEqualTo(decisions("a", unseeded));
    assertThat(decisions("a", zero)).isEqualTo(decisions("a", zero));
    assertThat(
            ChaosScenario.builder("default")
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.suppress())
                .build()
                .activationPolicy())
        .as("a scenario without a policy runs ActivationPolicy.always(), seed 0")
        .isEqualTo(ActivationPolicy.always())
        .extracting(ActivationPolicy::randomSeed)
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("an APPLIED event carries the delay it added, and only a delay; a metric counts it")
  void appliedEventAttributes() throws Throwable {
    final List<ChaosEvent> applied = new CopyOnWriteArrayList<>();
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.APPLIED) {
            applied.add(event);
          }
        });
    runtime.activate(
        scenario("slow", ChaosEffect.delay(Duration.ofMillis(1)), ActivationPolicy.always()));
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    runtime.activate(
        ChaosScenario.builder("quiet")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)))
            .effect(ChaosEffect.delay(Duration.ZERO))
            .build());
    runtime.beforeWorkerRun(this, Thread.currentThread(), () -> {});

    assertThat(applied).hasSize(2);
    assertThat(applied.get(0).attributes()).containsEntry("delayMillis", "1");
    assertThat(applied.get(1).attributes()).doesNotContainKey("delayMillis");
    assertThat(metrics).filteredOn("chaos.effect.applied"::equals).hasSize(2);
  }

  @Test
  @DisplayName("stopping a gate scenario releases the callers it holds")
  void stopReleasesTheGate() throws Exception {
    final ChaosActivationHandle handle =
        runtime.activate(scenario("held", ChaosEffect.gate(null), ActivationPolicy.always()));
    final Thread caller = heldCaller();

    handle.stop();

    caller.join(5_000L);
    assertThat(caller.isAlive()).as("released by stop()").isFalse();
  }

  @Test
  @DisplayName("a gate scenario that expires releases the callers it holds")
  void expiryReleasesTheGate() throws Exception {
    runtime.activate(
        scenario(
            "expiring",
            ChaosEffect.gate(null),
            ActivationPolicy.builder().activeFor(Duration.ofSeconds(1)).build()));
    final Thread caller = heldCaller();

    clock.set(Instant.ofEpochMilli(102_000L));
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    caller.join(5_000L);
    assertThat(caller.isAlive()).as("released when the scenario expired").isFalse();
  }

  /** A thread held by the gate of the active scenario, once it is parked there. */
  private Thread heldCaller() {
    final AtomicBoolean returned = new AtomicBoolean();
    final Thread caller =
        new Thread(
            () -> {
              runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
              returned.set(true);
            },
            "gate-caller");
    caller.setDaemon(true);
    caller.start();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> caller.getState() == Thread.State.WAITING || returned.get());
    assertThat(returned).as("held by the gate").isFalse();
    return caller;
  }

  private List<Boolean> decisions(final String id, final ActivationPolicy policy) {
    final ScenarioController controller =
        new ScenarioController(
            scenario(id, ChaosEffect.delay(Duration.ZERO), policy),
            "jvm",
            null,
            clock,
            new ObservabilityBus(ChaosMetricsSink.NOOP),
            Optional::empty);
    controller.start();
    final List<Boolean> decisions = new java.util.ArrayList<>();
    for (int match = 0; match < 64; match++) {
      final ScenarioContribution contribution = controller.evaluate(SUBMIT);
      decisions.add(contribution != null);
      if (contribution != null) {
        contribution.release();
      }
    }
    return decisions;
  }

  /** Evaluates {@code calls} times, applying every contribution, and counts the contributions. */
  private int granted(final ScenarioController controller, final int calls) {
    int granted = 0;
    for (int call = 0; call < calls; call++) {
      final ScenarioContribution contribution = controller.evaluate(SUBMIT);
      if (contribution != null) {
        granted++;
        contribution.commit();
      }
    }
    return granted;
  }

  private ScenarioController controller(final ChaosEffect effect, final ActivationPolicy policy) {
    final ScenarioController controller =
        new ScenarioController(
            scenario("boundary", effect, policy),
            "jvm",
            null,
            clock,
            new ObservabilityBus(ChaosMetricsSink.NOOP),
            Optional::empty);
    controller.start();
    return controller;
  }

  private static ChaosScenario scenario(
      final String id, final ChaosEffect effect, final ActivationPolicy policy) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(effect)
        .activationPolicy(policy)
        .build();
  }

  private static final class MutableClock extends Clock {
    private volatile Instant instant;

    MutableClock(final Instant instant) {
      this.instant = instant;
    }

    void set(final Instant newInstant) {
      instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

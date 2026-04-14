package io.macstab.chaos.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosEvent;
import io.macstab.chaos.api.ChaosMetricsSink;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.ChaosDiagnostics;
import io.macstab.chaos.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChaosRuntimeActivationPolicyTest {

  // ── activateAfterMatches ──────────────────────────────────────────────────

  @Test
  void activateAfterMatchesThreeSkipsFirstThreeMatches() {
    ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("warm-up")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(80)))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 3, null, null, null, null))
            .build());

    // First 3 matches: no delay expected (less than 30ms each)
    for (int i = 0; i < 3; i++) {
      long elapsed = measureMillis(
          () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      assertTrue(elapsed < 50,
          "match " + i + " should not be delayed, but took " + elapsed + "ms");
    }

    // 4th match: delay should fire
    long elapsed = measureMillis(
        () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
    assertTrue(elapsed >= 60,
        "4th match should incur delay, but took only " + elapsed + "ms");
  }

  // ── maxApplications ───────────────────────────────────────────────────────

  @Test
  void maxApplicationsTwoFiresExactlyTwice() {
    ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("max-two")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(60)))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, 2L, null, null, null))
            .build());

    // First two should be delayed
    long first = measureMillis(
        () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
    long second = measureMillis(
        () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));

    // Third should NOT be delayed (maxApplications exhausted)
    long third = measureMillis(
        () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));

    assertTrue(first >= 40, "first should be delayed but took " + first + "ms");
    assertTrue(second >= 40, "second should be delayed but took " + second + "ms");
    assertTrue(third < 40, "third should not be delayed but took " + third + "ms");
  }

  // ── addEventListener ──────────────────────────────────────────────────────

  @Test
  void addEventListenerReceivesStartedAndAppliedEvents() {
    ChaosRuntime runtime = new ChaosRuntime();
    List<ChaosEvent> events = new ArrayList<>();
    runtime.addEventListener(events::add);

    runtime.activate(
        ChaosScenario.builder("listen-test")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build());

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    boolean hasStarted = events.stream()
        .anyMatch(e -> e.type() == ChaosEvent.Type.STARTED
            && "listen-test".equals(e.scenarioId()));
    boolean hasApplied = events.stream()
        .anyMatch(e -> e.type() == ChaosEvent.Type.APPLIED
            && "listen-test".equals(e.scenarioId()));

    assertTrue(hasStarted, "expected STARTED event for listen-test");
    assertTrue(hasApplied, "expected APPLIED event for listen-test");
  }

  // ── diagnostics snapshot counters ────────────────────────────────────────

  @Test
  void diagnosticsSnapshotReflectsMatchedAndAppliedCount() {
    ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("counter-test")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build());

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    ChaosDiagnostics.Snapshot snapshot = runtime.diagnostics().snapshot();
    ChaosDiagnostics.ScenarioReport report =
        snapshot.scenarios().stream()
            .filter(r -> "counter-test".equals(r.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("scenario not found in snapshot"));

    assertEquals(2, report.matchedCount(), "expected 2 matched operations");
    assertEquals(2, report.appliedCount(), "expected 2 applied effects");
  }

  // ── activeFor with clock injection ───────────────────────────────────────

  @Test
  void activeForExpiresAfterClockAdvance() {
    MutableClock clock = new MutableClock();
    ChaosRuntime runtime = new ChaosRuntime(clock, ChaosMetricsSink.NOOP);
    runtime.activate(
        ChaosScenario.builder("time-bounded")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(60)))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC,
                    1.0d,
                    0,
                    null,
                    Duration.ofSeconds(5),
                    null,
                    null))
            .build());

    // First call: clock at T=0, within activeFor window — should be delayed
    long firstElapsed = measureMillis(
        () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
    assertTrue(firstElapsed >= 40, "expected delay within active window, got " + firstElapsed + "ms");

    // Advance clock by 10 seconds — beyond the 5-second activeFor window
    clock.advance(Duration.ofSeconds(10));

    // Second call: clock at T=10s, past activeFor — effect should not fire
    long secondElapsed = measureMillis(
        () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
    assertTrue(secondElapsed < 40,
        "expected no delay after activeFor expiry, got " + secondElapsed + "ms");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private long measureMillis(Runnable runnable) {
    long start = System.nanoTime();
    runnable.run();
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  static final class MutableClock extends Clock {
    private volatile Instant now = Instant.now();

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}

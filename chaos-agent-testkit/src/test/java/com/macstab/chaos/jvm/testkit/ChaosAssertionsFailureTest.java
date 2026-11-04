package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosScenario;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/** The failures of the chaos assertions, driven by fixed counts instead of a live agent. */
@DisplayName("ChaosAssertions failures")
class ChaosAssertionsFailureTest {

  @Test
  @DisplayName("a state other than the expected one fails, naming both")
  void wrongState() {
    final ChaosActivationHandle handle = handle(ChaosDiagnostics.ScenarioState.INACTIVE, 0L, 0L);

    assertThatThrownBy(
            () ->
                ChaosAssertions.assertThat(handle).hasState(ChaosDiagnostics.ScenarioState.ACTIVE))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessage("expected scenario fixed to be ACTIVE but was INACTIVE");
  }

  @Test
  @DisplayName("atMost and exactly fail on a count past them")
  void countsPastTheBound() {
    final ChaosActivationHandle handle = handle(ChaosDiagnostics.ScenarioState.ACTIVE, 3L, 3L);

    assertThatThrownBy(() -> ChaosAssertions.assertThat(handle).matched().atMost(2))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("at most 2")
        .hasMessageContaining("but was 3");
    assertThatThrownBy(() -> ChaosAssertions.assertThat(handle).applied().exactly(2))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("exactly 2")
        .hasMessageContaining("but was 3");
  }

  @Test
  @DisplayName("an interrupted wait stops at once, fails, and keeps the interrupt")
  void interruptedWait() {
    final ChaosActivationHandle handle = handle(ChaosDiagnostics.ScenarioState.ACTIVE, 0L, 0L);
    final long started = System.nanoTime();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(
              () ->
                  ChaosAssertions.assertThat(handle)
                      .applied()
                      .within(Duration.ofMinutes(1))
                      .atLeast(1))
          .isInstanceOf(AssertionFailedError.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("hasNoFailures fails on any failure; hasNoFailuresFor only on the named ids")
  void activationFailures() {
    final FixedDiagnostics none = new FixedDiagnostics(List.of());
    final FixedDiagnostics one =
        new FixedDiagnostics(
            List.of(
                new ChaosDiagnostics.ActivationFailure(
                    "broken", ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION, "bad")));

    assertThatCode(() -> ChaosAssertions.assertThat(none).hasNoFailures())
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> ChaosAssertions.assertThat(one).hasNoFailures())
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageStartingWith("expected no activation failures but found")
        .hasMessageContaining("broken");
    assertThatCode(() -> ChaosAssertions.assertThat(one).hasNoFailuresFor("other"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> ChaosAssertions.assertThat(one).hasNoFailuresFor("broken"))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessageContaining("broken");
  }

  @Test
  @DisplayName("a scenario's matches are read from diagnostics, and one gone since fails")
  void scenarioFromDiagnostics() {
    final FixedDiagnostics diagnostics = new FixedDiagnostics(List.of());
    diagnostics.reports.add(Optional.of(report(4L)));
    diagnostics.reports.add(Optional.of(report(4L)));
    diagnostics.reports.add(Optional.empty());

    final ChaosAssertions.ScenarioAssert scenario =
        ChaosAssertions.assertThat(diagnostics).scenario("fixed");
    scenario.matched().exactly(4);

    assertThatThrownBy(() -> scenario.matched().exactly(4))
        .isInstanceOf(AssertionFailedError.class)
        .hasMessage("scenario fixed is gone");
  }

  private static ChaosDiagnostics.ScenarioReport report(final long matched) {
    return new ChaosDiagnostics.ScenarioReport(
        "fixed",
        "",
        "jvm",
        ChaosScenario.ScenarioScope.JVM,
        ChaosDiagnostics.ScenarioState.ACTIVE,
        matched,
        0L,
        "");
  }

  private static ChaosActivationHandle handle(
      final ChaosDiagnostics.ScenarioState state, final long matched, final long applied) {
    return new ChaosActivationHandle() {
      @Override
      public String id() {
        return "fixed";
      }

      @Override
      public void start() {}

      @Override
      public void stop() {}

      @Override
      public void release() {}

      @Override
      public ChaosDiagnostics.ScenarioState state() {
        return state;
      }

      @Override
      public long matchedCount() {
        return matched;
      }

      @Override
      public long appliedCount() {
        return applied;
      }
    };
  }

  /** Diagnostics with fixed failures, answering scenario lookups from a queue of reports. */
  private static final class FixedDiagnostics implements ChaosDiagnostics {
    private final List<ActivationFailure> failures;
    private final Deque<Optional<ScenarioReport>> reports = new ArrayDeque<>();

    FixedDiagnostics(final List<ActivationFailure> failures) {
      this.failures = failures;
    }

    @Override
    public Snapshot snapshot() {
      return new Snapshot(Instant.EPOCH, List.of(), failures, Map.of());
    }

    @Override
    public Optional<ScenarioReport> scenario(final String scenarioId) {
      return reports.isEmpty() ? Optional.empty() : reports.poll();
    }

    @Override
    public String debugDump() {
      return "";
    }
  }
}

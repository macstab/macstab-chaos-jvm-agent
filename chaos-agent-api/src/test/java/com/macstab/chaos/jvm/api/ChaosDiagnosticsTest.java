package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosDiagnostics")
class ChaosDiagnosticsTest {

  private static ChaosDiagnostics.ScenarioReport report(final long matched, final long applied) {
    return new ChaosDiagnostics.ScenarioReport(
        "s",
        "d",
        "jvm",
        ChaosScenario.ScenarioScope.JVM,
        ChaosDiagnostics.ScenarioState.ACTIVE,
        matched,
        applied,
        null);
  }

  @Nested
  @DisplayName("Snapshot")
  class SnapshotTests {

    @Test
    @DisplayName("missing collections become empty")
    void nullsBecomeEmpty() {
      final ChaosDiagnostics.Snapshot snapshot =
          new ChaosDiagnostics.Snapshot(Instant.EPOCH, null, null, null);

      assertThat(snapshot.scenarios()).isEmpty();
      assertThat(snapshot.failures()).isEmpty();
      assertThat(snapshot.runtimeDetails()).isEmpty();
    }

    @Test
    @DisplayName("given collections are kept as copies")
    void keepsCopies() {
      final ChaosDiagnostics.ScenarioReport report = report(2, 1);
      final ChaosDiagnostics.ActivationFailure failure =
          new ChaosDiagnostics.ActivationFailure(
              "s", ChaosDiagnostics.FailureCategory.INTERNAL_ERROR, "boom");

      final ChaosDiagnostics.Snapshot snapshot =
          new ChaosDiagnostics.Snapshot(
              Instant.EPOCH, List.of(report), List.of(failure), Map.of("jdk", "21"));

      assertThat(snapshot.scenarios()).containsExactly(report);
      assertThat(snapshot.failures()).containsExactly(failure);
      assertThat(snapshot.runtimeDetails()).containsExactly(Map.entry("jdk", "21"));
    }

    @Test
    @DisplayName("the capture time is required")
    void capturedAtRequired() {
      assertThatThrownBy(() -> new ChaosDiagnostics.Snapshot(null, null, null, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("ScenarioReport")
  class ScenarioReportTests {

    @Test
    @DisplayName("zero counters and applied equal to matched are valid")
    void boundaries() {
      assertThatCode(() -> report(0, 0)).doesNotThrowAnyException();
      assertThatCode(() -> report(5, 5)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a negative matched count is rejected")
    void negativeMatched() {
      assertThatThrownBy(() -> report(-1, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("matchedCount must be >= 0, got -1");
    }

    @Test
    @DisplayName("a negative applied count is rejected")
    void negativeApplied() {
      assertThatThrownBy(() -> report(0, -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("appliedCount must be >= 0, got -1");
    }

    @Test
    @DisplayName("more applications than matches is rejected")
    void appliedAboveMatched() {
      assertThatThrownBy(() -> report(3, 4))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("appliedCount (4) must be <= matchedCount (3)");
    }
  }

  @Nested
  @DisplayName("ActivationFailure")
  class ActivationFailureTests {

    @Test
    @DisplayName("a missing message reads as a stable placeholder")
    void nullMessage() {
      assertThat(
              new ChaosDiagnostics.ActivationFailure(
                      "s", ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT, null)
                  .message())
          .isEqualTo("(no detail)");
    }

    @Test
    @DisplayName("a given message is kept")
    void givenMessage() {
      assertThat(
              new ChaosDiagnostics.ActivationFailure(
                      "s", ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME, "no JFR")
                  .message())
          .isEqualTo("no JFR");
    }
  }
}

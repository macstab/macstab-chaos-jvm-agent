package com.macstab.chaos.jvm.testkit;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.opentest4j.AssertionFailedError;

/**
 * Assertions on chaos activity, for tests that check a scenario actually fired (or never did).
 *
 * <pre>{@code
 * ChaosAssertions.assertThat(handle).applied().atLeast(3);
 * ChaosAssertions.assertThat(handle).applied().within(Duration.ofSeconds(2)).atLeast(1);
 * ChaosAssertions.assertThat(chaos.diagnostics()).hasNoFailures();
 * ChaosAssertions.assertThat(chaos.diagnostics()).scenario("slow-db").matched().never();
 * }</pre>
 */
public final class ChaosAssertions {

  private ChaosAssertions() {}

  /**
   * Starts assertions on an activation handle.
   *
   * @param handle the handle
   * @return the assertions
   */
  public static ScenarioAssert assertThat(final ChaosActivationHandle handle) {
    Objects.requireNonNull(handle, "handle");
    return new ScenarioAssert(
        handle.id(), handle::appliedCount, handle::matchedCount, handle::state);
  }

  /**
   * Starts assertions on diagnostics.
   *
   * @param diagnostics the diagnostics
   * @return the assertions
   */
  public static DiagnosticsAssert assertThat(final ChaosDiagnostics diagnostics) {
    return new DiagnosticsAssert(Objects.requireNonNull(diagnostics, "diagnostics"));
  }

  /** Assertions on one scenario. */
  public static final class ScenarioAssert {
    private final String id;
    private final LongSupplier applied;
    private final LongSupplier matched;
    private final Supplier<ChaosDiagnostics.ScenarioState> state;

    ScenarioAssert(
        final String id,
        final LongSupplier applied,
        final LongSupplier matched,
        final Supplier<ChaosDiagnostics.ScenarioState> state) {
      this.id = id;
      this.applied = applied;
      this.matched = matched;
      this.state = state;
    }

    /**
     * Asserts on how often the effect took effect.
     *
     * @return the count assertions
     */
    public CountAssert applied() {
      return new CountAssert("applications of " + id, applied, Duration.ZERO);
    }

    /**
     * Asserts on how often the selector matched.
     *
     * @return the count assertions
     */
    public CountAssert matched() {
      return new CountAssert("matches of " + id, matched, Duration.ZERO);
    }

    /**
     * Asserts the scenario is in {@code expected}.
     *
     * @param expected the expected state
     * @return this
     */
    public ScenarioAssert hasState(final ChaosDiagnostics.ScenarioState expected) {
      final ChaosDiagnostics.ScenarioState actual = state.get();
      if (actual != expected) {
        throw new AssertionFailedError(
            "expected scenario " + id + " to be " + expected + " but was " + actual,
            expected,
            actual);
      }
      return this;
    }
  }

  /** Assertions on diagnostics. */
  public static final class DiagnosticsAssert {
    private final ChaosDiagnostics diagnostics;

    DiagnosticsAssert(final ChaosDiagnostics diagnostics) {
      this.diagnostics = diagnostics;
    }

    /**
     * Asserts that no activation failed since the agent started. In a JVM shared by many tests,
     * prefer {@link #hasNoFailuresFor(String...)}.
     *
     * @return this
     */
    public DiagnosticsAssert hasNoFailures() {
      final List<ChaosDiagnostics.ActivationFailure> failures = diagnostics.snapshot().failures();
      if (!failures.isEmpty()) {
        throw new AssertionFailedError("expected no activation failures but found " + failures);
      }
      return this;
    }

    /**
     * Asserts that no activation of the given scenario ids failed.
     *
     * @param ids the scenario ids
     * @return this
     */
    public DiagnosticsAssert hasNoFailuresFor(final String... ids) {
      final java.util.Set<String> wanted = java.util.Set.of(ids);
      final List<ChaosDiagnostics.ActivationFailure> failures =
          diagnostics.snapshot().failures().stream()
              .filter(failure -> wanted.contains(failure.scenarioId()))
              .toList();
      if (!failures.isEmpty()) {
        throw new AssertionFailedError("expected no activation failures but found " + failures);
      }
      return this;
    }

    /**
     * Asserts on the scenario with {@code id}.
     *
     * @param id the scenario id
     * @return the scenario assertions
     * @throws AssertionFailedError if no scenario has that id
     */
    public ScenarioAssert scenario(final String id) {
      if (diagnostics.scenario(id).isEmpty()) {
        throw new AssertionFailedError("no scenario with id " + id + " is registered");
      }
      return new ScenarioAssert(
          id,
          () -> report(id).appliedCount(),
          () -> report(id).matchedCount(),
          () -> report(id).state());
    }

    private ChaosDiagnostics.ScenarioReport report(final String id) {
      return diagnostics
          .scenario(id)
          .orElseThrow(() -> new AssertionFailedError("scenario " + id + " is gone"));
    }
  }

  /** Assertions on a count; {@link #within} makes them wait for asynchronous effects. */
  public static final class CountAssert {
    private final String description;
    private final LongSupplier count;
    private final Duration timeout;

    CountAssert(final String description, final LongSupplier count, final Duration timeout) {
      this.description = description;
      this.count = count;
      this.timeout = timeout;
    }

    /**
     * Returns assertions that retry until they hold or {@code timeout} elapses.
     *
     * @param timeout how long to wait
     * @return the waiting assertions
     */
    public CountAssert within(final Duration timeout) {
      return new CountAssert(description, count, timeout);
    }

    /**
     * Asserts the count is at least {@code minimum}.
     *
     * @param minimum the smallest accepted count
     */
    public void atLeast(final long minimum) {
      check(actual -> actual >= minimum, "at least " + minimum);
    }

    /**
     * Asserts the count is at most {@code maximum}. Checked at once, not waited for.
     *
     * @param maximum the largest accepted count
     */
    public void atMost(final long maximum) {
      checkNow(actual -> actual <= maximum, "at most " + maximum);
    }

    /**
     * Asserts the count is exactly {@code expected}.
     *
     * @param expected the expected count
     */
    public void exactly(final long expected) {
      check(actual -> actual == expected, "exactly " + expected);
    }

    /** Asserts the count is zero. Checked at once, not waited for. */
    public void never() {
      checkNow(actual -> actual == 0L, "never (0)");
    }

    private void check(final java.util.function.LongPredicate accepted, final String expectation) {
      final long deadline = System.nanoTime() + timeout.toNanos();
      long actual = count.getAsLong();
      while (!accepted.test(actual) && System.nanoTime() < deadline) {
        try {
          Thread.sleep(10L);
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
        actual = count.getAsLong();
      }
      fail(accepted, expectation, actual);
    }

    private void checkNow(
        final java.util.function.LongPredicate accepted, final String expectation) {
      fail(accepted, expectation, count.getAsLong());
    }

    private void fail(
        final java.util.function.LongPredicate accepted,
        final String expectation,
        final long actual) {
      if (!accepted.test(actual)) {
        throw new AssertionFailedError(
            "expected "
                + description
                + " to be "
                + expectation
                + " but was "
                + actual
                + (timeout.isZero() ? "" : " after waiting " + timeout));
      }
    }
  }
}

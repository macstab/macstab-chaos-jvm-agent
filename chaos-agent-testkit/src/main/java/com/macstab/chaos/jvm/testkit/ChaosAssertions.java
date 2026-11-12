package com.macstab.chaos.jvm.testkit;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
 *
 * <p>A scenario is named by its key, {@code <scopeKey>::<id>} ({@code jvm::slow-db}, {@code
 * session:<id>::slow-db}), in every message. A count failure carries the expected bound and the
 * actual count. The waiting of {@link CountAssert#within} is invisible to scenarios: a {@code
 * THREAD_SLEEP} scenario does not see the assertion's own pauses.
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
      Objects.requireNonNull(expected, "expected");
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
     * prefer {@link #hasNoFailuresFor(String, String...)}.
     *
     * @return this
     */
    public DiagnosticsAssert hasNoFailures() {
      return assertNoFailures(failure -> true);
    }

    /**
     * Asserts that no activation of the given scenario ids failed. An id given twice counts once.
     *
     * @param id a scenario id
     * @param more further scenario ids
     * @return this
     */
    public DiagnosticsAssert hasNoFailuresFor(final String id, final String... more) {
      final Set<String> wanted = new HashSet<>();
      wanted.add(Objects.requireNonNull(id, "id"));
      for (final String other : more) {
        wanted.add(Objects.requireNonNull(other, "id"));
      }
      return assertNoFailures(failure -> wanted.contains(failure.scenarioId()));
    }

    private DiagnosticsAssert assertNoFailures(
        final Predicate<ChaosDiagnostics.ActivationFailure> counted) {
      final List<ChaosDiagnostics.ActivationFailure> failures =
          diagnostics.snapshot().failures().stream().filter(counted).toList();
      if (!failures.isEmpty()) {
        throw new AssertionFailedError("expected no activation failures but found " + failures);
      }
      return this;
    }

    /**
     * Asserts on the scenario with {@code id}, named {@code <scopeKey>::<id>} in messages.
     *
     * @param id the scenario id
     * @return the scenario assertions
     * @throws AssertionFailedError if no scenario has that id
     */
    public ScenarioAssert scenario(final String id) {
      Objects.requireNonNull(id, "id");
      final ChaosDiagnostics.ScenarioReport found =
          diagnostics
              .scenario(id)
              .orElseThrow(
                  () -> new AssertionFailedError("no scenario with id " + id + " is registered"));
      final String key = found.scopeKey() + "::" + id;
      return new ScenarioAssert(
          key,
          () -> report(id, key).appliedCount(),
          () -> report(id, key).matchedCount(),
          () -> report(id, key).state());
    }

    private ChaosDiagnostics.ScenarioReport report(final String id, final String key) {
      return diagnostics
          .scenario(id)
          .orElseThrow(() -> new AssertionFailedError("scenario " + key + " is gone"));
    }
  }

  /**
   * Assertions on a count. Without {@link #within} each one checks the count once. With {@code
   * within(t)}, {@link #atLeast} and {@link #exactly} wait up to {@code t} for the count to reach
   * the bound, and {@link #atMost} and {@link #never} hold for the whole of {@code t}: they fail as
   * soon as the count passes the bound.
   */
  public static final class CountAssert {
    private static final long POLL_MILLIS = 10L;

    /** The boot loader's copy of the bridge, present while the agent is installed. */
    private static final String DISPATCHER =
        "com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher";

    /** Set once the bridge is found on the boot path; it is never removed again. */
    private static volatile boolean bridgeOnBootPath;

    private final String description;
    private final LongSupplier count;
    private final Duration timeout;

    CountAssert(final String description, final LongSupplier count, final Duration timeout) {
      this.description = description;
      this.count = count;
      this.timeout = timeout;
    }

    /**
     * Returns assertions that wait for, or hold for, {@code timeout}.
     *
     * @param timeout how long to wait; any non-negative duration
     * @return the waiting assertions
     * @throws IllegalArgumentException if {@code timeout} is negative
     */
    public CountAssert within(final Duration timeout) {
      Objects.requireNonNull(timeout, "timeout");
      if (timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must not be negative: " + timeout);
      }
      return new CountAssert(description, count, timeout);
    }

    /**
     * Asserts the count is at least {@code minimum}, waiting for it under {@link #within}.
     *
     * @param minimum the smallest accepted count, not negative
     */
    public void atLeast(final long minimum) {
      eventually(actual -> actual >= minimum, "at least " + bound(minimum));
    }

    /**
     * Asserts the count is at most {@code maximum}, for the whole duration under {@link #within}.
     *
     * @param maximum the largest accepted count, not negative
     */
    public void atMost(final long maximum) {
      holds(actual -> actual <= maximum, "at most " + bound(maximum));
    }

    /**
     * Asserts the count is exactly {@code expected}, waiting for it under {@link #within}.
     *
     * @param expected the expected count, not negative
     */
    public void exactly(final long expected) {
      eventually(actual -> actual == expected, "exactly " + bound(expected));
    }

    /** Asserts the count is zero, for the whole duration under {@link #within}. */
    public void never() {
      holds(actual -> actual == 0L, "never (0)");
    }

    private static long bound(final long bound) {
      if (bound < 0L) {
        throw new IllegalArgumentException("the bound must not be negative: " + bound);
      }
      return bound;
    }

    private void eventually(final LongPredicate accepted, final String expectation) {
      final long started = System.nanoTime();
      final long limit = nanos(timeout);
      long actual = count.getAsLong();
      while (!accepted.test(actual)
          && System.nanoTime() - started < limit
          && pause(bridgeOnBootPath())) {
        actual = count.getAsLong();
      }
      if (!accepted.test(actual)) {
        throw failure(expectation, actual, " after waiting ");
      }
    }

    private void holds(final LongPredicate accepted, final String expectation) {
      final long started = System.nanoTime();
      final long limit = nanos(timeout);
      long actual = count.getAsLong();
      while (accepted.test(actual) && System.nanoTime() - started < limit) {
        if (!pause(bridgeOnBootPath())) {
          throw new AssertionFailedError(
              "interrupted while checking that " + description + " stays " + expectation);
        }
        actual = count.getAsLong();
      }
      if (!accepted.test(actual)) {
        throw failure(expectation, actual, " within ");
      }
    }

    private AssertionFailedError failure(
        final String expectation, final long actual, final String waited) {
      return new AssertionFailedError(
          "expected "
              + description
              + " to be "
              + expectation
              + " but was "
              + actual
              + (timeout.isZero() ? "" : waited + timeout),
          expectation,
          actual);
    }

    /** The timeout in nanoseconds, saturated: a timeout of centuries waits "forever". */
    private static long nanos(final Duration timeout) {
      try {
        return timeout.toNanos();
      } catch (final ArithmeticException tooLong) {
        return Long.MAX_VALUE;
      }
    }

    /**
     * Sleeps one poll interval; as agent-internal work, so scenarios do not see it, when the agent
     * is installed.
     *
     * @param internal whether the bridge is on the boot path
     * @return {@code false} if interrupted (the interrupt is kept)
     */
    static boolean pause(final boolean internal) {
      if (internal) {
        BootstrapDispatcher.beginInternalCall();
      }
      try {
        Thread.sleep(POLL_MILLIS);
        return true;
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      } finally {
        if (internal) {
          BootstrapDispatcher.endInternalCall();
        }
      }
    }

    /**
     * Whether the agent's bridge is on the boot path. Without the agent nothing is hooked and the
     * dispatcher is never linked, so a test without the agent needs no instrumentation class.
     */
    private static boolean bridgeOnBootPath() {
      if (!bridgeOnBootPath) {
        bridgeOnBootPath = onBootPath(DISPATCHER);
      }
      return bridgeOnBootPath;
    }

    /**
     * Whether the boot loader has {@code className}; loads nothing else.
     *
     * @param className the binary class name
     * @return {@code true} when the boot loader finds it
     */
    static boolean onBootPath(final String className) {
      try {
        Class.forName(className, false, null);
        return true;
      } catch (final ClassNotFoundException absent) {
        return false;
      }
    }
  }
}

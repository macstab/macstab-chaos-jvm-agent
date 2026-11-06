package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Clock skew of the JVM clock reads: {@code System} call sites, {@code java.time}, the system
 * {@code Clock} and {@code java.util.Date}.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class ClockDispatch {

  private final DispatchEngine engine;

  ClockDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Applies any active clock-skew scenarios to a raw clock reading and returns the (possibly)
   * skewed value.
   *
   * @param realValue the real clock value read from the JVM
   * @param clockType the {@link OperationType} identifying which clock source was read
   * @return the (possibly skewed) clock value to return to the caller
   */
  public long applyClockSkew(final long realValue, final OperationType clockType) {
    // Clock-skew reads must NOT go through engine.registry.match() → ScenarioController.evaluate().
    // engine.evaluate() increments matchedCount, consumes rate-limit permits, rolls the probability
    // die, and decrements maxApplications — all designed for application-controlled events
    // (sleeps, network reads, lock acquires), not for JVM-internal clock calls that fire
    // thousands of times per second. Routing through engine.evaluate() would exhaust any activation
    // policy budget within milliseconds. Instead, iterate controllers directly: check started
    // + selector + ClockSkewEffect without touching policy accounting.
    final ScenarioController[] candidates = engine.registry.candidates(clockType);
    if (candidates.length == 0) {
      return realValue;
    }
    final InvocationContext context = context(clockType);
    final boolean isNanos = clockType == OperationType.SYSTEM_CLOCK_NANOS;
    long result = realValue;
    for (final ScenarioController controller : candidates) {
      final ClockSkewState state = controller.clockSkewState();
      if (state == null || !applies(controller, context)) {
        continue;
      }
      final ChaosEffect.ClockSkewMode mode = mode(controller);
      result = isNanos ? state.applyNanos(mode, result) : state.applyMillis(mode, result);
    }
    return result;
  }

  /**
   * Applies any active clock-skew scenarios to an instant read, to the nanosecond.
   *
   * @param realInstant the instant the clock read
   * @param clockType the {@link OperationType} identifying which clock source was read
   * @return the (possibly skewed) instant
   */
  private java.time.Instant skewInstant(
      final java.time.Instant realInstant, final OperationType clockType) {
    // See applyClockSkew: clock reads bypass engine.evaluate() and its policy accounting.
    final ScenarioController[] candidates = engine.registry.candidates(clockType);
    if (candidates.length == 0) {
      return realInstant;
    }
    final InvocationContext context = context(clockType);
    java.time.Instant result = realInstant;
    for (final ScenarioController controller : candidates) {
      final ClockSkewState state = controller.clockSkewState();
      if (state != null && applies(controller, context)) {
        result = state.applyInstant(mode(controller), result);
      }
    }
    return result;
  }

  private static InvocationContext context(final OperationType clockType) {
    return new InvocationContext(
        clockType, "java.lang.System", null, null, false, null, null, null);
  }

  private static ChaosEffect.ClockSkewMode mode(final ScenarioController controller) {
    return ((ChaosEffect.ClockSkewEffect) controller.scenario().effect()).mode();
  }

  private boolean applies(final ScenarioController controller, final InvocationContext context) {
    // The time bounds (activeFor, activeFrom, activeUntil) apply to a clock skew like to any
    // scenario. They are checked here because clock reads bypass engine.evaluate().
    if (!controller.withinWindow()
        || !SelectorMatcher.matches(controller.scenario().selector(), context)) {
      return false;
    }
    // A session-scoped clock skew applies only on threads bound to its session, never to every
    // thread in the JVM. The current session is looked up only when a session-scoped skew is
    // active, to keep the common clock-read path free of the lookup.
    final String sessionId = controller.sessionId();
    return sessionId == null || sessionId.equals(engine.scopeContext.currentSessionId());
  }

  /**
   * Returns the (possibly skewed) millisecond timestamp for {@link System#currentTimeMillis()}.
   *
   * @param realMillis the real value returned by {@link System#currentTimeMillis()}
   * @return the (possibly skewed) millisecond timestamp to return to the caller
   */
  public long adjustClockMillis(final long realMillis) {
    return applyClockSkew(realMillis, OperationType.SYSTEM_CLOCK_MILLIS);
  }

  /**
   * Returns the (possibly skewed) nanosecond timestamp for {@link System#nanoTime()}.
   *
   * @param realNanos the real value returned by {@link System#nanoTime()}
   * @return the (possibly skewed) nanosecond timestamp to return to the caller
   */
  public long adjustClockNanos(final long realNanos) {
    return applyClockSkew(realNanos, OperationType.SYSTEM_CLOCK_NANOS);
  }

  /**
   * Returns the (possibly skewed) {@link java.time.Instant} for {@code Instant.now()}.
   *
   * @param realInstant the real instant produced by {@code Instant.now()}
   * @return the (possibly skewed) instant to return to the caller
   */
  public java.time.Instant adjustInstantNow(final java.time.Instant realInstant) {
    return skewInstant(realInstant, OperationType.INSTANT_NOW);
  }

  /**
   * Returns the (possibly skewed) instant a read of the system {@link java.time.Clock} returns.
   *
   * @param realInstant the instant the clock read
   * @return the (possibly skewed) instant
   */
  public java.time.Instant adjustClockInstant(final java.time.Instant realInstant) {
    return skewInstant(realInstant, OperationType.CLOCK_READ);
  }

  /**
   * Returns the (possibly skewed) milliseconds a {@code millis()} read of the system {@link
   * java.time.Clock} returns.
   *
   * @param realMillis the milliseconds the clock read
   * @return the (possibly skewed) milliseconds
   */
  public long adjustClockReadMillis(final long realMillis) {
    return applyClockSkew(realMillis, OperationType.CLOCK_READ);
  }

  /**
   * Returns the (possibly skewed) {@link java.time.LocalDateTime} for {@code LocalDateTime.now()}.
   *
   * @param realValue the real local-date-time produced by {@code LocalDateTime.now()}
   * @return the (possibly skewed) local-date-time to return to the caller
   */
  public java.time.LocalDateTime adjustLocalDateTimeNow(final java.time.LocalDateTime realValue) {
    return adjustLocalDateTimeNowInZone(realValue, java.time.ZoneId.systemDefault());
  }

  /**
   * Returns the (possibly skewed) {@link java.time.LocalDateTime} for {@code
   * LocalDateTime.now(ZoneId)}.
   *
   * <p>The skew is applied to the real instant to the nanosecond; rebuilding the value from epoch
   * milliseconds would drop its sub-millisecond part.
   *
   * @param realValue the real local date-time
   * @param zone the zone the local date-time was produced in
   * @return the (possibly skewed) local date-time
   */
  public java.time.LocalDateTime adjustLocalDateTimeNowInZone(
      final java.time.LocalDateTime realValue, final java.time.ZoneId zone) {
    final java.time.Instant realInstant = realValue.atZone(zone).toInstant();
    final java.time.Instant skewed = skewInstant(realInstant, OperationType.LOCAL_DATE_TIME_NOW);
    return skewed.equals(realInstant) ? realValue : java.time.LocalDateTime.ofInstant(skewed, zone);
  }

  /**
   * Returns the (possibly skewed) {@link java.time.ZonedDateTime} for {@code ZonedDateTime.now()}.
   *
   * @param realValue the real zoned-date-time produced by {@code ZonedDateTime.now()}
   * @return the (possibly skewed) zoned-date-time to return to the caller
   */
  public java.time.ZonedDateTime adjustZonedDateTimeNow(final java.time.ZonedDateTime realValue) {
    final java.time.Instant realInstant = realValue.toInstant();
    final java.time.Instant skewed = skewInstant(realInstant, OperationType.ZONED_DATE_TIME_NOW);
    return skewed.equals(realInstant) ? realValue : skewed.atZone(realValue.getZone());
  }

  /**
   * Returns the (possibly skewed) millis value used when constructing a new {@link java.util.Date}.
   *
   * @param realMillis the real millis argument passed to {@code new Date(long)}
   * @return the (possibly skewed) millis value to use for the constructed {@link java.util.Date}
   */
  public long adjustDateNew(final long realMillis) {
    return applyClockSkew(realMillis, OperationType.DATE_NEW);
  }
}

package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} classes for clock reads ({@code java.time} {@code now()} methods, the
 * system {@code Clock} and {@code new Date()}) for clock-skew injection.
 *
 * <p>Each nested class targets one method or overload family. The outer class is not instantiated;
 * it exists only as a namespace.
 */
final class ClockAdvice {
  private ClockAdvice() {}

  // ── A1: Clock ─────────────────────────────────────────────────────────────

  /**
   * Rewrites the return value of {@link java.time.Instant#now()} for clock-skew injection.
   *
   * <p>{@code Instant.now()} is a regular Java static method that delegates to {@code
   * Clock.systemUTC().instant()}, so unlike {@link System#currentTimeMillis()} it is neither {@code
   * native} nor {@code @IntrinsicCandidate} and can be instrumented at exit.
   */
  static final class InstantNowAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) java.time.Instant returned) throws Throwable {
      returned = BootstrapDispatcher.adjustInstantNow(returned);
    }
  }

  /**
   * Rewrites the return value of {@link java.time.LocalDateTime#now()} (no-argument variant) for
   * clock-skew injection.
   */
  static final class LocalDateTimeNowAdvice {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLOCK_API);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Thrown final Throwable thrown,
        @Advice.Return(readOnly = false) java.time.LocalDateTime returned)
        throws Throwable {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLOCK_API);
      if (thrown == null) {
        returned = BootstrapDispatcher.adjustLocalDateTimeNow(returned);
      }
    }
  }

  /**
   * Rewrites the return value of {@link java.time.LocalDateTime#now(java.time.ZoneId)} for
   * clock-skew injection, mapping through the zone it was called with.
   */
  static final class LocalDateTimeNowInZoneAdvice {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLOCK_API);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Argument(0) final java.time.ZoneId zone,
        @Advice.Thrown final Throwable thrown,
        @Advice.Return(readOnly = false) java.time.LocalDateTime returned)
        throws Throwable {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLOCK_API);
      if (thrown == null) {
        returned = BootstrapDispatcher.adjustLocalDateTimeNowInZone(returned, zone);
      }
    }
  }

  /**
   * Rewrites the return value of {@link java.time.ZonedDateTime#now()} and {@link
   * java.time.ZonedDateTime#now(java.time.ZoneId)} for clock-skew injection.
   */
  static final class ZonedDateTimeNowAdvice {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLOCK_API);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Thrown final Throwable thrown,
        @Advice.Return(readOnly = false) java.time.ZonedDateTime returned)
        throws Throwable {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLOCK_API);
      if (thrown == null) {
        returned = BootstrapDispatcher.adjustZonedDateTimeNow(returned);
      }
    }
  }

  /** Rewrites {@code instant()} of the system clock and instant source (CLOCK_READ). */
  static final class SystemClockInstantAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) java.time.Instant returned) throws Throwable {
      returned = BootstrapDispatcher.adjustClockInstant(returned);
    }
  }

  /** Rewrites {@code millis()} of the system clock and instant source (CLOCK_READ). */
  static final class SystemClockMillisAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) long returned) throws Throwable {
      returned = BootstrapDispatcher.adjustClockReadMillis(returned);
    }
  }

  /**
   * Adjusts the embedded epoch-milliseconds of a freshly-constructed {@link java.util.Date} for
   * clock-skew injection.
   *
   * <p>{@link java.util.Date#Date()} calls {@link System#currentTimeMillis()} and stores it
   * internally. Direct interception of {@code currentTimeMillis()} is impossible (see {@code
   * JdkInstrumentationInstaller} for the JVM-level analysis), so we instead wrap the constructor
   * and overwrite the embedded value via {@link java.util.Date#setTime(long)} on exit.
   *
   * <p>The {@code self} parameter is typed as {@link Object} rather than {@link java.util.Date}:
   * {@code java.util.Date} is eagerly referenced during early JVM bootstrap by {@code
   * java.util.logging.SimpleFormatter}, and inlining an advice that names {@code Date} in its
   * signature can cause a {@link ClassCircularityError} if any logging happens while the
   * retransformed constructor body is still being verified by the JVM.
   */
  static final class DateNewAdvice {
    @SuppressWarnings("JavaUtilDate") // the advice skews java.util.Date itself
    @Advice.OnMethodExit
    static void exit(@Advice.This final Object self) throws Throwable {
      final java.util.Date date = (java.util.Date) self;
      final long realMillis = date.getTime();
      final long adjusted = BootstrapDispatcher.adjustDateNew(realMillis);
      if (adjusted != realMillis) {
        date.setTime(adjusted);
      }
    }
  }
}

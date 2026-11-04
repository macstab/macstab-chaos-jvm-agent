package com.macstab.chaos.jvm.core;

import org.jspecify.annotations.Nullable;

/**
 * A discriminated-union value describing what the chaos engine should do at the end of a decision's
 * execution.
 *
 * <p>Only one of the payload fields ({@code returnValue} or {@code throwable}) is meaningful for a
 * given {@link TerminalKind}:
 *
 * <ul>
 *   <li>{@link TerminalKind#THROW} — {@code throwable} contains the exception to throw; {@code
 *       returnValue} is {@code null}.
 *   <li>{@link TerminalKind#RETURN} — {@code returnValue} contains the value to return from the
 *       intercepted method; {@code throwable} is {@code null}.
 *   <li>{@link TerminalKind#SUPPRESS} — both payload fields are {@code null}; the intercepted call
 *       is skipped entirely and the advice returns its zero/null/false default.
 *   <li>{@link TerminalKind#COMPLETE_EXCEPTIONALLY} — {@code throwable} contains the exception to
 *       pass to {@link java.util.concurrent.CompletableFuture#completeExceptionally}; {@code
 *       returnValue} is {@code null}.
 *   <li>{@link TerminalKind#CORRUPT_RETURN} — {@code returnValue} contains the corrupted value that
 *       will replace the original return value post-exit; {@code throwable} is {@code null}.
 * </ul>
 *
 * @param kind the action to take; never {@code null}
 * @param returnValue the replacement return value for {@code RETURN} and {@code CORRUPT_RETURN}
 *     kinds, or {@code null} for other kinds
 * @param throwable the exception to inject for {@code THROW} and {@code COMPLETE_EXCEPTIONALLY}
 *     kinds, or {@code null} for other kinds
 * @param scenarioId the id of the scenario that produced this terminal; surfaced in fallback and
 *     diagnostic log messages so operators can correlate a fallback ("EMPTY → ZERO") back to a
 *     specific scenario. {@code null} if the action was synthesised outside a scenario context.
 */
record TerminalAction(
    TerminalKind kind,
    @Nullable Object returnValue,
    @Nullable Throwable throwable,
    @Nullable String scenarioId) {
  /** Backwards-compatible constructor used by call sites that do not have a scenario id. */
  TerminalAction(
      final TerminalKind kind,
      final @Nullable Object returnValue,
      final @Nullable Throwable throwable) {
    this(kind, returnValue, throwable, null);
  }

  /**
   * The number a {@code TIMEOUT} (the wait), {@code CAP_LENGTH} (the byte cap) or {@code
   * CORRUPT_READ} (the seed) action carries.
   *
   * @return the value as a {@code long}
   * @throws NullPointerException if this action carries no value, which would be an engine bug
   */
  long number() {
    return ((Number)
            java.util.Objects.requireNonNull(returnValue, () -> kind + " carries no value"))
        .longValue();
  }

  /**
   * The exception a {@code THROW}, {@code TIMEOUT} or {@code COMPLETE_EXCEPTIONALLY} action
   * carries.
   *
   * @return the exception to throw or complete with
   * @throws NullPointerException if this action carries none, which would be an engine bug
   */
  Throwable failure() {
    return java.util.Objects.requireNonNull(throwable, () -> kind + " carries no throwable");
  }
}

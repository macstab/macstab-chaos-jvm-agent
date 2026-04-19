package com.macstab.chaos.core;

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
    TerminalKind kind, Object returnValue, Throwable throwable, String scenarioId) {
  /** Backwards-compatible constructor used by call sites that do not have a scenario id. */
  TerminalAction(final TerminalKind kind, final Object returnValue, final Throwable throwable) {
    this(kind, returnValue, throwable, null);
  }
}

package com.macstab.chaos.core;

/**
 * The merged outcome of evaluating all active {@link ScenarioContribution}s against a single
 * intercepted invocation.
 *
 * <p>A {@code RuntimeDecision} is produced by {@code ChaosRuntime} after collecting and merging
 * contributions from every matching {@link ScenarioController}. The three components are applied in
 * order: delay first, then gate, then terminal action.
 *
 * @param delayMillis the total artificial delay to inject before the intercepted operation
 *     proceeds; {@code 0} means no delay; always non-negative
 * @param gateAction a {@link GateAction} describing a {@link ManualGate} to block on (and for how
 *     long), or {@code null} if no gate is active
 * @param terminalAction a {@link TerminalAction} to execute after the delay and gate, or {@code
 *     null} if the intercepted operation should proceed normally
 */
record RuntimeDecision(long delayMillis, GateAction gateAction, TerminalAction terminalAction) {

  /**
   * Returns a decision that has no effect: zero delay, no gate, and no terminal action.
   *
   * @return a {@code RuntimeDecision} that is a no-op; the same instance is returned each time
   */
  static RuntimeDecision none() {
    return new RuntimeDecision(0L, null, null);
  }
}

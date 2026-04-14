package io.macstab.chaos.core;

record RuntimeDecision(long delayMillis, GateAction gateAction, TerminalAction terminalAction) {
  static RuntimeDecision none() {
    return new RuntimeDecision(0L, null, null);
  }
}

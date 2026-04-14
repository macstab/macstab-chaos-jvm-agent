package io.macstab.chaos.core;

record TerminalAction(TerminalKind kind, Object returnValue, Throwable throwable) {}

package com.macstab.chaos.jvm.core;

import java.time.Duration;

/**
 * Describes a manual gate that should be acquired (blocking the intercepted thread) as part of a
 * chaos decision.
 *
 * <p>When a {@link RuntimeDecision} contains a non-null {@code GateAction}, {@code ChaosRuntime}
 * calls {@link ManualGate#await(java.time.Duration)} on the gate before executing any terminal
 * action. This allows an external actor (test code or an operator) to hold threads at a precise
 * point in the application until deliberately releasing them.
 *
 * @param gate the {@link ManualGate} to await; never {@code null}
 * @param maxBlock the maximum time to block waiting for the gate to open; a value of {@link
 *     java.time.Duration#ZERO} or {@link java.time.Duration#ofMillis(long) ofMillis(0)} means block
 *     indefinitely
 */
record GateAction(ManualGate gate, Duration maxBlock) {}

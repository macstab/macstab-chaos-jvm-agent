package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import java.time.Duration;

/**
 * The result of a single {@link ScenarioController} agreeing to participate in a chaos decision.
 *
 * <p>When {@link ScenarioController#evaluate(InvocationContext)} returns a non-null value, it
 * returns an instance of this record. {@link ScenarioRegistry#match(InvocationContext)} collects
 * all non-null contributions and returns them to {@code ChaosRuntime}, which merges them into a
 * single {@link RuntimeDecision}.
 *
 * @param controller the controller that produced this contribution; never {@code null}. Retained so
 *     that the runtime can call {@link ScenarioController#release()} after the gate is cleared.
 * @param scenario the immutable descriptor of the chaos scenario; never {@code null}
 * @param effect the specific {@link ChaosEffect} variant to apply (e.g. delay, exception,
 *     suppress); never {@code null}
 * @param delayMillis the artificial delay in milliseconds to inject before the effect; {@code 0}
 *     means no delay; always non-negative
 * @param gateTimeout the maximum time to block on a {@link ManualGate}, or {@code null} if no gate
 *     applies to this contribution
 */
record ScenarioContribution(
    ScenarioController controller,
    ChaosScenario scenario,
    ChaosEffect effect,
    long delayMillis,
    Duration gateTimeout) {}

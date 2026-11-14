package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

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
 * @param delayNanos the artificial delay in nanoseconds to inject before the effect; {@code 0}
 *     means no delay; always non-negative
 * @param gateTimeout the maximum time to block on a {@link ManualGate}, or {@code null} if no gate
 *     applies to this contribution
 * @param context the invocation the contribution was evaluated for; passed back to {@link
 *     ScenarioController#commitApplication(InvocationContext, long, long)} when the contribution
 *     takes effect
 * @param matchNumber the scenario's 1-based match number for this call
 * @param ratePermitWindow the rate-limit window the contribution's permit was taken in, returned
 *     with the reservation when the contribution does not take effect
 */
record ScenarioContribution(
    ScenarioController controller,
    ChaosScenario scenario,
    ChaosEffect effect,
    long delayNanos,
    @Nullable Duration gateTimeout,
    InvocationContext context,
    long matchNumber,
    long ratePermitWindow) {

  /** Records this contribution as applied. */
  void commit() {
    controller.commitApplication(context, delayNanos, matchNumber);
  }

  /** Returns this contribution's reservation without applying it. */
  void release() {
    controller.releaseApplication(ratePermitWindow);
  }
}

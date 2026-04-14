package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosScenario;
import java.time.Duration;

record ScenarioContribution(
    ScenarioController controller,
    ChaosScenario scenario,
    ChaosEffect effect,
    long delayMillis,
    Duration gateTimeout) {}

package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import java.time.Duration;

record ScenarioContribution(
    ScenarioController controller,
    ChaosScenario scenario,
    ChaosEffect effect,
    long delayMillis,
    Duration gateTimeout) {}

package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.startup.TrustedSources;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@value #PROPERTY} is {@code true} from a trusted property source: the endpoint
 * injects faults, so it follows the same trust rule as {@code macstab.chaos.enabled} ({@link
 * TrustedChaosEnablement}).
 */
final class OnTrustedActuatorEnablementCondition extends SpringBootCondition {

  /** The actuator switch. */
  static final String PROPERTY = "macstab.chaos.actuator.enabled";

  OnTrustedActuatorEnablementCondition() {}

  @Override
  public ConditionOutcome getMatchOutcome(
      final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    if (!(context.getEnvironment() instanceof ConfigurableEnvironment environment)) {
      return ConditionOutcome.noMatch("environment is not a ConfigurableEnvironment");
    }
    if (TrustedSources.isTrue(
        PROPERTY, TrustedChaosEnablement.trustedValue(environment, PROPERTY))) {
      return ConditionOutcome.match(PROPERTY + "=true");
    }
    return TrustedChaosEnablement.rejected(environment, PROPERTY)
        ? ConditionOutcome.noMatch(
            PROPERTY
                + " comes from an untrusted property source; set "
                + TrustedChaosEnablement.ALLOW_REMOTE_ENABLE_PROPERTY
                + "=true through a trusted source to allow it")
        : ConditionOutcome.noMatch(PROPERTY + " is not true");
  }
}

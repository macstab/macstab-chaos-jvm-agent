package com.macstab.chaos.jvm.spring.boot.common;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@link TrustedChaosEnablement} decides that chaos is enabled. Replaces a plain
 * {@code @ConditionalOnProperty(macstab.chaos.enabled)}, which honoured the flag from any property
 * source.
 */
public final class OnTrustedChaosEnablementCondition extends SpringBootCondition {

  /** Instantiated by Spring when the condition is evaluated. */
  public OnTrustedChaosEnablementCondition() {}

  @Override
  public ConditionOutcome getMatchOutcome(
      final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    if (!(context.getEnvironment() instanceof ConfigurableEnvironment environment)) {
      return ConditionOutcome.noMatch("environment is not a ConfigurableEnvironment");
    }
    return switch (TrustedChaosEnablement.evaluate(environment)) {
      case ENABLED -> ConditionOutcome.match(TrustedChaosEnablement.ENABLED_PROPERTY + "=true");
      case DISABLED ->
          ConditionOutcome.noMatch(TrustedChaosEnablement.ENABLED_PROPERTY + " is not true");
      case REJECTED_UNTRUSTED_SOURCE ->
          ConditionOutcome.noMatch(
              TrustedChaosEnablement.ENABLED_PROPERTY
                  + "=true comes from an untrusted property source; set "
                  + TrustedChaosEnablement.ALLOW_REMOTE_ENABLE_PROPERTY
                  + "=true through a trusted source to allow it");
    };
  }
}

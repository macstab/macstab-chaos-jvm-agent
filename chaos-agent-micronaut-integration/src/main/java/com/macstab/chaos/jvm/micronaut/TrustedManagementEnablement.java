package com.macstab.chaos.jvm.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * Switches the {@code /chaos} management endpoint on when {@value #PROPERTY} is true and comes from
 * a launch-time local source: system properties, environment variables, the command line, {@code
 * ApplicationContext.run(Map)}, the {@code application} and {@code application-<env>} files, the
 * files of {@code micronaut.config.files} and Micronaut Test's {@code @Property} values. From any
 * other source (a distributed configuration server) only when {@code
 * macstab.chaos.allow-remote-enable=true} comes from one of those.
 */
public final class TrustedManagementEnablement implements Condition {

  /** The endpoint's switch. */
  public static final String PROPERTY = "macstab.chaos.management.enabled";

  /** Default constructor invoked by Micronaut. */
  public TrustedManagementEnablement() {}

  @Override
  @SuppressWarnings("rawtypes") // Condition declares the raw type.
  public boolean matches(final ConditionContext context) {
    return context.getBeanContext() instanceof ApplicationContext application
        && TrustedSwitch.on(application.getEnvironment(), PROPERTY);
  }
}

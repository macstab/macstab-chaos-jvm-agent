package com.macstab.chaos.jvm.spring.boot.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers {@link ChaosSessionFilter} for the test starters, so requests a test sends with {@code
 * X-Chaos-Session} run in that test's chaos session. Running applications get the filter from
 * {@link ChaosAutoConfiguration} instead, only when chaos is enabled from a trusted source.
 */
@AutoConfiguration
@Import(ChaosSessionPropagationConfiguration.class)
public class ChaosSessionPropagationAutoConfiguration {

  /** Creates the auto-configuration. */
  public ChaosSessionPropagationAutoConfiguration() {}
}

package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link ChaosSessionFilter} in servlet applications, so requests carrying {@code
 * X-Chaos-Session} run in that chaos session. Independent of {@code macstab.chaos.enabled}: the
 * filter only joins sessions already open in this JVM, which only tests open. Switch it off with
 * {@code macstab.chaos.session-propagation.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@ConditionalOnProperty(
    prefix = "macstab.chaos.session-propagation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ChaosSessionPropagationAutoConfiguration {

  /** Creates the auto-configuration. */
  public ChaosSessionPropagationAutoConfiguration() {}

  /**
   * The filter joining a request to its chaos session; a servlet container registers {@code Filter}
   * beans for every request.
   *
   * @return the filter
   */
  @Bean
  @ConditionalOnMissingBean
  public ChaosSessionFilter chaosSessionFilter() {
    return new ChaosSessionFilter(ChaosSessionPropagationAutoConfiguration::installedControlPlane);
  }

  private static @Nullable ChaosControlPlane installedControlPlane() {
    try {
      return ChaosPlatform.current();
    } catch (final IllegalStateException notInstalled) {
      return null;
    }
  }
}

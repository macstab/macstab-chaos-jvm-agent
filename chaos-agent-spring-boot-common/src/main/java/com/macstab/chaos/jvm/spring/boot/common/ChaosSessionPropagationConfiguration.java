package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link ChaosSessionFilter} bean, in servlet applications. Imported by {@link
 * ChaosAutoConfiguration}, so a running application gets it only when chaos is enabled from a
 * trusted source, and by {@link ChaosSessionPropagationAutoConfiguration} of the test starters.
 * Switch it off with {@code macstab.chaos.session-propagation.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@ConditionalOnProperty(
    prefix = "macstab.chaos.session-propagation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ChaosSessionPropagationConfiguration {

  /** Creates the configuration. */
  public ChaosSessionPropagationConfiguration() {}

  /**
   * The filter joining a request to its chaos session; a servlet container registers {@code Filter}
   * beans for every request.
   *
   * @return the filter
   */
  @Bean
  @ConditionalOnMissingBean
  public ChaosSessionFilter chaosSessionFilter() {
    return new ChaosSessionFilter(ChaosSessionPropagationConfiguration::installedControlPlane);
  }

  private static @Nullable ChaosControlPlane installedControlPlane() {
    return ChaosPlatform.installed().orElse(null);
  }
}

package com.macstab.chaos.spring.boot3.test;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that exposes the {@link ChaosControlPlane} as a Spring bean when the chaos
 * extension classes are on the classpath.
 *
 * <p>Registered via {@code
 * META-INF/spring/org.springframework.boot.test.autoconfigure.ImportAutoConfiguration.imports}.
 */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnClass(ChaosAgentExtension.class)
public class ChaosTestAutoConfiguration {

  /** Default constructor invoked by Spring when the configuration is imported. */
  public ChaosTestAutoConfiguration() {}

  /**
   * Exposes the installed {@link ChaosControlPlane} as a Spring bean.
   *
   * @return the JVM-wide control plane
   */
  @Bean
  @ConditionalOnMissingBean(ChaosControlPlane.class)
  public ChaosControlPlane chaosControlPlane() {
    return ChaosPlatform.installLocally();
  }
}

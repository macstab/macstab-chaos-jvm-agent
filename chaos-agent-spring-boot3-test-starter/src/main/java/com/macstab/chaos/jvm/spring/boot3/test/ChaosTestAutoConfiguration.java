package com.macstab.chaos.jvm.spring.boot3.test;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that exposes the {@link ChaosControlPlane} as a Spring bean when the chaos
 * extension classes are on the classpath.
 *
 * <p>Registered via {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} — the standard
 * Spring Boot auto-configuration SPI. The earlier {@code
 * org.springframework.boot.test.autoconfigure.ImportAutoConfiguration.imports} layout is only
 * consulted when the test class carries {@code @ImportAutoConfiguration} (directly or via a slice
 * annotation like {@code @DataJpaTest}/{@code @WebMvcTest}), so the bean was silently absent from
 * plain {@code @SpringBootTest} and {@code @ChaosTest} contexts. Standard auto-config discovery
 * runs on every {@code @SpringBootTest}-driven context regardless of slice annotations, so the bean
 * is now reliably present for test code using {@code @Autowired ChaosControlPlane}.
 */
@AutoConfiguration
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
  @ConditionalOnProperty(name = "macstab.chaos.test.enabled", havingValue = "true")
  public ChaosControlPlane chaosControlPlane() {
    // ChaosAgentInitializer.initialize() has already called installLocally() and set
    // the guard property; current() returns the same singleton without a second attach.
    return ChaosPlatform.installLocally();
  }
}

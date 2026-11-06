package com.macstab.chaos.jvm.spring.boot4.test;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that exposes the {@link ChaosControlPlane} as a Spring bean when the chaos
 * extension classes are on the classpath.
 *
 * <p>Registered via {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} — the standard
 * Spring Boot auto-configuration SPI. The {@code
 * org.springframework.boot.test.autoconfigure.ImportAutoConfiguration.imports} layout is only
 * consulted when the test class carries {@code @ImportAutoConfiguration} (directly or via a slice
 * annotation like {@code @DataJpaTest}/{@code @WebMvcTest}), so registered there the bean would be
 * silently absent from plain {@code @SpringBootTest} and {@code @ChaosTest} contexts. Standard
 * auto-config discovery runs on every {@code @SpringBootTest}-driven context regardless of slice
 * annotations, so the bean is reliably present for test code using {@code @Autowired
 * ChaosControlPlane}.
 */
@AutoConfiguration
public class ChaosTestAutoConfiguration {

  /**
   * Property set in a context's environment by {@link ChaosAgentInitializer} for
   * {@code @ChaosTest}.
   */
  static final String TEST_ENABLED_PROPERTY = "macstab.chaos.test.enabled";

  /** Default constructor invoked by Spring when the configuration is imported. */
  public ChaosTestAutoConfiguration() {}

  /**
   * Exposes the installed {@link ChaosControlPlane} as a Spring bean. Scenarios activated through
   * it are tracked and released at the end of each test class, like those activated through the
   * {@link ChaosAgentExtension} parameter.
   *
   * @return the JVM-wide control plane, tracked
   */
  // destroyMethod = "": the control plane is the JVM-wide runtime shared by every test context;
  // Spring's inferred destroy method would close() it whenever any context shuts down.
  @Bean(destroyMethod = "")
  @ConditionalOnMissingBean(ChaosControlPlane.class)
  @ConditionalOnProperty(name = TEST_ENABLED_PROPERTY, havingValue = "true")
  public ChaosControlPlane chaosControlPlane() {
    return ChaosAgentExtension.contextPlane(ChaosPlatform.installLocally());
  }
}

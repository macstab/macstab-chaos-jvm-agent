package com.macstab.chaos.spring.boot4.test;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@link ApplicationContextInitializer} that ensures the chaos agent is installed before the Spring
 * application context is refreshed.
 *
 * <p>Registered as an initializer via {@code @SpringBootTest(initializers = ...)} or via the Spring
 * Boot 4 initializer discovery so that chaos interception is available for beans instantiated
 * during context startup.
 */
public final class ChaosAgentInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  /** Default constructor invoked by Spring when the initializer is discovered. */
  public ChaosAgentInitializer() {}

  @Override
  public void initialize(final ConfigurableApplicationContext applicationContext) {
    final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
    applicationContext.getBeanFactory().registerSingleton("chaosControlPlane", controlPlane);
  }
}

package com.macstab.chaos.jvm.spring.boot4.test;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
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
    // Install the JVM-wide agent before context refresh; see ChaosAgentInitializer in the
    // Boot 3 starter for the reasoning behind delegating bean exposure to the auto-config
    // rather than calling beanFactory.registerSingleton(...) here.
    System.setProperty("macstab.chaos.test.enabled", "true");
    ChaosPlatform.installLocally();
  }
}

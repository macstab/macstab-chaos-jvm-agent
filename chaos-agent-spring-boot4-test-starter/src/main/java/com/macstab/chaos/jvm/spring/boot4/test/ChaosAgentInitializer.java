package com.macstab.chaos.jvm.spring.boot4.test;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * {@link ApplicationContextInitializer} that ensures the chaos agent is installed before the Spring
 * application context is refreshed.
 *
 * <p>Applied by {@code @ChaosTest} (through its {@code @ContextConfiguration}), or registered with
 * {@code @SpringBootTest(initializers = ...)}, so that chaos interception is available for beans
 * instantiated during context startup.
 */
public final class ChaosAgentInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  /** Name of the per-context property source that opts the context in to chaos test support. */
  static final String TEST_ENABLED_SOURCE = "macstabChaosTestEnabled";

  /** Default constructor invoked by Spring when the initializer is discovered. */
  public ChaosAgentInitializer() {}

  @Override
  public void initialize(final ConfigurableApplicationContext applicationContext) {
    // Install the JVM-wide agent before context refresh; see ChaosAgentInitializer in the
    // Boot 3 starter for the reasoning behind delegating bean exposure to the auto-config
    // rather than calling beanFactory.registerSingleton(...) here.
    // The flag is added to this context's environment only. A JVM-wide system property would
    // leak into every later @SpringBootTest context, making bean presence depend on test order.
    applicationContext
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                TEST_ENABLED_SOURCE,
                Map.of(ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY, "true")));
    ChaosPlatform.installLocally();
  }
}

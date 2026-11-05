package com.macstab.chaos.jvm.spring.boot3.test;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * {@link ApplicationContextInitializer} that ensures the chaos agent is installed before the Spring
 * application context is refreshed.
 *
 * <p>Registered as an initializer via {@code @SpringBootTest(initializers = ...)} or via Spring
 * factories so that chaos interception is available for beans instantiated during context startup
 * (for example, {@code CommandLineRunner} beans used for integration fixtures).
 */
public final class ChaosAgentInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  /** Name of the per-context property source that opts the context in to chaos test support. */
  static final String TEST_ENABLED_SOURCE = "macstabChaosTestEnabled";

  /** Default constructor invoked by Spring when the initializer is discovered. */
  public ChaosAgentInitializer() {}

  @Override
  public void initialize(final ConfigurableApplicationContext applicationContext) {
    // Install the JVM-wide agent before context refresh so instrumentation is in place when
    // beans are instantiated. The resulting control plane is exposed as a Spring bean by
    // ChaosTestAutoConfiguration; this initializer does not call
    // beanFactory.registerSingleton("chaosControlPlane", ...). Such an early singleton lands in
    // the bean factory before @ConditionalOnMissingBean is evaluated, silently suppressing
    // the auto-config bean method and bypassing @Bean lifecycle processing (lifecycle
    // callbacks, dependency injection checks, BeanPostProcessor hooks). Worse, test code that
    // relies on repeatable bean definitions — e.g. @MockBean replacement, @Primary overrides,
    // or @TestConfiguration-time bean-name collisions — sees a pre-registered singleton it
    // cannot replace the normal way. Letting the auto-config produce the bean keeps the
    // lifecycle consistent and leaves the override paths open.
    // Signal to ChaosTestAutoConfiguration that the agent was explicitly requested so the
    // auto-config bean is only created in contexts where chaos is actually opted in. Without
    // this flag the auto-config's chaosControlPlane() would call installLocally() for every
    // @SpringBootTest context regardless of @ChaosTest, self-attaching the agent globally.
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

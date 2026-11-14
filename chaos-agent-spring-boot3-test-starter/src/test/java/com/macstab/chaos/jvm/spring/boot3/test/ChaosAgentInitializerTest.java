package com.macstab.chaos.jvm.spring.boot3.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;

@DisplayName("ChaosAgentInitializer (Spring Boot 3)")
class ChaosAgentInitializerTest {

  @Test
  @DisplayName("opts in only the initialized context, not the whole JVM")
  void optInIsScopedToTheContext() {
    // Arrange
    final String before = System.getProperty(ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY);
    try (GenericApplicationContext context = new GenericApplicationContext();
        GenericApplicationContext otherContext = new GenericApplicationContext()) {

      // Act
      new ChaosAgentInitializer().initialize(context);

      // Assert
      assertThat(
              context
                  .getEnvironment()
                  .getProperty(ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY))
          .isEqualTo("true");
      assertThat(
              otherContext
                  .getEnvironment()
                  .getProperty(ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY))
          .as("a context without @ChaosTest is not opted in")
          .isNull();
      assertThat(System.getProperty(ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY))
          .as("no JVM-wide system property is left behind")
          .isEqualTo(before);
    }
  }

  @Test
  @DisplayName("the control-plane bean is never closed with the test context")
  void controlPlaneBeanHasNoDestroyMethod() throws Exception {
    final Bean bean =
        ChaosTestAutoConfiguration.class.getMethod("chaosControlPlane").getAnnotation(Bean.class);
    assertThat(bean.destroyMethod()).isEmpty();
  }
}

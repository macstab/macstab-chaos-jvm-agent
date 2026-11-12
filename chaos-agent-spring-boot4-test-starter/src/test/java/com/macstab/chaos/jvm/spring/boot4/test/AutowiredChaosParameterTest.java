package com.macstab.chaos.jvm.spring.boot4.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestConstructor;

/**
 * Spring resolves the parameters it autowires, a {@link ChaosControlPlane} included: the chaos
 * extension leaves them to it instead of competing for them.
 */
@ChaosTest(classes = AutowiredChaosParameterTest.App.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("A ChaosControlPlane parameter Spring autowires")
class AutowiredChaosParameterTest {

  /** The test application: the auto-configurations on the class path, chaos included. */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {}

  private final ChaosControlPlane constructorPlane;

  AutowiredChaosParameterTest(final ChaosControlPlane constructorPlane) {
    this.constructorPlane = constructorPlane;
  }

  @Test
  @DisplayName("is injected into an autowired test constructor and an @Autowired method parameter")
  void injectedBySpring(@Autowired final ChaosControlPlane methodPlane) {
    assertThat(constructorPlane).isNotNull().isSameAs(methodPlane);
  }
}

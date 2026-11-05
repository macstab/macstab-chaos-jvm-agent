package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The factory hands out the JVM-wide control plane and attaches the agent only when enabled. */
@DisplayName("ChaosFactory")
class ChaosFactoryTest {

  @Test
  @DisplayName("enabled, it produces the control plane installed in this JVM")
  void enabledProducesTheInstalledControlPlane() {
    final String previous = System.getProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "true");
    try {
      assertThat(new ChaosFactory().chaosControlPlane()).isSameAs(ChaosPlatform.current());
    } finally {
      restore(previous);
    }
  }

  @Test
  @DisplayName("disabled, it produces an agent that is already installed")
  void disabledProducesTheAlreadyInstalledAgent() {
    final var installed = ChaosPlatform.installLocally();
    final String previous = System.getProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "false");
    try {
      assertThat(new ChaosFactory().chaosControlPlane()).isSameAs(installed);
    } finally {
      restore(previous);
    }
  }

  private static void restore(final String previous) {
    if (previous == null) {
      System.clearProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
    } else {
      System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, previous);
    }
  }
}

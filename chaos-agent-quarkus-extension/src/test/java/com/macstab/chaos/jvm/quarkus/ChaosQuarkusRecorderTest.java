package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The runtime-init step installs the agent only when macstab.chaos.enabled is true. */
@DisplayName("ChaosQuarkusRecorder")
class ChaosQuarkusRecorderTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY);
  }

  @Test
  @DisplayName("without macstab.chaos.enabled the agent is not installed")
  void disabledByDefault() {
    assertThat(new ChaosQuarkusRecorder().installAgent().getValue()).isFalse();
  }

  @Test
  @DisplayName("macstab.chaos.enabled=true installs the agent")
  void enabled() {
    System.setProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY, "true");
    assertThat(new ChaosQuarkusRecorder().installAgent().getValue()).isTrue();
  }
}

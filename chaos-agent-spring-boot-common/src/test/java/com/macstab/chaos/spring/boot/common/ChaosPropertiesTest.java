package com.macstab.chaos.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosProperties")
class ChaosPropertiesTest {

  @Test
  @DisplayName("enabled defaults to false")
  void enabledDefaultsToFalse() {
    final ChaosProperties properties = new ChaosProperties();
    assertThat(properties.isEnabled()).isFalse();
  }

  @Test
  @DisplayName("actuator.enabled defaults to false")
  void actuatorEnabledDefaultsToFalse() {
    final ChaosProperties properties = new ChaosProperties();
    assertThat(properties.getActuator()).isNotNull();
    assertThat(properties.getActuator().isEnabled()).isFalse();
  }

  @Test
  @DisplayName("configFile defaults to null")
  void configFileDefaultsToNull() {
    final ChaosProperties properties = new ChaosProperties();
    assertThat(properties.getConfigFile()).isNull();
  }

  @Test
  @DisplayName("debugDumpOnStart defaults to false")
  void debugDumpOnStartDefaultsToFalse() {
    final ChaosProperties properties = new ChaosProperties();
    assertThat(properties.isDebugDumpOnStart()).isFalse();
  }
}

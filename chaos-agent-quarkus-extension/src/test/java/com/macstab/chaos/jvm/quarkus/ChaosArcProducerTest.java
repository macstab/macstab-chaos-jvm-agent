package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.quarkus.arc.properties.IfBuildProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The CDI producer hands out the JVM-wide control plane, not one of its own. */
@DisplayName("ChaosArcProducer")
class ChaosArcProducerTest {

  @Test
  @DisplayName("enabled at run time, it produces the control plane installed in this JVM")
  void enabledProducesTheInstalledControlPlane() {
    final String previous = System.getProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY);
    System.setProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY, "true");
    try {
      assertThat(new ChaosArcProducer().chaosControlPlane()).isSameAs(ChaosPlatform.current());
    } finally {
      restore(previous);
    }
  }

  @Test
  @DisplayName("disabled at run time, it produces an agent that is already installed")
  void disabledProducesTheAlreadyInstalledAgent() {
    final var installed = ChaosPlatform.installLocally();
    final String previous = System.getProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY);
    System.setProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY, "false");
    try {
      assertThat(new ChaosArcProducer().chaosControlPlane()).isSameAs(installed);
    } finally {
      restore(previous);
    }
  }

  @Test
  @DisplayName(
      "is registered whatever the build-time configuration: the switch is read at run time")
  void noBuildTimeGate() throws NoSuchMethodException {
    assertThat(
            ChaosArcProducer.class
                .getMethod("chaosControlPlane")
                .isAnnotationPresent(IfBuildProperty.class))
        .isFalse();
  }

  private static void restore(final String previous) {
    if (previous == null) {
      System.clearProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY);
    } else {
      System.setProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY, previous);
    }
  }
}

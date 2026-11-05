package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The CDI producer hands out the JVM-wide control plane, not one of its own. */
@DisplayName("ChaosArcProducer")
class ChaosArcProducerTest {

  @Test
  @DisplayName("produces the control plane installed in this JVM")
  void producesTheInstalledControlPlane() {
    assertThat(new ChaosArcProducer().chaosControlPlane()).isSameAs(ChaosPlatform.installLocally());
  }
}

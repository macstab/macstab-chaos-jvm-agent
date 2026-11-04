package com.macstab.chaos.jvm.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.quarkus.ChaosQuarkusRecorder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the build steps contribute: the feature name, and the agent install at runtime init. */
@DisplayName("ChaosQuarkusBuildStep")
class ChaosQuarkusBuildStepTest {

  private final ChaosQuarkusBuildStep step = new ChaosQuarkusBuildStep();

  @Test
  @DisplayName("announces the macstab-chaos-agent feature")
  void feature() {
    assertThat(step.feature().getName()).isEqualTo("macstab-chaos-agent");
  }

  @Test
  @DisplayName("records the agent install for runtime init")
  void installsAtRuntimeInit() {
    final AtomicInteger installs = new AtomicInteger();
    step.installChaosAgent(
        new ChaosQuarkusRecorder() {
          @Override
          public io.quarkus.runtime.RuntimeValue<Boolean> installAgent() {
            installs.incrementAndGet();
            return new io.quarkus.runtime.RuntimeValue<>(Boolean.FALSE);
          }
        });
    assertThat(installs).hasValue(1);
  }
}

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
  @DisplayName("makes a @QuarkusChaosTest class a test-class bean, and nothing else")
  void chaosTestClassesAreBeans() throws Exception {
    final org.jboss.jandex.Index index =
        org.jboss.jandex.Index.of(
            ChaosTestFixture.class,
            PlainFixture.class,
            com.macstab.chaos.jvm.quarkus.QuarkusChaosTest.class);
    final java.util.List<String> beans = new java.util.ArrayList<>();

    step.chaosTestClassBeans(
        new io.quarkus.deployment.builditem.CombinedIndexBuildItem(index, index),
        item -> beans.add(item.getTestClassName()));

    assertThat(beans).containsExactly(ChaosTestFixture.class.getName());
  }

  @com.macstab.chaos.jvm.quarkus.QuarkusChaosTest
  static final class ChaosTestFixture {}

  static final class PlainFixture {}

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

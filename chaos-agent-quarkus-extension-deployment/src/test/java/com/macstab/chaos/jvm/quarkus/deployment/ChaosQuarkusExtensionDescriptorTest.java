package com.macstab.chaos.jvm.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.quarkus.ChaosArcProducer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the metadata through which Quarkus discovers this extension. Without it the extension was
 * never loaded: no build step ran, the agent was never installed and {@code ChaosControlPlane}
 * could not be injected.
 */
@DisplayName("Quarkus extension descriptor")
class ChaosQuarkusExtensionDescriptorTest {

  @Test
  @DisplayName("the runtime jar names this deployment artifact with group and version")
  void runtimeDescriptorNamesDeploymentArtifact() throws IOException {
    final Properties descriptor = new Properties();
    try (InputStream in = resource("META-INF/quarkus-extension.properties")) {
      descriptor.load(in);
    }

    assertThat(descriptor.getProperty("deployment-artifact"))
        .matches("com\\.macstab\\.chaos\\.jvm:chaos-agent-quarkus-extension-deployment:[^:$]+");
  }

  @Test
  @DisplayName("the deployment jar indexes every class that declares a @BuildStep")
  void buildStepIndexListsBuildStepClasses() throws Exception {
    final List<String> indexed;
    try (InputStream in = resource("META-INF/quarkus-build-steps.list")) {
      indexed =
          Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"))
              .map(String::trim)
              .filter(line -> !line.isEmpty())
              .toList();
    }

    assertThat(indexed).contains(ChaosQuarkusBuildStep.class.getName());
    for (final String className : indexed) {
      assertThat(
              Arrays.stream(Class.forName(className).getDeclaredMethods())
                  .anyMatch(method -> method.isAnnotationPresent(BuildStep.class)))
          .as("%s declares a @BuildStep", className)
          .isTrue();
    }
  }

  @Test
  @DisplayName("the producer of ChaosControlPlane is registered as an unremovable bean")
  void producerIsRegisteredAsBean() {
    final AdditionalBeanBuildItem beans = new ChaosQuarkusBuildStep().producerBean();

    assertThat(beans.getBeanClasses()).containsExactly(ChaosArcProducer.class.getName());
    assertThat(beans.isRemovable()).isFalse();
  }

  private static InputStream resource(final String name) {
    final InputStream in =
        ChaosQuarkusExtensionDescriptorTest.class.getClassLoader().getResourceAsStream(name);
    assertThat(in).as("classpath resource %s", name).isNotNull();
    return in;
  }
}

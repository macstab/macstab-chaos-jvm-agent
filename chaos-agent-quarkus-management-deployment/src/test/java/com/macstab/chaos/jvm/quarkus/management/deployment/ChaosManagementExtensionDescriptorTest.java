package com.macstab.chaos.jvm.quarkus.management.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks the metadata through which Quarkus discovers the management extension. */
@DisplayName("Quarkus management extension descriptor")
class ChaosManagementExtensionDescriptorTest {

  @Test
  @DisplayName(
      "the runtime jar loads the api and the agent parent-first, in tests and in the packaged"
          + " application")
  void runtimeDescriptorLoadsApiAndAgentParentFirst() throws IOException {
    final Properties descriptor = managementDescriptor();

    final String agentArtifacts =
        "com.macstab.chaos.jvm:chaos-agent-api,com.macstab.chaos.jvm:chaos-agent-bootstrap";
    assertThat(descriptor.getProperty("parent-first-artifacts")).isEqualTo(agentArtifacts);
    assertThat(descriptor.getProperty("runner-parent-first-artifacts")).isEqualTo(agentArtifacts);
  }

  /** The descriptor that names this module's deployment artifact. */
  private static Properties managementDescriptor() throws IOException {
    final List<Properties> found = new ArrayList<>();
    for (final URL url :
        Collections.list(
            ChaosManagementExtensionDescriptorTest.class
                .getClassLoader()
                .getResources("META-INF/quarkus-extension.properties"))) {
      final Properties descriptor = new Properties();
      try (InputStream in = url.openStream()) {
        descriptor.load(in);
      }
      if (descriptor
          .getProperty("deployment-artifact", "")
          .contains(":chaos-agent-quarkus-management-deployment:")) {
        found.add(descriptor);
      }
    }
    assertThat(found).as("the management extension descriptor").hasSize(1);
    return found.get(0);
  }
}

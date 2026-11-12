package com.macstab.chaos.jvm.bootstrap;

import java.nio.file.Path;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hooks in classes a child-first class loader defines (a web application with the agent jar in
 * {@code WEB-INF/lib}): the loader must resolve the bootstrap bridge to the one copy that carries
 * the agent's delegate, not define a copy of its own from the agent jar, which would leave every
 * hook in its classes inert.
 */
@DisplayName("Child-first class loaders")
class ChildFirstLoaderIntegrationTest {

  @TempDir Path workDir;

  @Test
  @DisplayName(
      "method and library hooks in a child-first loader reach the agent; a loader with its own"
          + " bridge copy is reported, not woven")
  void hooksInChildFirstLoaderReachTheAgent() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(workDir)
            .runShadedOnly(
                DefectProbeMain.class,
                "child-first-loader",
                "-Dchaos.bootstrap.agentJar=" + System.getProperty("chaos.bootstrap.agentJar"),
                "-Dchaos.test.isolatedClasspath="
                    + System.getProperty("chaos.test.clientClasspath"));

    SoftAssertions.assertSoftly(
        softly -> {
          softly.assertThat(result.value("target.childFirst")).isEqualTo("true");
          softly
              .assertThat(result.value("target.call"))
              .as("the method reject throws in the child-first loader's class")
              .isEqualTo("java.lang.IllegalStateException");
          softly.assertThat(result.longValue("method.applied")).isEqualTo(1L);
          softly
              .assertThat(result.longValue("kafka.matched"))
              .as("Kafka in WEB-INF/lib is evaluated")
              .isEqualTo(1L);
          softly.assertThat(result.longValue("bridge.count")).isPositive();
          softly
              .assertThat(result.value("bridge.notBoot"))
              .as("bridge classes the child-first loader does not resolve to the boot copy")
              .isEqualTo("[]");
          softly
              .assertThat(result.value("bridge.unlisted"))
              .as("classes the agent jar keeps out of view that BRIDGE_CLASSES does not list")
              .isEqualTo("[]");
          softly
              .assertThat(result.value("module.call"))
              .as("a loader with its own bridge copy is not woven: no reject, no LinkageError")
              .isEqualTo("called");
          softly
              .assertThat(result.value("hookFailures"))
              .as("the loader with its own bridge copy is named")
              .contains(result.value("module.loader"));
        });
  }
}

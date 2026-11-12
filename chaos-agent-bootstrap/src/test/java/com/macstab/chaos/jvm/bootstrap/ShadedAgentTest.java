package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shaded agent jar behaves as the unshaded agent: relocating ByteBuddy must not relocate the
 * names the agent compares with application classes, nor the system property users set for
 * ByteBuddy.
 */
@DisplayName("Shaded agent jar")
class ShadedAgentTest {

  @TempDir Path workDir;

  @Test
  @DisplayName("the switch is copied only when set, and never over a value set under the target")
  void copiesOnlyAnUnsetTarget() {
    final String from = "chaos.test.copy.from";
    final String to = "chaos.test.copy.to";
    try {
      ChaosAgentBootstrap.copyProperty(from, to);
      assertThat(System.getProperty(to)).isNull();

      System.setProperty(from, "true");
      ChaosAgentBootstrap.copyProperty(from, to);
      assertThat(System.getProperty(to)).isEqualTo("true");

      System.setProperty(from, "false");
      ChaosAgentBootstrap.copyProperty(from, to);
      assertThat(System.getProperty(to)).as("set already").isEqualTo("true");
    } finally {
      System.clearProperty(from);
      System.clearProperty(to);
    }
  }

  @Test
  @DisplayName(
      "leaves application classes in net.bytebuddy packages alone and honours"
          + " -Dnet.bytebuddy.experimental")
  void shadingKeepsByteBuddyNames() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(workDir)
            .runShadedOnly(
                ShadedAgentProbeMain.class, "shaded", "-Dnet.bytebuddy.experimental=true");

    SoftAssertions.assertSoftly(
        softly -> {
          softly
              .assertThat(result.longValue("matched"))
              .as("net.bytebuddy.probe.Target is not woven")
              .isZero();
          softly
              .assertThat(result.value("relocatedProperty"))
              .as("the property as the relocated ByteBuddy reads it")
              .isEqualTo("true");
          softly
              .assertThat(result.value("experimental"))
              .as("copied before the relocated ByteBuddy read it in its static initialiser")
              .isEqualTo("true");
        });
  }

  @Test
  @DisplayName(
      "in a fat jar (the agent in a class loader of its own), a stopped direct buffer pressure frees"
          + " its memory at once")
  void fatJarReleasesDirectBuffersOnStop() throws Exception {
    // Epsilon never collects: only the stressor's eager release can lower the pool.
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(workDir)
            .runInFatJarShape(
                FatJarProbeMain.class,
                "direct-buffer-release",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseEpsilonGC",
                "-Xmx1g");

    SoftAssertions.assertSoftly(
        softly -> {
          softly
              .assertThat(result.value("agent.ownLoader"))
              .as("the agent classes come from a loader of their own")
              .isEqualTo("true");
          softly.assertThat(result.longValue("directDuringMiB")).isGreaterThanOrEqualTo(32L);
          softly
              .assertThat(result.longValue("directDuringMiB") - result.longValue("directAfterMiB"))
              .as("freed through the cleaner on stop: the opens reach the agent's own module")
              .isGreaterThanOrEqualTo(32L);
          softly
              .assertThat(result.output())
              .as("no warning that the cleaner is out of reach")
              .doesNotContain("DirectBufferPressure");
        });
  }
}

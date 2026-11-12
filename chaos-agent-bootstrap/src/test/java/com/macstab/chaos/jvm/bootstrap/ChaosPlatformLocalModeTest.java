package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ChaosPlatform#installLocally()} in a child JVM: the install attaches the agent and weaves
 * hooks for the rest of the JVM's life, which in the test JVM would reach every later test.
 */
@DisplayName("ChaosPlatform local mode")
class ChaosPlatformLocalModeTest {

  private static final long DELAY_MS = 60L;

  @TempDir Path tempDir;

  @Test
  @DisplayName("session-scoped executor delay is applied when installed locally")
  void localInstallAppliesSessionScopedExecutorDelay() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutAgent(DefectProbeMain.class, "local-mode-session-delay");

    assertThat(result.longValue("applied.local-delay")).isEqualTo(1L);
    assertThat(result.longValue("elapsedMillis"))
        .as("execute() must be delayed by session-scoped chaos")
        .isGreaterThanOrEqualTo(DELAY_MS);
  }

  @Test
  @DisplayName("closing the JVM-wide control plane is refused, and chaos keeps working")
  void closingTheSharedPlaneIsRefused() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir).runWithoutAgent(SharedPlaneCloseProbeMain.class, "close");

    assertThat(result.value("close"))
        .startsWith("refused:")
        .contains("JVM-wide")
        .contains("TrackingChaosControlPlane");
    assertThat(result.value("activate")).isEqualTo("ACTIVE");
    assertThat(result.value("session")).isEqualTo("open");
    assertThat(result.value("done")).isEqualTo("true");
  }
}

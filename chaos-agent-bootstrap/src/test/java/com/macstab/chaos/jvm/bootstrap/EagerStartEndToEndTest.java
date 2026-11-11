package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An agent started with every hook group woven, in a child JVM. */
@DisplayName("An eager start")
class EagerStartEndToEndTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("without clockCallSites warns about no clock scenario: none is active")
  void noClockWarningWithoutAScenario() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlan(
                EagerStartProbeMain.class, "start", "-Dmacstab.chaos.instrumentation=eager");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("done")).isEqualTo("true");
          softly.assertThat(result.output()).doesNotContain("clockCallSites is not set");
        });
  }

  @Test
  @DisplayName("with clockCallSites weaves the call sites at start, without a warning")
  void callSitesWovenAtStart() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlan(
                EagerStartProbeMain.class,
                "start",
                "-Dmacstab.chaos.instrumentation=eager",
                "-Dmacstab.chaos.clockCallSites=com.example.chaostarget.");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("done")).isEqualTo("true");
          softly.assertThat(result.output()).doesNotContain("clockCallSites is not set");
        });
  }
}

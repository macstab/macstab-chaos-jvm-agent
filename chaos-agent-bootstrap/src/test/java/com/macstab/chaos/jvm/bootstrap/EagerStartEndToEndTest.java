package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An agent started with every hook group woven, in a child JVM. */
@DisplayName("An eager start")
class EagerStartEndToEndTest {

  private static final String WARNING = "clockCallSites is not set";

  private static final String EAGER = "-Dmacstab.chaos.instrumentation=eager";

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "without clockCallSites and without a clock scenario leaves the call-site group unwoven and"
          + " does not warn")
  void noClockScenarioNoCallSiteGroup() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir).runWithoutPlan(EagerStartProbeMain.class, "idle", EAGER);

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("done")).isEqualTo("true");
          softly.assertThat(result.value("wovenAtStart")).doesNotContain("CLOCK_CALL_SITES");
          softly.assertThat(result.output()).doesNotContain(WARNING);
        });
  }

  @Test
  @DisplayName(
      "without clockCallSites weaves the call-site group when a clock scenario is activated, which"
          + " warns once and leaves the application's clock reads real")
  void clockScenarioWeavesAndWarns() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir).runWithoutPlan(EagerStartProbeMain.class, "clock", EAGER);

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("done")).isEqualTo("true");
          softly.assertThat(result.value("wovenAtStart")).doesNotContain("CLOCK_CALL_SITES");
          softly.assertThat(result.value("wovenAfterActivation")).contains("CLOCK_CALL_SITES");
          softly.assertThat(result.output().split(WARNING, -1)).as("one warning").hasSize(2);
          softly.assertThat(result.value("skewAfterSeconds")).isEqualTo("0");
        });
  }

  @Test
  @DisplayName("with clockCallSites weaves the call sites at start, which a clock scenario skews")
  void callSitesWovenAtStart() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlan(
                EagerStartProbeMain.class,
                "clock",
                EAGER,
                "-Dmacstab.chaos.clockCallSites=com.example.chaostarget.");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("done")).isEqualTo("true");
          softly.assertThat(result.value("wovenAtStart")).contains("CLOCK_CALL_SITES");
          softly.assertThat(result.value("skewBeforeSeconds")).isEqualTo("0");
          softly.assertThat(result.value("skewAfterSeconds")).isEqualTo("3600");
          softly.assertThat(result.output()).doesNotContain(WARNING);
        });
  }
}

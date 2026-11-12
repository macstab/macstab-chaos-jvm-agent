package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code Thread.sleep} on a virtual thread under a scenario, in a child JVM. */
@DisplayName("Thread.sleep on a virtual thread")
class VirtualSleepEndToEndTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("is lengthened by a delay and skipped by a suppress; a platform sleep is not")
  void virtualThreadSleep() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir).runWithoutPlan(VirtualSleepProbeMain.class, "sleep");

    assertSoftly(
        softly -> {
          softly.assertThat(result.longValue("delay.millis")).isBetween(340L, 5_000L);
          softly.assertThat(result.longValue("delay.applied")).isEqualTo(1L);
          softly.assertThat(result.longValue("suppress.millis")).isLessThan(1_000L);
          softly.assertThat(result.longValue("suppress.applied")).isEqualTo(1L);
          softly.assertThat(result.longValue("platform.millis")).isGreaterThanOrEqualTo(95L);
        });
  }
}

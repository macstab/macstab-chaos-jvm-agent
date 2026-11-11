package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The JDK's close inside {@code Thread.interrupt()} under close scenarios, in a child JVM. */
@DisplayName("A channel closed by an interrupt")
class InterruptCloseEndToEndTest {

  @TempDir Path tempDir;

  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "needs mkfifo")
  @DisplayName("is not a close target: interrupt() returns at once and the reader ends")
  void interruptCloseIsNotATarget() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir).runWithoutPlan(InterruptCloseProbeMain.class, "interrupt-close");

    assertSoftly(
        softly -> {
          softly.assertThat(result.longValue("file.interruptMillis")).isLessThan(1_000L);
          softly
              .assertThat(result.value("file.reader"))
              .isEqualTo("java.nio.channels.ClosedByInterruptException");
          softly.assertThat(result.longValue("file.applied")).isZero();
          softly.assertThat(result.longValue("socket.interruptMillis")).isLessThan(1_000L);
          softly
              .assertThat(result.value("socket.reader"))
              .isEqualTo("java.nio.channels.ClosedByInterruptException");
          softly.assertThat(result.longValue("socket.applied")).isZero();
        });
  }
}

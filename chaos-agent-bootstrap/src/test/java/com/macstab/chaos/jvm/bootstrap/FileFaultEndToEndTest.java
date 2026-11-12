package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Injected file I/O failures on real files in a child JVM (ADR 0005). */
@DisplayName("File I/O failures on real files")
class FileFaultEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe.ProbeResult run(final String mode) throws Exception {
    return new ChildJvmProbe(tempDir).runWithoutPlan(FileFaultProbeMain.class, mode);
  }

  @Nested
  @DisplayName("a failing open")
  class FailingOpen {

    @Test
    @DisplayName("of a java.io stream subclass throws what the constructor declares")
    void subclassGetsTheDeclaredType() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("open-subclass");

      assertSoftly(
          softly -> {
            for (final String effect : new String[] {"errno", "reject"}) {
              for (final String stream : new String[] {"input", "output", "randomAccess"}) {
                softly
                    .assertThat(result.value(effect + "." + stream))
                    .as("%s on a %s subclass", effect, stream)
                    .isEqualTo("java.io.FileNotFoundException");
              }
            }
          });
    }
  }

  @Nested
  @DisplayName("a torn transferFrom")
  class TornTransfer {

    @Test
    @DisplayName("with an unbounded count tears the bytes the source holds")
    void unboundedCountTearsTheTransfer() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("torn-transfer");

      assertSoftly(
          softly -> {
            softly.assertThat(result.longValue("transferred")).isBetween(1L, 99L);
            softly.assertThat(result.longValue("written")).isEqualTo(100L);
            softly.assertThat(result.longValue("applied")).isEqualTo(1L);
          });
    }
  }
}

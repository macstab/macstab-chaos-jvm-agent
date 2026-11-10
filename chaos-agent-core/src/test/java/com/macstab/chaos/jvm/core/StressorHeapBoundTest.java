package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A thread-local leak and a reference-queue flood are refused at activation when they cannot fit
 * the heap, as heap pressure is (L17): before anything is allocated, so no application thread runs
 * out of memory meanwhile.
 */
@DisplayName("Stressors bounded by the heap")
class StressorHeapBoundTest {

  @Test
  @DisplayName("larger than the free heap, both are refused before they allocate")
  void refusedBeforeAllocating() throws Exception {
    final String output =
        BoundedChildJvm.run(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m",
                "-cp",
                System.getProperty("java.class.path"),
                HeapBoundProbeMain.class.getName()),
            Duration.ofSeconds(60));

    assertThat(output)
        .contains("thread-local-leak=refused")
        .contains("reference-queue-flood=refused");
    assertThat(ChildOutput.value(output, "thread-local-leak.message")).contains("free heap");
    assertThat(ChildOutput.value(output, "reference-queue-flood.message")).contains("free heap");
    assertThat(Long.parseLong(ChildOutput.value(output, "thread-local-leak.peakPercent")))
        .as("the refused activation filled no heap")
        .isLessThan(50L);
    assertThat(Long.parseLong(ChildOutput.value(output, "reference-queue-flood.peakPercent")))
        .isLessThan(50L);
  }

  @Test
  @DisplayName("a thread-local leak whose entry count overflows an int is an invalid scenario")
  void entryCountOverflowIsInvalid() {
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      assertThatThrownBy(
              () ->
                  runtime.validate(
                      ChaosScenario.builder("overflowing")
                          .selector(
                              ChaosSelector.stress(ChaosSelector.StressTarget.THREAD_LOCAL_LEAK))
                          .effect(new ChaosEffect.ThreadLocalLeakEffect(Integer.MAX_VALUE, 1))
                          .build()))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("entriesPerThread");
    }
  }

  /** The {@code key=value} lines a child printed. */
  private static final class ChildOutput {
    private static String value(final String output, final String key) {
      return output
          .lines()
          .filter(line -> line.startsWith(key + "="))
          .map(line -> line.substring(key.length() + 1))
          .findFirst()
          .orElseThrow(() -> new AssertionError("no " + key + "= in:\n" + output));
    }
  }
}

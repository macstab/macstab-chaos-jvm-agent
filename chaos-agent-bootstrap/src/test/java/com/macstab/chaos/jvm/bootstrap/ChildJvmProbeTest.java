package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bounded child-JVM run every child-process test uses. */
@DisplayName("ChildJvmProbe")
class ChildJvmProbeTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a child that never ends is stopped at the timeout and does not outlive the run")
  void neverEndingChildIsStopped() throws Exception {
    final long start = System.nanoTime();

    final ChildJvmProbe.BoundedRun run =
        ChildJvmProbe.runBounded(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                AgentProcessProbeMain.class.getName(),
                "sleep-forever"),
            tempDir.resolve("forever.out"),
            Duration.ofSeconds(2));

    assertThat(run.finished()).isFalse();
    assertThat(run.exitValue()).isEqualTo(-1);
    assertThat(run.threads()).as("the hang is reported with its threads").contains("main");
    assertThat(ProcessHandle.of(run.pid()).filter(ProcessHandle::isAlive))
        .as("no orphan child")
        .isEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("a child that ends reports its exit value and its key=value output")
  void finishedChildIsRead() throws Exception {
    final ChildJvmProbe.BoundedRun run =
        ChildJvmProbe.runBounded(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                AgentProcessProbeMain.class.getName(),
                "sleep-delay"),
            tempDir.resolve("ends.out"),
            Duration.ofSeconds(30));

    assertThat(run.finished()).isTrue();
    assertThat(run.exitValue()).isZero();
    assertThat(run.result().longValue("elapsedMillis")).isNotNegative();
  }
}

package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executor hooks on real pools in a child JVM. */
@DisplayName("Executor hooks on real pools")
class ExecutorEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe.ProbeResult run(final String mode) throws Exception {
    return new ChildJvmProbe(tempDir).runWithoutPlan(ExecutorProbeMain.class, mode);
  }

  @Nested
  @DisplayName("EXECUTOR_WORKER_RUN")
  class WorkerRun {

    @Test
    @DisplayName("applies once per task, also on a pool whose beforeExecute skips or calls super")
    void appliesToOverridingPools() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("worker-run-overrides");

      assertSoftly(
          softly -> {
            for (final String pool : new String[] {"plain", "overriding", "delegating"}) {
              softly.assertThat(result.longValue(pool + ".millis")).as(pool).isGreaterThan(250L);
              softly.assertThat(result.longValue(pool + ".applied")).as(pool).isEqualTo(1L);
            }
          });
    }
  }
}

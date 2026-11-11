package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A gRPC reject under the channel's retry policy, with real gRPC in a child JVM. */
@DisplayName("gRPC reject and the retry policy")
class GrpcRetryEndToEndTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a rejected attempt is retried by the channel's policy for UNAVAILABLE")
  void rejectedAttemptIsRetried() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlanOnClasspath(
                GrpcRetryProbeMain.class,
                "retry-policy",
                System.getProperty("chaos.test.clientClasspath"));

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("reply")).isEqualTo("echo hi");
          softly.assertThat(result.longValue("applied")).isEqualTo(1L);
          softly.assertThat(result.longValue("received")).isEqualTo(1L);
        });
  }
}

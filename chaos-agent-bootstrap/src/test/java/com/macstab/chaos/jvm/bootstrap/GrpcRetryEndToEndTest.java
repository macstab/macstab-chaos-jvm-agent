package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A gRPC call under the channel's retry policy, with real gRPC in a child JVM. */
@DisplayName("gRPC calls and the retry policy")
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

  @Test
  @DisplayName(
      "a gRPC whose RetryStream.newSubstream takes 2 arguments (1.39) rejects attempts too")
  void rejectedAttemptOnOlderGrpc() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlanOnClasspath(
                GrpcRetryProbeMain.class,
                "retry-policy",
                System.getProperty("chaos.test.legacyGrpcClasspath"));

    assertSoftly(
        softly -> {
          // The shape this row is about; a newer gRPC here would test nothing new.
          softly.assertThat(result.longValue("newSubstreamArguments")).isEqualTo(2L);
          softly.assertThat(result.value("reply")).isEqualTo("echo hi");
          softly.assertThat(result.longValue("applied")).isEqualTo(1L);
          softly.assertThat(result.longValue("received")).isEqualTo(1L);
        });
  }

  @Test
  @DisplayName("a delay is the call's: once, not again for each retried attempt")
  void delayIsAppliedOncePerCall() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlanOnClasspath(
                GrpcRetryProbeMain.class,
                "retry-delay",
                System.getProperty("chaos.test.clientClasspath"));

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("reply")).isEqualTo("echo hi");
          softly.assertThat(result.longValue("received")).isEqualTo(2L);
          softly.assertThat(result.longValue("applied")).isEqualTo(1L);
        });
  }
}

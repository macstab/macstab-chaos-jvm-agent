package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Client-library hooks through real calls of the library, in a child JVM. */
@DisplayName("Client libraries on real calls")
class ClientRealCallEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe.ProbeResult run(final String mode) throws Exception {
    final String classpath =
        String.join(
            File.pathSeparator,
            System.getProperty("chaos.test.clientClasspath"),
            System.getProperty("chaos.test.httpClasspath"),
            System.getProperty("chaos.test.coverageClasspath"));
    return new ChildJvmProbe(tempDir)
        .runWithoutPlanOnClasspath(ClientRealCallProbeMain.class, mode, classpath);
  }

  @Test
  @DisplayName("an Apache HttpClient 5 or 4 reject fails the request with IOException")
  void apacheHttpClientReject() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("apache-reject");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("hc5.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("hc5.message")).isEqualTo("chaos");
          softly.assertThat(result.longValue("hc5.after")).isEqualTo(200L);
          softly.assertThat(result.value("hc4.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("hc4.message")).isEqualTo("chaos");
          softly.assertThat(result.longValue("hc4.after")).isEqualTo(200L);
        });
  }

  @Test
  @DisplayName("an AWS SDK async client's rejected call fails its future before any request")
  void awsAsyncClientReject() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("aws-async");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("rejected.returnedFuture")).isEqualTo("true");
          softly
              .assertThat(result.value("rejected.type"))
              .isEqualTo("software.amazon.awssdk.core.exception.SdkClientException");
          softly.assertThat(result.value("rejected.message")).isEqualTo("chaos");
          softly.assertThat(result.longValue("rejected.requests")).isZero();
          softly.assertThat(result.longValue("rejected.applied")).isEqualTo(1L);
          softly.assertThat(result.longValue("plain.requests")).isEqualTo(1L);
        });
  }

  @Test
  @DisplayName("a NETTY_CHANNEL_READ delay holds the bytes a real Netty client reads")
  void nettyReadDelay() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("netty-read");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("read.done")).isEqualTo("true");
          softly.assertThat(result.longValue("read.millis")).isBetween(290L, 5_000L);
          softly.assertThat(result.longValue("read.applied")).isEqualTo(1L);
        });
  }
}

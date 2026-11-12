package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Network effects on real loopback sockets and channels, in a child JVM. */
@DisplayName("Network effects on real sockets and channels")
class NetworkRealCallEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe.ProbeResult run(final String mode) throws Exception {
    return new ChildJvmProbe(tempDir).runWithoutPlan(NetworkRealCallProbeMain.class, mode);
  }

  @Test
  @DisplayName("an errno fails a socket or channel call with the JDK's exception and text")
  void errnosOnRealCalls() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("errnos");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("readReset.type")).isEqualTo("java.net.SocketException");
          softly.assertThat(result.value("readReset.message")).isEqualTo("Connection reset");
          softly
              .assertThat(result.value("readTimedOut.type"))
              .isEqualTo("java.net.SocketException");
          softly.assertThat(result.value("readTimedOut.message")).isEqualTo("Connection timed out");
          softly
              .assertThat(result.value("readHostUnreachable.type"))
              .isEqualTo("java.net.SocketException");
          softly
              .assertThat(result.value("readHostUnreachable.message"))
              .isEqualTo("No route to host");
          softly
              .assertThat(result.value("writeBrokenPipe.type"))
              .isEqualTo("java.net.SocketException");
          softly.assertThat(result.value("writeBrokenPipe.message")).isEqualTo("Broken pipe");
          softly
              .assertThat(result.value("connectHostUnreachable.type"))
              .isEqualTo("java.net.NoRouteToHostException");
          softly
              .assertThat(result.value("connectHostUnreachable.message"))
              .isEqualTo("No route to host");
          softly
              .assertThat(result.value("connectTimedOut.type"))
              .isEqualTo("java.net.ConnectException");
          softly
              .assertThat(result.value("connectTimedOut.message"))
              .isEqualTo("Connection timed out");
          softly
              .assertThat(result.value("channelReadReset.type"))
              .isEqualTo("java.net.SocketException");
          softly.assertThat(result.value("channelReadReset.message")).isEqualTo("Connection reset");
          softly
              .assertThat(result.value("channelWriteBrokenPipe.type"))
              .isEqualTo("java.net.SocketException");
          softly
              .assertThat(result.value("channelWriteBrokenPipe.message"))
              .isEqualTo("Broken pipe");
          softly
              .assertThat(result.value("channelConnectRefused.type"))
              .isEqualTo("java.net.ConnectException");
          softly
              .assertThat(result.value("channelConnectRefused.message"))
              .isEqualTo("Connection refused");
          softly
              .assertThat(result.value("channelAcceptTooManyFiles.type"))
              .isEqualTo("java.io.IOException");
          softly
              .assertThat(result.value("channelAcceptTooManyFiles.message"))
              .isEqualTo("Too many open files");
        });
  }

  @Test
  @DisplayName("a reject of accept, bind, shutdown and the channel calls raises the real type")
  void rejectsOnRealCalls() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("rejects");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("accept.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("shutdown.type")).isEqualTo("java.net.SocketException");
          softly.assertThat(result.value("bind.type")).isEqualTo("java.net.BindException");
          softly.assertThat(result.value("channelRead.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("channelWrite.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("channelConnect.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("channelAccept.type")).isEqualTo("java.io.IOException");
        });
  }

  @Test
  @DisplayName("a spurious wakeup returns 0 at once from a select with a readable channel")
  void spuriousWakeup() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("spurious-wakeup");

    assertSoftly(
        softly -> {
          softly.assertThat(result.longValue("spurious.selected")).isZero();
          softly.assertThat(result.longValue("spurious.keys")).isZero();
          softly.assertThat(result.longValue("spurious.millis")).isLessThan(2_000L);
          softly.assertThat(result.longValue("spurious.applied")).isEqualTo(1L);
          softly.assertThat(result.longValue("after.selected")).isEqualTo(1L);
        });
  }

  @Test
  @DisplayName("an SSLSocket handshake is delayed explicitly and implicitly, and a reject fails it")
  void sslSocketHandshake() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("tls");

    assertSoftly(
        softly -> {
          // The server closes at once: each handshake still fails after the delay.
          softly.assertThat(result.value("explicitDelay.type")).isNotEqualTo("none");
          softly.assertThat(result.longValue("explicitDelay.millis")).isGreaterThanOrEqualTo(290L);
          softly.assertThat(result.value("implicitDelay.type")).isNotEqualTo("none");
          softly.assertThat(result.longValue("implicitDelay.millis")).isGreaterThanOrEqualTo(290L);
          softly.assertThat(result.longValue("delay.applied")).isEqualTo(2L);
          softly
              .assertThat(result.value("reject.type"))
              .isEqualTo("javax.net.ssl.SSLHandshakeException");
          softly.assertThat(result.value("reject.message")).isEqualTo("chaos");
          softly.assertThat(result.value("reject.closed")).isEqualTo("true");
        });
  }

  @Test
  @DisplayName("a channel connect evaluates SOCKET_CONNECT and NIO_CHANNEL_CONNECT once each")
  void channelConnectIsEvaluatedOncePerOperation() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("connect-both");

    assertSoftly(
        softly -> {
          softly.assertThat(result.longValue("channel.socketApplied")).isEqualTo(1L);
          softly.assertThat(result.longValue("channel.channelApplied")).isEqualTo(1L);
          // The adaptor's connect is a SOCKET_CONNECT only: it does not call
          // connect(SocketAddress).
          softly.assertThat(result.longValue("adaptor.socketApplied")).isEqualTo(2L);
          softly.assertThat(result.longValue("adaptor.channelApplied")).isEqualTo(1L);
        });
  }
}

package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Injected network failures on real sockets and channels in a child JVM: the endpoint and the
 * threads using it must end up as the real failure leaves them (ADR 0005).
 */
@DisplayName("Network failures on real endpoints")
class NetworkFaultEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe.ProbeResult run(final String mode) throws Exception {
    return new ChildJvmProbe(tempDir).runWithoutPlan(NetworkFaultProbeMain.class, mode);
  }

  @Nested
  @DisplayName("a failing close")
  class FailingClose {

    @Test
    @DisplayName("closes the socket or channel first: a blocked reader ends and the peer reads EOF")
    void closesBeforeItFails() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("close-reject");

      assertSoftly(
          softly -> {
            softly.assertThat(result.value("socket.close")).isEqualTo("java.io.IOException");
            softly.assertThat(result.value("socket.closed")).isEqualTo("true");
            softly.assertThat(result.value("socket.readerEnded")).isEqualTo("true");
            softly.assertThat(result.value("socket.reader")).isEqualTo("java.net.SocketException");
            softly.assertThat(result.value("socket.peerRead")).isEqualTo("-1");
            softly.assertThat(result.value("channel.close")).isEqualTo("java.io.IOException");
            softly.assertThat(result.value("channel.closed")).isEqualTo("true");
            softly.assertThat(result.value("channel.readerEnded")).isEqualTo("true");
            softly
                .assertThat(result.value("channel.reader"))
                .isEqualTo("java.nio.channels.AsynchronousCloseException");
            softly.assertThat(result.value("channel.peerRead")).isEqualTo("-1");
            // JDK 25 closes an interrupted reader's channel through close(), the hooked method.
            softly.assertThat(result.value("interrupt.readerEnded")).isEqualTo("true");
            softly
                .assertThat(result.value("interrupt.reader"))
                .isEqualTo("java.nio.channels.ClosedByInterruptException");
            softly.assertThat(result.value("interrupt.closed")).isEqualTo("true");
          });
    }
  }

  @Nested
  @DisplayName("a failing connect or handshake")
  class FailingConnect {

    @Test
    @DisplayName("closes the socket or channel, so a second connect fails as on a real failure")
    void connectFailureCloses() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("connect-reject");

      assertSoftly(
          softly -> {
            softly.assertThat(result.value("socket.type")).isEqualTo("java.net.ConnectException");
            softly.assertThat(result.value("socket.open")).isEqualTo("false");
            softly.assertThat(result.value("socket.retry")).isEqualTo("java.net.SocketException");
            softly.assertThat(result.value("channel.type")).isEqualTo("java.net.ConnectException");
            softly.assertThat(result.value("channel.open")).isEqualTo("false");
            // What the JDK throws for a connect of a closed channel: JDK 21 reports it as an
            // asynchronous close, a ClosedChannelException too.
            softly
                .assertThat(result.value("channel.retry"))
                .isIn(
                    "java.nio.channels.ClosedChannelException",
                    "java.nio.channels.AsynchronousCloseException");
            softly.assertThat(result.value("adaptor.type")).isEqualTo("java.net.ConnectException");
            softly.assertThat(result.value("adaptor.open")).isEqualTo("false");
            softly.assertThat(result.value("nio.type")).isNotEqualTo("none");
            softly.assertThat(result.value("nio.open")).isEqualTo("false");
            softly
                .assertThat(result.value("timeout.type"))
                .isEqualTo("java.net.SocketTimeoutException");
            softly.assertThat(result.value("timeout.open")).isEqualTo("false");
          });
    }

    @Test
    @DisplayName("a failing TLS handshake closes the socket and the engine")
    void handshakeFailureCloses() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("handshake-reject");

      assertSoftly(
          softly -> {
            softly
                .assertThat(result.value("socket.type"))
                .isEqualTo("javax.net.ssl.SSLHandshakeException");
            softly.assertThat(result.value("socket.closed")).isEqualTo("true");
            softly.assertThat(result.value("socket.retry")).isEqualTo("java.net.SocketException");
            softly
                .assertThat(result.value("engine.type"))
                .isEqualTo("javax.net.ssl.SSLHandshakeException");
            softly.assertThat(result.value("engine.outboundDone")).isEqualTo("true");
          });
    }
  }

  @Nested
  @DisplayName("a timeout() stall")
  class TimeoutStall {

    @Test
    @DisplayName("ends on a selector wakeup or close, and on a close of the socket or channel")
    void endsAsTheRealWaitDoes() throws Exception {
      final ChildJvmProbe.ProbeResult result = run("stall-ends");

      assertSoftly(
          softly -> {
            for (final String row :
                new String[] {"selectWakeup", "selectClose", "read", "adaptorRead", "connect"}) {
              softly.assertThat(result.longValue(row + ".millis")).as(row).isLessThan(1_500L);
            }
            softly.assertThat(result.value("selectWakeup.outcome")).isEqualTo("ok");
            softly.assertThat(result.value("read.outcome")).isEqualTo("java.net.SocketException");
            // The adaptor's stream reports the channel's asynchronous close as it is.
            softly
                .assertThat(result.value("adaptorRead.outcome"))
                .isEqualTo("java.nio.channels.AsynchronousCloseException");
            softly
                .assertThat(result.value("connect.outcome"))
                .isEqualTo("java.net.SocketException");
          });
    }
  }
}

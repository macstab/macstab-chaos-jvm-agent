package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** UDP under a scenario, on real datagram sockets and channels in a child JVM. */
@DisplayName("Datagrams under a scenario")
class DatagramEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe.ProbeResult run(final String mode) throws Exception {
    return new ChildJvmProbe(tempDir).runWithoutPlan(DatagramProbeMain.class, mode);
  }

  @Test
  @DisplayName("a packet receive counts the packet's window, not the whole array")
  void receiveCountsThePacketWindow() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("receive-window");

    assertSoftly(
        softly -> {
          softly.assertThat(result.longValue("received")).isEqualTo(200L);
          softly.assertThat(result.longValue("applied")).isEqualTo(1L);
          softly.assertThat(result.value("corrupted")).isEqualTo("true");
        });
  }

  @Test
  @DisplayName("a dropped send fails as the real one does where the JDK refuses it")
  void droppedSendKeepsTheJdkChecks() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("dropped-send");

    assertSoftly(
        softly -> {
          // JDK 21 reports the closed channel as an AsynchronousCloseException, 25 as its parent.
          softly
              .assertThat(result.value("closed.send"))
              .isIn(
                  "java.nio.channels.ClosedChannelException",
                  "java.nio.channels.AsynchronousCloseException");
          softly
              .assertThat(result.value("unconnected.write"))
              .isEqualTo("java.nio.channels.NotYetConnectedException");
          softly
              .assertThat(result.value("unresolved.send"))
              .isEqualTo("java.nio.channels.UnresolvedAddressException");
          softly.assertThat(result.value("port0.send")).isEqualTo("java.net.SocketException");
          softly
              .assertThat(result.value("otherPeer.send"))
              .isEqualTo("java.nio.channels.AlreadyConnectedException");
          softly
              .assertThat(result.value("noAddress.send"))
              .isEqualTo("java.lang.IllegalArgumentException");
          softly.assertThat(result.longValue("drop.sent")).isEqualTo(4L);
          softly.assertThat(result.value("connected.send")).isEqualTo("ok");
          softly.assertThat(result.value("connected.packetAddress")).isEqualTo("true");
          softly
              .assertThat(result.value("connected.received"))
              .isEqualTo("java.net.SocketTimeoutException");
          softly.assertThat(result.longValue("applied")).isEqualTo(2L);
        });
  }
}

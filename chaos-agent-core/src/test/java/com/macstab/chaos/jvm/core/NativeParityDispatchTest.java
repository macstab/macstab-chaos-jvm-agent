package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The dispatch of the operations that mirror the native libraries: socket channels, binds,
 * datagrams, reverse lookups, system-clock reads, opens, maps and transfers.
 */
@DisplayName("Native-parity dispatch")
class NativeParityDispatchTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private void activate(final String id, final ChaosSelector selector, final ChaosEffect effect) {
    runtime.activate(
        ChaosScenario.builder(id)
            .selector(selector)
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  private static ChaosSelector network(final OperationType operation, final Integer port) {
    return new ChaosSelector.NetworkSelector(
        Set.of(operation), NamePattern.any(), "127.0.0.1", port, null, null);
  }

  private static ChaosSelector files(final OperationType operation) {
    return new ChaosSelector.FileIoSelector(
        Set.of(operation), PathPattern.under("/data"), null, null);
  }

  @Nested
  @DisplayName("socket channels")
  class SocketChannels {

    @Test
    @DisplayName("reads match the channel's peer and are trickled; other peers are not")
    void readsMatchThePeer() throws Throwable {
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
          SocketChannel channel =
              SocketChannel.open(new InetSocketAddress("127.0.0.1", server.getLocalPort()))) {
        activate(
            "trickle",
            network(OperationType.SOCKET_READ, server.getLocalPort()),
            ChaosEffect.trickle(8));
        assertThat(runtime.beforeSocketChannelRead(channel, 100)).isEqualTo(8);
        // an unconnected channel has no peer: the address filter cannot match
        try (SocketChannel unconnected = SocketChannel.open()) {
          assertThat(runtime.beforeSocketChannelRead(unconnected, 100)).isEqualTo(100);
        }
      }
    }

    @Test
    @DisplayName("writes are torn to a short count")
    void writesAreTorn() throws Throwable {
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
          SocketChannel channel =
              SocketChannel.open(new InetSocketAddress("127.0.0.1", server.getLocalPort()))) {
        activate("torn", network(OperationType.SOCKET_WRITE, null), ChaosEffect.tornWrite());
        assertThat(runtime.beforeSocketChannelWrite(channel, 100)).isBetween(1, 99);
      }
    }

    @Test
    @DisplayName("an idle runtime returns the request unchanged")
    void idle() throws Throwable {
      assertThat(runtime.beforeSocketChannelRead(null, 10)).isEqualTo(10);
      assertThat(runtime.beforeSocketChannelWrite(null, 10)).isEqualTo(10);
    }

    @Test
    @DisplayName("connectionReset sets linger 0 on an open channel before its close")
    void resetSetsLingerZero() throws Throwable {
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
          SocketChannel channel =
              SocketChannel.open(new InetSocketAddress("127.0.0.1", server.getLocalPort()))) {
        activate("reset", network(OperationType.SOCKET_CLOSE, null), ChaosEffect.connectionReset());
        runtime.beforeSocketClose(channel);
        assertThat(channel.getOption(StandardSocketOptions.SO_LINGER)).isZero();
      }
    }

    @Test
    @DisplayName("a reset of a closed channel closes normally")
    void resetOfClosedChannel() throws Throwable {
      activate("reset", network(OperationType.SOCKET_CLOSE, null), ChaosEffect.connectionReset());
      final SocketChannel channel = SocketChannel.open();
      channel.close();
      runtime.beforeSocketClose(channel);
      assertThat(channel.isOpen()).isFalse();
    }
  }

  @Nested
  @DisplayName("redirects")
  class Redirects {

    @Test
    @DisplayName("a redirect returns the other endpoint, keeping the port when none is given")
    void redirect() throws Throwable {
      activate(
          "failover",
          network(OperationType.SOCKET_CONNECT, 5432),
          ChaosEffect.redirect("127.0.0.1", 6543));
      assertThat(runtime.beforeSocketConnect(null, new InetSocketAddress("127.0.0.1", 5432), 0))
          .isEqualTo(new InetSocketAddress("127.0.0.1", 6543));
      assertThat(runtime.beforeSocketConnect(null, new InetSocketAddress("127.0.0.1", 5433), 0))
          .isNull();
    }

    @Test
    @DisplayName("a redirect without a port keeps the requested one")
    void keepsPort() throws Throwable {
      activate(
          "other-host",
          network(OperationType.SOCKET_CONNECT, null),
          ChaosEffect.redirect("localhost"));
      assertThat(
              runtime
                  .beforeSocketConnect(null, new InetSocketAddress("127.0.0.1", 5432), 0)
                  .getPort())
          .isEqualTo(5432);
    }

    @Test
    @DisplayName("a redirect applies to connects only, and rejects a bad port")
    void validation() {
      assertThatThrownBy(
              () ->
                  activate(
                      "bad",
                      network(OperationType.SOCKET_READ, null),
                      ChaosEffect.redirect("localhost", 1)))
          .isInstanceOf(com.macstab.chaos.jvm.api.ChaosValidationException.class)
          .hasMessageContaining("RedirectEffect applies only to [SOCKET_CONNECT]");
      assertThatThrownBy(() -> ChaosEffect.redirect("localhost", 70_000))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ChaosEffect.redirect(" "))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("binds")
  class Binds {

    @Test
    @DisplayName("an errno on the bound address fails with BindException")
    void bindFails() {
      activate(
          "in-use",
          network(OperationType.SOCKET_BIND, 8080),
          ChaosEffect.errno(ChaosEffect.Errno.EADDRINUSE));
      assertThatThrownBy(() -> runtime.beforeSocketBind(InetAddress.getByName("127.0.0.1"), 8080))
          .isInstanceOf(java.net.BindException.class)
          .hasMessage("Address already in use");
    }

    @Test
    @DisplayName("another port or no address does not match")
    void otherBinds() throws Throwable {
      activate(
          "in-use",
          network(OperationType.SOCKET_BIND, 8080),
          ChaosEffect.errno(ChaosEffect.Errno.EADDRINUSE));
      runtime.beforeSocketBind(InetAddress.getByName("127.0.0.1"), 8081);
      runtime.beforeSocketBind(null, 8080);

      assertThat(runtime.diagnostics().scenario("in-use").orElseThrow().matchedCount())
          .as("neither bind is matched")
          .isZero();
      assertThatThrownBy(() -> runtime.beforeSocketBind(InetAddress.getByName("127.0.0.1"), 8080))
          .as("the selected port is")
          .isInstanceOf(java.net.BindException.class);
    }
  }

  @Nested
  @DisplayName("datagrams")
  class Datagrams {

    @Test
    @DisplayName("a suppressed send is dropped")
    void suppressedSendIsDropped() throws Throwable {
      activate("drop", network(OperationType.DATAGRAM_SEND, 9999), ChaosEffect.suppress());
      assertThat(
              runtime.beforeDatagram(
                  "DATAGRAM_SEND", null, new InetSocketAddress("127.0.0.1", 9999), 10))
          .isEqualTo(ChaosDispatcher.DATAGRAM_DROPPED);
      assertThat(
              runtime.beforeDatagram(
                  "DATAGRAM_SEND", null, new InetSocketAddress("127.0.0.1", 9998), 10))
          .isEqualTo(10);
    }

    @Test
    @DisplayName("a send without a target uses the channel's connected peer")
    void connectedPeer() throws Throwable {
      try (DatagramChannel channel =
          DatagramChannel.open().connect(new InetSocketAddress("127.0.0.1", 9999))) {
        activate("drop", network(OperationType.DATAGRAM_SEND, 9999), ChaosEffect.suppress());
        assertThat(runtime.beforeDatagram("DATAGRAM_SEND", channel, null, 10))
            .isEqualTo(ChaosDispatcher.DATAGRAM_DROPPED);
      }
    }

    @Test
    @DisplayName("a corrupted receive is flagged")
    void corruptedReceive() throws Throwable {
      activate(
          "corrupt",
          ChaosSelector.network(Set.of(OperationType.DATAGRAM_RECEIVE)),
          ChaosEffect.corruptRead());
      assertThat(runtime.beforeDatagram("DATAGRAM_RECEIVE", null, null, 64)).isNegative();
    }

    @Test
    @DisplayName("a refused datagram is PortUnreachableException")
    void refusedReceive() {
      activate(
          "refused",
          ChaosSelector.network(Set.of(OperationType.DATAGRAM_RECEIVE)),
          ChaosEffect.errno(ChaosEffect.Errno.ECONNREFUSED));
      assertThatThrownBy(() -> runtime.beforeDatagram("DATAGRAM_RECEIVE", null, null, 64))
          .isInstanceOf(java.net.PortUnreachableException.class);
    }

    @Test
    @DisplayName("an idle runtime sends and receives unchanged")
    void idle() throws Throwable {
      assertThat(runtime.beforeDatagram("DATAGRAM_SEND", null, null, 5)).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("reverse lookups")
  class ReverseLookups {

    @Test
    @DisplayName("a rejection fails the lookup of the matching address only")
    void rejection() throws Throwable {
      activate(
          "no-ptr",
          ChaosSelector.dns(Set.of(OperationType.DNS_REVERSE), NamePattern.exact("10.0.0.1")),
          ChaosEffect.reject("no PTR"));
      assertThatThrownBy(() -> runtime.beforeDnsReverse("10.0.0.1"))
          .isInstanceOf(java.net.UnknownHostException.class);
      runtime.beforeDnsReverse("10.0.0.2");
    }
  }

  @Nested
  @DisplayName("system clock reads")
  class ClockReads {

    @Test
    @DisplayName("CLOCK_READ skews instant() and millis() by the same amount")
    void skew() {
      activate(
          "ahead",
          ChaosSelector.jvmRuntime(Set.of(OperationType.CLOCK_READ)),
          ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED));
      final Instant real = Instant.ofEpochSecond(1_000_000L, 123_456_789L);
      assertThat(runtime.adjustClockInstant(real)).isEqualTo(real.plus(Duration.ofHours(1)));
      assertThat(runtime.adjustClockReadMillis(5_000L)).isEqualTo(5_000L + 3_600_000L);
    }

    @Test
    @DisplayName("without a scenario the reads are unchanged")
    void unchanged() {
      final Instant real = Instant.ofEpochSecond(42L);
      assertThat(runtime.adjustClockInstant(real)).isSameAs(real);
      assertThat(runtime.adjustClockReadMillis(7L)).isEqualTo(7L);
    }
  }

  @Nested
  @DisplayName("opens, maps and transfers")
  class Files {

    @Test
    @DisplayName(
        "a java.io open fails as FileNotFoundException, an nio open as NoSuchFileException")
    void opens() {
      activate(
          "gone", files(OperationType.FILE_IO_OPEN), ChaosEffect.errno(ChaosEffect.Errno.ENOENT));
      assertThatThrownBy(
              () ->
                  runtime.beforeFileIo(
                      "FILE_IO_OPEN", new java.io.ByteArrayInputStream(new byte[0]), "/data/f", -1))
          .isInstanceOf(java.io.FileNotFoundException.class)
          .hasMessage("/data/f (No such file or directory)");
      assertThatThrownBy(() -> runtime.beforeFileIo("FILE_IO_OPEN", this, "/data/f", -1))
          .isInstanceOf(java.nio.file.NoSuchFileException.class);
    }

    @Test
    @DisplayName("ENOMEM on a map is the JDK's Map failed")
    void mapFails() {
      activate(
          "no-memory",
          files(OperationType.FILE_IO_MAP),
          ChaosEffect.errno(ChaosEffect.Errno.ENOMEM));
      assertThatThrownBy(() -> runtime.beforeFileIo("FILE_IO_MAP", this, "/data/f", 4096))
          .isInstanceOf(java.io.IOException.class)
          .hasMessage("Map failed")
          .hasCauseInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("corruptRead does not apply to transferTo")
    void transferToIsNotCorrupted() throws Throwable {
      activate("corrupt", files(OperationType.FILE_IO_READ), ChaosEffect.corruptRead());
      assertThat(runtime.beforeFileIo(ChaosDispatcher.TRANSFER_TO, this, "/data/f", 100))
          .isEqualTo(100);
      assertThat(runtime.beforeFileIo("FILE_IO_READ", this, "/data/f", 100)).isNegative();
    }

    @Test
    @DisplayName("a torn transferFrom of a file channel is a short count")
    void transferFromIsTorn() throws Throwable {
      activate("torn", files(OperationType.FILE_IO_WRITE), ChaosEffect.tornWrite());
      final Object fileChannel = new StandInFileChannelImpl();
      assertThat(runtime.beforeFileIo(ChaosDispatcher.TRANSFER_FROM, fileChannel, "/data/f", 100))
          .isBetween(1, 99);
    }
  }

  /**
   * Its name ends like the JDK's {@code sun.nio.ch.FileChannelImpl}: a channel that can write
   * short.
   */
  private static final class StandInFileChannelImpl {}
}

package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnixDomainSocketAddress;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The socket, channel, datagram and selector entry points: the peer each one reports to a {@link
 * ChaosSelector.NetworkSelector}, the byte count it hands back, and that a composed delay runs
 * before a terminal action that ends the call early.
 */
@DisplayName("Network dispatch")
class NetworkDispatchTest {

  /** Elapsed time a composed {@link #DELAY} must at least take; below it, the delay was skipped. */
  private static final Duration DELAY = Duration.ofMillis(60);

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private void activate(final String id, final ChaosSelector selector, final ChaosEffect effect) {
    runtime.activate(
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  private void delayThen(final ChaosSelector selector, final ChaosEffect terminal) {
    activate("delay", selector, ChaosEffect.delay(DELAY));
    activate("terminal", selector, terminal);
  }

  private static Duration timed(final ThrowingRunnable action) throws Throwable {
    final long start = System.nanoTime();
    action.run();
    return Duration.ofNanos(System.nanoTime() - start);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Throwable;
  }

  private static ChaosSelector.NetworkSelector toPort(
      final OperationType operation, final int port) {
    return new ChaosSelector.NetworkSelector(
        Set.of(operation), NamePattern.any(), null, port, null, null);
  }

  private static ChaosSelector.NetworkSelector toHost(
      final OperationType operation, final NamePattern host) {
    return new ChaosSelector.NetworkSelector(Set.of(operation), host, null, null, null, null);
  }

  private static InetAddress address(final String host, final int... octets) throws IOException {
    final byte[] bytes = new byte[octets.length];
    for (int i = 0; i < octets.length; i++) {
      bytes[i] = (byte) octets[i];
    }
    return InetAddress.getByAddress(host, bytes);
  }

  @Nested
  @DisplayName("socket stream reads and writes")
  class Streams {

    @Test
    @DisplayName("without a scenario a read and a write keep their byte count")
    void idleKeepsTheCount() throws Throwable {
      assertThat(runtime.beforeSocketRead(this, 7, null, 0, 0)).isEqualTo(7);
      assertThat(runtime.beforeSocketWrite(this, 9, null, 0)).isEqualTo(9);
    }

    @Test
    @DisplayName("a scenario that only delays keeps the byte count")
    void delayKeepsTheCount() throws Throwable {
      activate(
          "read",
          ChaosSelector.network(Set.of(OperationType.SOCKET_READ)),
          ChaosEffect.delay(Duration.ofMillis(1)));
      activate(
          "write",
          ChaosSelector.network(Set.of(OperationType.SOCKET_WRITE)),
          ChaosEffect.delay(Duration.ofMillis(1)));

      assertThat(runtime.beforeSocketRead(this, 7, null, 0, 0)).isEqualTo(7);
      assertThat(runtime.beforeSocketWrite(this, 9, null, 0)).isEqualTo(9);
    }

    @Test
    @DisplayName("a read matches by the peer's port only when the peer is known")
    void readMatchesThePeerPort() throws Throwable {
      activate("db", toPort(OperationType.SOCKET_READ, 5432), ChaosEffect.trickle(2));
      final InetAddress peer = address("db.internal", 10, 0, 0, 5);

      assertThat(runtime.beforeSocketRead(this, 100, peer, 5432, 0)).isEqualTo(2);
      assertThat(runtime.beforeSocketRead(this, 100, null, 5432, 0)).isEqualTo(100);
    }

    @Test
    @DisplayName("a read matches a CIDR by the peer's address")
    void readMatchesThePeerAddress() throws Throwable {
      activate(
          "internal",
          new ChaosSelector.NetworkSelector(
              Set.of(OperationType.SOCKET_READ), NamePattern.any(), "10.0.0.0/8", null, null, null),
          ChaosEffect.trickle(3));

      assertThat(runtime.beforeSocketRead(this, 100, address("x", 10, 1, 2, 3), 80, 0))
          .isEqualTo(3);
      assertThat(runtime.beforeSocketRead(this, 100, address("y", 192, 168, 0, 1), 80, 0))
          .isEqualTo(100);
    }

    @Test
    @DisplayName("a read without a peer never matches a host pattern")
    void noPeerNoHost() throws Throwable {
      activate("any-host", toHost(OperationType.SOCKET_READ, NamePattern.glob("*")), trickle());

      assertThat(runtime.beforeSocketRead(this, 100, null, 0, 0)).isEqualTo(100);
      assertThat(runtime.beforeSocketRead(this, 100, address("db", 10, 0, 0, 1), 1, 0))
          .isEqualTo(1);
    }

    @Test
    @DisplayName("a corrupted read encodes its byte count as -(count) - 1")
    void corruptReadEncoding() throws Throwable {
      activate("corrupt", ChaosSelector.network(Set.of(OperationType.SOCKET_READ)), corruptRead());

      assertThat(runtime.beforeSocketRead(this, 10, null, 0, 0)).isEqualTo(-11);
      assertThat(runtime.beforeSocketRead(this, 0, null, 0, 0)).isEqualTo(-1);
    }

    @Test
    @DisplayName("a read timeout waits out SO_TIMEOUT, then fails with \"Read timed out\"")
    void readTimeoutWaitsOutTheTimeout() throws Throwable {
      activate(
          "stall", ChaosSelector.network(Set.of(OperationType.SOCKET_READ)), ChaosEffect.timeout());

      final Duration elapsed =
          timed(
              () ->
                  assertThatThrownBy(() -> runtime.beforeSocketRead(this, 10, null, 0, 60))
                      .isInstanceOf(SocketTimeoutException.class)
                      .hasMessage("Read timed out"));

      assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(60));
    }

    @Test
    @DisplayName("a timeout never fires on a read without SO_TIMEOUT, and keeps its budget")
    void timeoutNeverFiresOnAnInfiniteRead() throws Throwable {
      runtime.activate(
          ChaosScenario.builder("stall-once")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
              .effect(ChaosEffect.timeout())
              .activationPolicy(ActivationPolicy.builder().maxApplications(1L).build())
              .build());

      assertThat(runtime.beforeSocketRead(this, 10, null, 0, 0)).isEqualTo(10);
      assertThat(runtime.diagnostics().scenario("stall-once").orElseThrow().appliedCount())
          .isZero();
      assertThatThrownBy(() -> runtime.beforeSocketRead(this, 10, null, 0, 1))
          .as("the call without a timeout did not spend the single application")
          .isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("a torn write of two bytes sends exactly one")
    void tornWriteOfTwoBytes() throws Throwable {
      activate("torn", ChaosSelector.network(Set.of(OperationType.SOCKET_WRITE)), tornWrite());

      for (int write = 0; write < 50; write++) {
        assertThat(runtime.beforeSocketWrite(this, 2, null, 0)).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("a torn write always sends a non-empty strict prefix")
    void tornWriteIsAStrictPrefix() throws Throwable {
      activate("torn", ChaosSelector.network(Set.of(OperationType.SOCKET_WRITE)), tornWrite());

      for (int write = 0; write < 200; write++) {
        assertThat(runtime.beforeSocketWrite(this, 5, null, 0)).isBetween(1, 4);
      }
    }

    @Test
    @DisplayName("a single byte cannot be torn and does not count an application")
    void singleByteIsNotTorn() throws Throwable {
      activate("torn", ChaosSelector.network(Set.of(OperationType.SOCKET_WRITE)), tornWrite());

      assertThat(runtime.beforeSocketWrite(this, 1, null, 0)).isEqualTo(1);
      assertThat(runtime.diagnostics().scenario("torn").orElseThrow().appliedCount()).isZero();
    }
  }

  @Nested
  @DisplayName("socket connects")
  class Connects {

    @Test
    @DisplayName("an unresolved endpoint matches by the host name it was given")
    void unresolvedEndpointMatchesItsHostName() {
      activate(
          "db",
          toHost(OperationType.SOCKET_CONNECT, NamePattern.exact("db.internal")),
          ChaosEffect.reject("db down"));

      assertThatThrownBy(
              () ->
                  runtime.beforeSocketConnect(
                      new Socket(), InetSocketAddress.createUnresolved("db.internal", 5432), 0))
          .hasMessageContaining("db down");
    }

    @Test
    @DisplayName("a Unix-domain endpoint matches by its path")
    void unixEndpointMatchesItsPath() {
      final UnixDomainSocketAddress socketPath = UnixDomainSocketAddress.of("/tmp/app.sock");
      activate(
          "unix",
          toHost(OperationType.SOCKET_CONNECT, NamePattern.exact(socketPath.toString())),
          ChaosEffect.reject("unix down"));

      assertThatThrownBy(() -> runtime.beforeSocketConnect(null, socketPath, 0))
          .hasMessageContaining("unix down");
    }

    @Test
    @DisplayName("a redirect waits out a composed delay before naming the new endpoint")
    void delayBeforeRedirect() throws Throwable {
      delayThen(
          ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)),
          ChaosEffect.redirect("127.0.0.1", 9999));

      final InetSocketAddress[] target = new InetSocketAddress[1];
      final Duration elapsed =
          timed(
              () ->
                  target[0] =
                      runtime.beforeSocketConnect(
                          new Socket(), InetSocketAddress.createUnresolved("db", 5432), 0));

      assertThat(target[0].getPort()).isEqualTo(9999);
      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
    }
  }

  @Nested
  @DisplayName("connected sockets")
  class ConnectedSockets {

    @Test
    @DisplayName("a close matches by the peer of the connected socket; an unconnected one has none")
    void closeMatchesThePeer() throws Throwable {
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
          Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
          Socket accepted = server.accept()) {
        activate(
            "peer",
            toPort(OperationType.SOCKET_CLOSE, server.getLocalPort()),
            ChaosEffect.reject("close failed"));

        assertThatThrownBy(() -> runtime.beforeSocketClose(client))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("close failed");
        assertThatCode(() -> runtime.beforeSocketClose(new Socket())).doesNotThrowAnyException();
        assertThat(accepted.isConnected()).isTrue();
      }
    }

    @Test
    @DisplayName("a connection reset sets linger 0 on the socket, after a composed delay")
    void connectionResetSetsLingerZero() throws Throwable {
      delayThen(
          ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)), ChaosEffect.connectionReset());
      try (Socket socket = new Socket()) {
        assertThat(socket.getSoLinger()).isEqualTo(-1);

        final Duration elapsed = timed(() -> runtime.beforeSocketClose(socket));

        assertThat(socket.getSoLinger()).isZero();
        assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
      }
    }

    @Test
    @DisplayName("a half-close matches by the peer address and port it is given")
    void shutdownMatchesThePeer() throws Throwable {
      assertThatCode(() -> runtime.beforeSocketShutdown(this, null, 0)).doesNotThrowAnyException();
      activate("peer", toPort(OperationType.SOCKET_SHUTDOWN, 5432), ChaosEffect.reject("half"));

      assertThatThrownBy(() -> runtime.beforeSocketShutdown(this, address("db", 10, 0, 0, 1), 5432))
          .isInstanceOf(SocketException.class)
          .hasMessageContaining("half");
      assertThatCode(() -> runtime.beforeSocketShutdown(new Socket(), null, 5432))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("NIO channels and selectors")
  class Channels {

    @Test
    @DisplayName("a channel operation matches the channel class; an unknown channel is Channel")
    void channelOperation() throws Throwable {
      runtime.beforeNioChannelOp("NIO_CHANNEL_CONNECT", this);
      activate(
          "channel",
          new ChaosSelector.NioSelector(
              Set.of(OperationType.NIO_CHANNEL_CONNECT),
              NamePattern.exact("java.nio.channels.Channel")),
          ChaosEffect.reject("no connect"));

      assertThatThrownBy(() -> runtime.beforeNioChannelOp("NIO_CHANNEL_CONNECT", null))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("no connect");
      runtime.beforeNioChannelOp("NIO_CHANNEL_CONNECT", this);
    }

    @Test
    @DisplayName("a channel write keeps its count without a scenario, and is torn under one")
    void channelWrite() throws Throwable {
      assertThat(runtime.beforeNioChannelWrite(this, 10)).isEqualTo(10);
      activate(
          "torn",
          ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_WRITE)),
          ChaosEffect.tornWrite());

      for (int write = 0; write < 20; write++) {
        assertThat(runtime.beforeNioChannelWrite(null, 10)).isBetween(1, 9);
      }
    }

    @Test
    @DisplayName("a spurious wakeup returns from select at once")
    void spuriousWakeup() throws Throwable {
      assertThat(runtime.beforeNioSelect(this, 0L)).isFalse();
      activate(
          "wakeup",
          ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT)),
          ChaosEffect.spuriousWakeup());

      assertThat(runtime.beforeNioSelect(null, 0L)).isTrue();
    }

    @Test
    @DisplayName("a select timeout waits out the select's own timeout and reports no channel")
    void selectTimeout() throws Throwable {
      activate(
          "stall",
          ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT)),
          ChaosEffect.timeout());

      final boolean[] woken = new boolean[1];
      final Duration elapsed = timed(() -> woken[0] = runtime.beforeNioSelect(this, 60L));

      assertThat(woken[0]).isTrue();
      assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(60));
    }
  }

  @Nested
  @DisplayName("datagrams")
  class Datagrams {

    @Test
    @DisplayName("a dropped datagram send waits out a composed delay first")
    void delayBeforeDrop() throws Throwable {
      delayThen(ChaosSelector.network(Set.of(OperationType.DATAGRAM_SEND)), ChaosEffect.suppress());

      final int[] result = new int[1];
      final Duration elapsed =
          timed(() -> result[0] = runtime.beforeDatagram("DATAGRAM_SEND", null, null, 64));

      assertThat(result[0]).isEqualTo(ChaosDispatcher.DATAGRAM_DROPPED);
      assertThat(elapsed).isGreaterThanOrEqualTo(DELAY);
    }
  }

  private static ChaosEffect trickle() {
    return ChaosEffect.trickle(1);
  }

  private static ChaosEffect corruptRead() {
    return ChaosEffect.corruptRead();
  }

  private static ChaosEffect tornWrite() {
    return ChaosEffect.tornWrite();
  }
}

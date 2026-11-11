package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.jspecify.annotations.Nullable;

/**
 * Probe main for network effects on real loopback sockets and channels: errnos, rejects of the
 * operations other than read, write and connect, a spurious selector wakeup, TLS handshakes, and
 * one channel connect under both a socket and a channel scenario. Each row prints the class and
 * message of what the call threw ({@code none} when it returned).
 */
public final class NetworkRealCallProbeMain {

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

  private NetworkRealCallProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "errnos" -> errnos();
      case "rejects" -> rejects();
      case "spurious-wakeup" -> spuriousWakeup();
      case "tls" -> tls();
      case "connect-both" -> connectBoth();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /** Runs {@code call} under {@code effect} on {@code operations}; prints type and message. */
  private static void row(
      final String row,
      final Set<OperationType> operations,
      final ChaosEffect effect,
      final RealCallRows.Call call) {
    final ChaosSelector selector =
        operations.stream().anyMatch(op -> op.name().startsWith("NIO_"))
            ? ChaosSelector.nio(operations)
            : ChaosSelector.network(operations);
    print(row, RealCallRows.during(row, selector, effect, call));
  }

  private static void print(final String row, final @Nullable Throwable failure) {
    System.out.println(row + ".type=" + (failure == null ? "none" : failure.getClass().getName()));
    if (failure != null) {
      System.out.println(row + ".message=" + failure.getMessage());
    }
  }

  private static ChaosEffect errno(final ChaosEffect.Errno errno) {
    return ChaosEffect.errno(errno);
  }

  /** A connected loopback pair: {@code [client, peer]}. */
  private static Socket[] pair(final ServerSocket server) throws Exception {
    final Socket client = new Socket(LOOPBACK, server.getLocalPort());
    return new Socket[] {client, server.accept()};
  }

  private static void errnos() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 8, LOOPBACK)) {
      final Socket[] sockets = pair(server);
      try (Socket client = sockets[0];
          Socket peer = sockets[1]) {
        row(
            "readReset",
            Set.of(OperationType.SOCKET_READ),
            errno(ChaosEffect.Errno.ECONNRESET),
            () -> client.getInputStream().read());
        row(
            "readTimedOut",
            Set.of(OperationType.SOCKET_READ),
            errno(ChaosEffect.Errno.ETIMEDOUT),
            () -> client.getInputStream().read());
        row(
            "readHostUnreachable",
            Set.of(OperationType.SOCKET_READ),
            errno(ChaosEffect.Errno.EHOSTUNREACH),
            () -> client.getInputStream().read());
        row(
            "writeBrokenPipe",
            Set.of(OperationType.SOCKET_WRITE),
            errno(ChaosEffect.Errno.EPIPE),
            () -> client.getOutputStream().write(1));
      }
      row(
          "connectHostUnreachable",
          Set.of(OperationType.SOCKET_CONNECT),
          errno(ChaosEffect.Errno.EHOSTUNREACH),
          () -> new Socket(LOOPBACK, server.getLocalPort()).close());
      row(
          "connectTimedOut",
          Set.of(OperationType.SOCKET_CONNECT),
          errno(ChaosEffect.Errno.ETIMEDOUT),
          () -> new Socket(LOOPBACK, server.getLocalPort()).close());
    }
    try (ServerSocketChannel server = channelServer()) {
      try (SocketChannel client = SocketChannel.open(server.getLocalAddress());
          SocketChannel peer = server.accept()) {
        row(
            "channelReadReset",
            Set.of(OperationType.NIO_CHANNEL_READ),
            errno(ChaosEffect.Errno.ECONNRESET),
            () -> client.read(ByteBuffer.allocate(4)));
        row(
            "channelWriteBrokenPipe",
            Set.of(OperationType.NIO_CHANNEL_WRITE),
            errno(ChaosEffect.Errno.EPIPE),
            () -> client.write(ByteBuffer.allocate(4)));
      }
      row(
          "channelConnectRefused",
          Set.of(OperationType.NIO_CHANNEL_CONNECT),
          errno(ChaosEffect.Errno.ECONNREFUSED),
          () -> SocketChannel.open(server.getLocalAddress()).close());
      try (SocketChannel client = SocketChannel.open(server.getLocalAddress())) {
        row(
            "channelAcceptTooManyFiles",
            Set.of(OperationType.NIO_CHANNEL_ACCEPT),
            errno(ChaosEffect.Errno.EMFILE),
            () -> server.accept().close());
      }
    }
  }

  private static ServerSocketChannel channelServer() throws Exception {
    return ServerSocketChannel.open().bind(new InetSocketAddress(LOOPBACK, 0));
  }

  private static void rejects() throws Exception {
    final ChaosEffect reject = ChaosEffect.reject("chaos");
    try (ServerSocket server = new ServerSocket(0, 8, LOOPBACK)) {
      try (Socket client = new Socket(LOOPBACK, server.getLocalPort())) {
        row("accept", Set.of(OperationType.SOCKET_ACCEPT), reject, () -> server.accept().close());
      }
      final Socket[] sockets = pair(server);
      try (Socket client = sockets[0];
          Socket peer = sockets[1]) {
        row("shutdown", Set.of(OperationType.SOCKET_SHUTDOWN), reject, client::shutdownOutput);
      }
    }
    row(
        "bind",
        Set.of(OperationType.SOCKET_BIND),
        reject,
        () -> new ServerSocket(0, 8, LOOPBACK).close());
    try (ServerSocketChannel server = channelServer()) {
      try (SocketChannel client = SocketChannel.open(server.getLocalAddress());
          SocketChannel peer = server.accept()) {
        row(
            "channelRead",
            Set.of(OperationType.NIO_CHANNEL_READ),
            reject,
            () -> client.read(ByteBuffer.allocate(4)));
        row(
            "channelWrite",
            Set.of(OperationType.NIO_CHANNEL_WRITE),
            reject,
            () -> client.write(ByteBuffer.allocate(4)));
      }
      row(
          "channelConnect",
          Set.of(OperationType.NIO_CHANNEL_CONNECT),
          reject,
          () -> SocketChannel.open(server.getLocalAddress()).close());
      try (SocketChannel client = SocketChannel.open(server.getLocalAddress())) {
        row(
            "channelAccept",
            Set.of(OperationType.NIO_CHANNEL_ACCEPT),
            reject,
            () -> server.accept().close());
      }
    }
  }

  /**
   * A {@code spuriousWakeup} on a selector whose channel is readable: {@code select()} returns 0
   * with no selected key at once; without the scenario it reports the readable key.
   */
  private static void spuriousWakeup() throws Exception {
    try (ServerSocketChannel server = channelServer();
        SocketChannel client = SocketChannel.open(server.getLocalAddress());
        SocketChannel peer = server.accept();
        Selector selector = Selector.open()) {
      client.configureBlocking(false);
      client.register(selector, SelectionKey.OP_READ);
      peer.write(ByteBuffer.wrap(new byte[] {1}));
      try (ChaosActivationHandle wakeup =
          RealCallRows.activate(
              "spurious",
              ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT)),
              ChaosEffect.spuriousWakeup())) {
        final long start = System.nanoTime();
        System.out.println("spurious.selected=" + selector.select(5_000L));
        System.out.println(
            "spurious.millis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        System.out.println("spurious.keys=" + selector.selectedKeys().size());
        System.out.println("spurious.applied=" + wakeup.appliedCount());
      }
      System.out.println("after.selected=" + selector.select(5_000L));
    }
  }

  /**
   * TLS on an {@code SSLSocket} to a loopback server whose certificate is not trusted: the real
   * handshake fails; under a 300 ms {@code SSL_HANDSHAKE} delay it fails after the delay, both
   * explicitly ({@code startHandshake}) and implicitly (the first write); under a reject it fails
   * with the handshake failure before any byte is sent.
   */
  private static void tls() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 8, LOOPBACK)) {
      final Thread acceptor =
          new Thread(
              () -> {
                while (!server.isClosed()) {
                  try {
                    server.accept().close();
                  } catch (final Exception closed) {
                    return;
                  }
                }
              });
      acceptor.setDaemon(true);
      acceptor.start();
      final SSLContext context = SSLContext.getDefault();
      try (ChaosActivationHandle delay =
          RealCallRows.activate(
              "tls-delay",
              ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)),
              ChaosEffect.delay(Duration.ofMillis(300)))) {
        timedRow("explicitDelay", () -> tlsSocket(context, server).startHandshake());
        timedRow("implicitDelay", () -> tlsSocket(context, server).getOutputStream().write(1));
        System.out.println("delay.applied=" + delay.appliedCount());
      }
      final SSLSocket rejected = tlsSocket(context, server);
      print(
          "reject",
          RealCallRows.during(
              "tls-reject",
              ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)),
              ChaosEffect.reject("chaos"),
              rejected::startHandshake));
      System.out.println("reject.closed=" + rejected.isClosed());
    }
  }

  private static SSLSocket tlsSocket(final SSLContext context, final ServerSocket server)
      throws Exception {
    return (SSLSocket) context.getSocketFactory().createSocket(LOOPBACK, server.getLocalPort());
  }

  private static void timedRow(final String row, final RealCallRows.Call call) {
    final long start = System.nanoTime();
    print(row, outcome(call));
    System.out.println(row + ".millis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  private static @Nullable Throwable outcome(final RealCallRows.Call call) {
    try {
      call.run();
      return null;
    } catch (final Throwable thrown) {
      return thrown;
    }
  }

  /**
   * One {@code SocketChannel.connect} under a {@code SOCKET_CONNECT} and a {@code
   * NIO_CHANNEL_CONNECT} delay: each operation is evaluated once. Then a connect through the
   * channel's adaptor ({@code channel.socket().connect}). Prints the applied counts.
   */
  private static void connectBoth() throws Exception {
    try (ServerSocketChannel server = channelServer();
        ChaosActivationHandle socket =
            RealCallRows.activate(
                "socket-connect",
                ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)),
                ChaosEffect.delay(Duration.ofMillis(1)));
        ChaosActivationHandle channel =
            RealCallRows.activate(
                "channel-connect",
                ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_CONNECT)),
                ChaosEffect.delay(Duration.ofMillis(1)))) {
      SocketChannel.open(server.getLocalAddress()).close();
      server.accept().close();
      System.out.println("channel.socketApplied=" + socket.appliedCount());
      System.out.println("channel.channelApplied=" + channel.appliedCount());
      try (SocketChannel adapted = SocketChannel.open()) {
        adapted.socket().connect(server.getLocalAddress(), 1_000);
      }
      server.accept().close();
      System.out.println("adaptor.socketApplied=" + socket.appliedCount());
      System.out.println("adaptor.channelApplied=" + channel.appliedCount());
    }
  }
}

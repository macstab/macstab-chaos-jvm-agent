package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Probe main for injected network failures on real sockets and channels: what the endpoint looks
 * like after the failure, and what the threads using it see. Each mode prints {@code key=value}
 * lines.
 */
public final class NetworkFaultProbeMain {

  private static final long READER_WAIT_MILLIS = 1_000L;

  private NetworkFaultProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "close-reject" -> closeReject();
      case "connect-reject" -> connectReject();
      case "handshake-reject" -> handshakeReject();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  // ── a rejected close ─────────────────────────────────────────────────────

  /**
   * A {@code SOCKET_CLOSE} reject on a socket, a socket channel and a channel closed by an
   * interrupt, each with a reader blocked on it. Prints, per {@code socket}, {@code channel} and
   * {@code interrupt}: the close's failure, whether the endpoint is closed, whether the reader
   * ended within a second and what it saw, and what the peer reads.
   */
  private static void closeReject() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      final Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      try (Socket peer = server.accept()) {
        final Reader reader = Reader.start(() -> client.getInputStream().read());
        System.out.println("socket.close=" + closeRejected(client::close));
        System.out.println("socket.closed=" + client.isClosed());
        reader.report("socket");
        peer.setSoTimeout((int) READER_WAIT_MILLIS);
        System.out.println("socket.peerRead=" + read(peer.getInputStream()));
      }
    }
    try (ServerSocketChannel server = loopbackServer()) {
      final SocketChannel client = SocketChannel.open(server.getLocalAddress());
      try (SocketChannel peer = server.accept()) {
        final Reader reader = Reader.start(() -> client.read(ByteBuffer.allocate(4)));
        System.out.println("channel.close=" + closeRejected(client::close));
        System.out.println("channel.closed=" + !client.isOpen());
        reader.report("channel");
        peer.socket().setSoTimeout((int) READER_WAIT_MILLIS);
        System.out.println("channel.peerRead=" + read(peer.socket().getInputStream()));
      }
    }
    try (ServerSocketChannel server = loopbackServer()) {
      final SocketChannel client = SocketChannel.open(server.getLocalAddress());
      try (SocketChannel peer = server.accept()) {
        final Reader reader = Reader.start(() -> client.read(ByteBuffer.allocate(4)));
        // The interrupt closes the channel: through close() on JDK 25, implCloseChannel on 21.
        closeRejected(
            () -> {
              reader.thread.interrupt();
              reader.report("interrupt");
            });
        System.out.println("interrupt.closed=" + !client.isOpen());
      }
    }
  }

  /** Runs {@code call} under a {@code SOCKET_CLOSE} reject; returns the class it threw, or ok. */
  private static String closeRejected(final RealCallRows.Call call) {
    final Throwable failure =
        RealCallRows.during(
            "close-reject",
            ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
            ChaosEffect.reject("chaos close"),
            call);
    return failure == null ? "ok" : failure.getClass().getName();
  }

  /** What one read returns ({@code -1} at the end of the stream), or the class it throws. */
  private static String read(final java.io.InputStream in) {
    try {
      return Integer.toString(in.read());
    } catch (final IOException failed) {
      return failed.getClass().getName();
    }
  }

  // ── a rejected connect ───────────────────────────────────────────────────

  /**
   * A rejected connect of a socket, a socket channel, a channel's adaptor, and a channel under a
   * {@code NIO_CHANNEL_CONNECT} reject; and a {@code timeout()} on a socket's connect. Prints, per
   * row, the failure, whether the endpoint is still open, and what a second connect of the same
   * endpoint (with no scenario active) does.
   */
  private static void connectReject() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      final InetSocketAddress address = (InetSocketAddress) server.getLocalSocketAddress();
      final ChaosSelector socketConnect =
          ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT));

      final Socket socket = new Socket();
      RealCallRows.print(
          "socket",
          RealCallRows.during(
              "socket",
              socketConnect,
              ChaosEffect.reject("chaos connect"),
              () -> socket.connect(address, 1_000)));
      System.out.println("socket.open=" + !socket.isClosed());
      System.out.println("socket.retry=" + RealCallRows.outcome(() -> socket.connect(address)));
      socket.close();

      final SocketChannel channel = SocketChannel.open();
      RealCallRows.print(
          "channel",
          RealCallRows.during(
              "channel",
              socketConnect,
              ChaosEffect.reject("chaos connect"),
              () -> channel.connect(address)));
      System.out.println("channel.open=" + channel.isOpen());
      System.out.println("channel.retry=" + RealCallRows.outcome(() -> channel.connect(address)));
      channel.close();

      final SocketChannel adapted = SocketChannel.open();
      RealCallRows.print(
          "adaptor",
          RealCallRows.during(
              "adaptor",
              socketConnect,
              ChaosEffect.reject("chaos connect"),
              () -> adapted.socket().connect(address, 1_000)));
      System.out.println("adaptor.open=" + adapted.isOpen());
      adapted.close();

      final SocketChannel nio = SocketChannel.open();
      RealCallRows.print(
          "nio",
          RealCallRows.during(
              "nio",
              ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_CONNECT)),
              ChaosEffect.reject("chaos connect"),
              () -> nio.connect(address)));
      System.out.println("nio.open=" + nio.isOpen());
      nio.close();

      final Socket timedOut = new Socket();
      RealCallRows.print(
          "timeout",
          RealCallRows.during(
              "timeout",
              socketConnect,
              ChaosEffect.timeout(),
              () -> timedOut.connect(address, 100)));
      System.out.println("timeout.open=" + !timedOut.isClosed());
      timedOut.close();
    }
  }

  // ── a rejected TLS handshake ─────────────────────────────────────────────

  /**
   * An {@code SSL_HANDSHAKE} reject on an {@code SSLSocket} layered over a connected socket and on
   * an {@code SSLEngine}. Prints the failure, whether the socket or the engine's outbound side is
   * closed, and what a second handshake of the same socket (with no scenario active) does.
   */
  private static void handshakeReject() throws Exception {
    final ChaosSelector handshake = ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE));
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket plain = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        Socket peer = server.accept()) {
      // The peer never answers: a handshake that really starts ends at the read timeout.
      plain.setSoTimeout((int) READER_WAIT_MILLIS);
      final SSLSocket tls =
          (SSLSocket)
              ((SSLSocketFactory) SSLSocketFactory.getDefault())
                  .createSocket(plain, "localhost", server.getLocalPort(), true);
      RealCallRows.print(
          "socket",
          RealCallRows.during(
              "socket", handshake, ChaosEffect.reject("chaos handshake"), tls::startHandshake));
      System.out.println("socket.closed=" + tls.isClosed());
      System.out.println("socket.retry=" + RealCallRows.outcome(tls::startHandshake));
      tls.close();
    }
    final SSLEngine engine = SSLContext.getDefault().createSSLEngine("localhost", 443);
    engine.setUseClientMode(true);
    RealCallRows.print(
        "engine",
        RealCallRows.during(
            "engine", handshake, ChaosEffect.reject("chaos handshake"), engine::beginHandshake));
    System.out.println("engine.outboundDone=" + engine.isOutboundDone());
  }

  private static ServerSocketChannel loopbackServer() throws IOException {
    return ServerSocketChannel.open()
        .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
  }

  /** A daemon thread blocked in a read, and what it ended with. */
  private record Reader(Thread thread, AtomicReference<String> ended) {

    static Reader start(final RealCallRows.Call read) throws InterruptedException {
      final AtomicReference<String> ended = new AtomicReference<>();
      final Thread thread =
          Thread.ofPlatform()
              .daemon()
              .name("blocked-reader")
              .start(() -> ended.set(RealCallRows.outcome(read)));
      // Long enough for the reader to block in the read.
      Thread.sleep(300L);
      return new Reader(thread, ended);
    }

    /** Prints {@code <row>.readerEnded} and {@code <row>.reader} (what the read ended with). */
    void report(final String row) throws InterruptedException {
      thread.join(READER_WAIT_MILLIS);
      System.out.println(row + ".readerEnded=" + !thread.isAlive());
      System.out.println(row + ".reader=" + ended.get());
    }
  }
}

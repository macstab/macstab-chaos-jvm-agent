package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
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
import java.util.Arrays;
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
      case "stall-ends" -> stallEnds();
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
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      final CountingSocket client = new CountingSocket();
      client.connect(server.getLocalSocketAddress());
      try (Socket peer = server.accept()) {
        System.out.println("subclass.close=" + closeRejected(client::close));
        System.out.println("subclass.closed=" + client.isClosed());
        System.out.println("subclass.closes=" + client.closes);
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
   * A real refused connect of a socket subclass that counts its {@code close()} calls, as the
   * baseline; a rejected connect of a socket, of that subclass, a socket channel, a channel's
   * adaptor, and a channel under a {@code NIO_CHANNEL_CONNECT} reject; and a {@code timeout()} on a
   * socket's connect. Prints, per row, the failure, whether the endpoint is still open, what a
   * second connect of the same endpoint (with no scenario active) does, and for the subclass rows
   * how often its {@code close()} ran.
   */
  private static void connectReject() throws Exception {
    final InetSocketAddress refused;
    try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      refused = (InetSocketAddress) unused.getLocalSocketAddress();
    }
    final CountingSocket real = new CountingSocket();
    System.out.println("real.type=" + RealCallRows.outcome(() -> real.connect(refused, 1_000)));
    System.out.println("real.open=" + !real.isClosed());
    System.out.println("real.closes=" + real.closes);
    System.out.println("real.retry=" + RealCallRows.outcome(() -> real.connect(refused)));
    real.close();

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

      final CountingSocket subclass = new CountingSocket();
      RealCallRows.print(
          "subclass",
          RealCallRows.during(
              "subclass",
              socketConnect,
              ChaosEffect.reject("chaos connect"),
              () -> subclass.connect(address, 1_000)));
      System.out.println("subclass.open=" + !subclass.isClosed());
      System.out.println("subclass.closes=" + subclass.closes);
      subclass.close();

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

  // ── a timeout() stall ended by a wakeup or a close ────────────────────────

  /**
   * A {@code timeout()} stall of a 3 s select, socket read, adaptor read and connect, each ended by
   * another thread 200 ms after the call entered the stall: a {@code wakeup()} or {@code close()}
   * of the selector, a close of the socket or channel; and a stalled select after a {@code
   * wakeup()} made before it, and on a closed selector. Prints {@code <row>.millis} (how long the
   * call took), {@code <row>.outcome} (what it returned or threw) and, for an ended stall, {@code
   * <row>.applied}.
   */
  private static void stallEnds() throws Exception {
    final ChaosSelector select = ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT));
    try (java.nio.channels.Selector selector = java.nio.channels.Selector.open()) {
      stall("selectWakeup", select, () -> selector.select(3_000L), selector::wakeup);
    }
    final java.nio.channels.Selector closing = java.nio.channels.Selector.open();
    stall("selectClose", select, () -> closing.select(3_000L), closing::close);
    // A wakeup with no select in progress: the next select returns at once.
    try (java.nio.channels.Selector selector = java.nio.channels.Selector.open();
        ChaosActivationHandle timeout =
            RealCallRows.activate("selectWakeupBefore", select, ChaosEffect.timeout())) {
      selector.wakeup();
      timed("selectWakeupBefore", () -> selector.select(3_000L));
    }
    // A select of a closed selector throws ClosedSelectorException.
    final java.nio.channels.Selector closed = java.nio.channels.Selector.open();
    closed.close();
    try (ChaosActivationHandle timeout =
        RealCallRows.activate("selectClosed", select, ChaosEffect.timeout())) {
      timed("selectClosed", () -> closed.select(3_000L));
    }

    final ChaosSelector read = ChaosSelector.network(Set.of(OperationType.SOCKET_READ));
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      final Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      try (Socket peer = server.accept()) {
        client.setSoTimeout(3_000);
        stall("read", read, () -> client.getInputStream().read(), client::close);
      }
    }
    try (ServerSocketChannel server = loopbackServer()) {
      final SocketChannel client = SocketChannel.open(server.getLocalAddress());
      try (SocketChannel peer = server.accept()) {
        client.socket().setSoTimeout(3_000);
        stall("adaptorRead", read, () -> client.socket().getInputStream().read(), client::close);
      }
    }
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      final Socket connecting = new Socket();
      stall(
          "connect",
          ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)),
          () -> connecting.connect(server.getLocalSocketAddress(), 3_000),
          connecting::close);
    }
  }

  private static void stall(
      final String row,
      final ChaosSelector selector,
      final RealCallRows.Call call,
      final RealCallRows.Call interruption)
      throws Exception {
    // Active (and its hooks woven) before the other thread starts: the wakeup or close comes
    // while the call waits.
    try (ChaosActivationHandle timeout =
        RealCallRows.activate(row, selector, ChaosEffect.timeout())) {
      final Thread caller = Thread.currentThread();
      final Thread ender =
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    try {
                      // The 200 ms count from the moment the call is in the stall.
                      awaitFrame(
                          caller,
                          frame ->
                              STALL_FRAMES.contains(frame.getMethodName())
                                  && frame.getClassName().startsWith("com.macstab.chaos.jvm."));
                      Thread.sleep(200L);
                      interruption.run();
                    } catch (final Exception failed) {
                      System.out.println(row + ".ender=" + failed);
                    }
                  });
      timed(row, call);
      System.out.println(row + ".applied=" + timeout.appliedCount());
      ender.join();
    }
  }

  /**
   * The agent's stall loops: {@code BootstrapDispatcher.stallSelect} for a select, {@code
   * NetworkDispatch.stalled} for a read or a connect.
   */
  private static final Set<String> STALL_FRAMES = Set.of("stallSelect", "stalled");

  /**
   * Waits, at most 10 s, until one of {@code thread}'s frames matches {@code frame}.
   *
   * @throws IllegalStateException when none did in time
   */
  private static void awaitFrame(
      final Thread thread, final java.util.function.Predicate<StackTraceElement> frame)
      throws InterruptedException {
    final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10L);
    while (System.nanoTime() < deadline) {
      for (final StackTraceElement element : thread.getStackTrace()) {
        if (frame.test(element)) {
          return;
        }
      }
      Thread.sleep(5L);
    }
    throw new IllegalStateException(
        thread.getName() + " did not reach the frame: " + Arrays.toString(thread.getStackTrace()));
  }

  /** Runs {@code call}; prints {@code <row>.millis} and {@code <row>.outcome}. */
  private static void timed(final String row, final RealCallRows.Call call) {
    final long start = System.nanoTime();
    final String outcome = RealCallRows.outcome(call);
    System.out.println(row + ".millis=" + (System.nanoTime() - start) / 1_000_000L);
    System.out.println(row + ".outcome=" + outcome);
  }

  /** A socket whose {@code close()} counts how often it runs, as a pooling subclass would. */
  private static final class CountingSocket extends Socket {
    private int closes;

    @Override
    public void close() throws IOException {
      closes++;
      super.close();
    }
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
      // Closed only once the reader blocks in the JDK's native read (or poll), as a real reader
      // does: a close before it enters the read would fail it as closed, not as closed meanwhile.
      awaitFrame(
          thread,
          frame ->
              frame.isNativeMethod()
                  && frame.getClassName().startsWith("sun.nio.ch.")
                  && (frame.getMethodName().startsWith("read")
                      || frame.getMethodName().startsWith("poll")));
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

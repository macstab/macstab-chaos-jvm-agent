package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetSocketAddress;
import org.jspecify.annotations.Nullable;

/**
 * Dispatch of socket, socket channel, datagram, selector and TLS operations, with each call's peer
 * and byte count.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class NetworkDispatch {

  private final DispatchEngine engine;

  NetworkDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Called before {@link java.nio.channels.Selector#select()}; returns {@code true} to force a
   * spurious wakeup.
   *
   * @param selector the NIO selector being invoked
   * @param timeoutMillis the requested select timeout in milliseconds
   * @return {@code true} to force a spurious wakeup; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeNioSelect(final Object selector, final long timeoutMillis) throws Throwable {
    if (engine.idle(OperationType.NIO_SELECTOR_SELECT)) {
      return false;
    }
    final RuntimeDecision decision =
        engine.evaluate(
            new InvocationContext(
                OperationType.NIO_SELECTOR_SELECT,
                selector == null ? "java.nio.channels.Selector" : selector.getClass().getName(),
                null,
                null,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                InvocationContext.UNKNOWN,
                null,
                null,
                InvocationContext.UNKNOWN,
                null,
                // select() is infinite (0), selectNow() has none (-1): a timeout cannot fire there.
                (int) Math.min(timeoutMillis, Integer.MAX_VALUE)));
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.failure();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
      if (terminalAction.kind() == TerminalKind.TIMEOUT) {
        // A stalled peer: the select waits out its own timeout and reports no ready channel.
        engine.sleep(terminalAction.number());
        return true;
      }
    }
    return false;
  }

  /**
   * Called before a connection is half-closed.
   *
   * @param endpoint the socket implementation or socket channel
   * @param remoteAddress the peer address of a socket implementation, or {@code null} to take the
   *     peer of a channel
   * @param remotePort the peer port that goes with {@code remoteAddress}
   * @throws Throwable if an active scenario fails the shutdown
   */
  public void beforeSocketShutdown(
      final Object endpoint, final Object remoteAddress, final int remotePort) throws Throwable {
    if (engine.idle(OperationType.SOCKET_SHUTDOWN)) {
      return;
    }
    engine.applyPreDecision(
        engine.evaluate(
            socketContext(
                OperationType.SOCKET_SHUTDOWN,
                endpoint,
                InvocationContext.UNKNOWN,
                remoteAddress instanceof java.net.InetAddress inet
                    ? new InetSocketAddress(inet, remotePort)
                    : peerOf(endpoint),
                InvocationContext.UNKNOWN)));
  }

  /**
   * Called before a NIO channel read/write/connect/accept to apply scenarios targeting that
   * operation.
   *
   * @param operation the {@link OperationType} name for the channel operation
   * @param channel the NIO channel being operated on
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeNioChannelOp(final String operation, final Object channel) throws Throwable {
    final OperationType indexedOp = OperationType.valueOf(operation);
    engine.applyPreDecision(
        (engine.idle(indexedOp)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    indexedOp,
                    channel == null ? "java.nio.channels.Channel" : channel.getClass().getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@link java.net.Socket#connect(java.net.SocketAddress, int)} to apply socket
   * scenarios.
   *
   * @param socket the socket being connected
   * @param socketAddress the remote socket address being targeted
   * @param timeoutMillis the connect timeout in milliseconds
   * @return the endpoint to connect to instead under a {@code RedirectEffect}; {@code null} to
   *     connect as asked
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public @Nullable InetSocketAddress beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    if (engine.idle(OperationType.SOCKET_CONNECT)) {
      return null;
    }
    final String remoteHost = extractRemoteHost(socketAddress);
    final InetSocketAddress inetSocketAddress =
        socketAddress instanceof InetSocketAddress address ? address : null;
    final RuntimeDecision decision =
        engine.evaluate(
            new InvocationContext(
                OperationType.SOCKET_CONNECT,
                socket == null ? "java.net.Socket" : socket.getClass().getName(),
                null,
                remoteHost,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                InvocationContext.UNKNOWN,
                null,
                inetSocketAddress == null ? null : inetSocketAddress.getAddress(),
                inetSocketAddress == null ? InvocationContext.UNKNOWN : inetSocketAddress.getPort(),
                null,
                timeoutMillis));
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.REWRITE
        && decision.terminalAction().returnValue() instanceof ChaosEffect.RedirectEffect redirect) {
      engine.applyDelayAndGate(decision);
      // Resolved here, where the agent's own lookup is not evaluated by the DNS hooks.
      return new InetSocketAddress(
          redirect.host(),
          redirect.port() != null
              ? redirect.port()
              : inetSocketAddress == null ? 0 : inetSocketAddress.getPort());
    }
    engine.applyPreDecision(decision);
    return null;
  }

  /**
   * Called before {@link java.net.ServerSocket#accept()} to apply server-accept scenarios.
   *
   * @param serverSocket the server socket performing the accept
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeSocketAccept(final Object serverSocket) throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.SOCKET_ACCEPT)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.SOCKET_ACCEPT,
                    serverSocket == null
                        ? "java.net.ServerSocket"
                        : serverSocket.getClass().getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before a socket read to apply scenarios targeting socket reads.
   *
   * @param stream the socket implementation being read from
   * @param len the number of bytes requested
   * @param remoteAddress the peer {@link java.net.InetAddress}, or {@code null} if not connected
   * @param remotePort the peer port, or {@code 0} if not connected
   * @param soTimeoutMillis the socket's {@code SO_TIMEOUT}; {@code 0} for infinite
   * @return the byte count to read: {@code len}, or less under a {@code TrickleEffect}; encoded as
   *     {@code -(count) - 1} when the bytes read must be corrupted (see {@code
   *     DispatchEngine.corruptFlag})
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public int beforeSocketRead(
      final Object stream,
      final int len,
      final @Nullable Object remoteAddress,
      final int remotePort,
      final int soTimeoutMillis)
      throws Throwable {
    if (engine.idle(OperationType.SOCKET_READ)) {
      return len;
    }
    return engine.readCount(
        (engine.evaluate(
            new InvocationContext(
                OperationType.SOCKET_READ,
                stream == null ? "java.net.SocketInputStream" : stream.getClass().getName(),
                null,
                remoteHost(remoteAddress),
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                len,
                null,
                remoteAddress instanceof java.net.InetAddress address ? address : null,
                remoteAddress == null ? InvocationContext.UNKNOWN : remotePort,
                null,
                soTimeoutMillis))),
        len);
  }

  /**
   * Called before a socket write to apply scenarios targeting socket writes.
   *
   * @param stream the socket implementation being written to
   * @param len the number of bytes being written
   * @param remoteAddress the peer {@link java.net.InetAddress}, or {@code null} if not connected
   * @param remotePort the peer port, or {@code 0} if not connected
   * @return the bytes to write: {@code len}, or a shorter prefix under a torn write, after which
   *     the advice fails the write as a reset connection
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public int beforeSocketWrite(
      final Object stream,
      final int len,
      final @Nullable Object remoteAddress,
      final int remotePort)
      throws Throwable {
    if (engine.idle(OperationType.SOCKET_WRITE)) {
      return len;
    }
    return engine.readCount(
        (engine.evaluate(
            new InvocationContext(
                OperationType.SOCKET_WRITE,
                stream == null ? "java.net.SocketOutputStream" : stream.getClass().getName(),
                null,
                remoteHost(remoteAddress),
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                len,
                null,
                remoteAddress instanceof java.net.InetAddress address ? address : null,
                remoteAddress == null ? InvocationContext.UNKNOWN : remotePort,
                null,
                InvocationContext.UNKNOWN))),
        len);
  }

  /**
   * Called before {@link java.nio.channels.SocketChannel#read(java.nio.ByteBuffer)} (and its
   * scattering variant): the same {@code SOCKET_READ} event as a socket stream read, with the
   * channel's peer and the bytes the buffers can take. A channel has no {@code SO_TIMEOUT}, so a
   * {@code TimeoutEffect} never fires on it.
   *
   * @param channel the channel being read from
   * @param remaining the bytes the buffers can take
   * @return the byte count to read, as {@link #beforeSocketRead}
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public int beforeSocketChannelRead(final Object channel, final int remaining) throws Throwable {
    if (engine.idle(OperationType.SOCKET_READ)) {
      return remaining;
    }
    return engine.readCount(
        engine.evaluate(
            socketContext(OperationType.SOCKET_READ, channel, remaining, peerOf(channel), 0)),
        remaining);
  }

  /**
   * Called before {@link java.nio.channels.SocketChannel#write(java.nio.ByteBuffer)} (and its
   * gathering variant): the same {@code SOCKET_WRITE} event as a socket stream write, with the
   * channel's peer and the bytes the buffers hold. A torn write is a short count, which channel
   * callers loop on.
   *
   * @param channel the channel being written to
   * @param remaining the bytes the buffers hold
   * @return the bytes to write: {@code remaining}, or fewer under a torn write
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public int beforeSocketChannelWrite(final Object channel, final int remaining) throws Throwable {
    if (engine.idle(OperationType.SOCKET_WRITE)) {
      return remaining;
    }
    return engine.readCount(
        engine.evaluate(
            socketContext(
                OperationType.SOCKET_WRITE,
                channel,
                remaining,
                peerOf(channel),
                InvocationContext.UNKNOWN)),
        remaining);
  }

  /**
   * Called before a socket is bound ({@code sun.nio.ch.Net.bind}): the {@code SOCKET_BIND} event,
   * whose address and port are the local ones being bound.
   *
   * @param address the local address
   * @param port the local port; {@code 0} for an ephemeral one
   * @throws Throwable if an active scenario fails the bind
   */
  public void beforeSocketBind(final Object address, final int port) throws Throwable {
    if (engine.idle(OperationType.SOCKET_BIND)) {
      return;
    }
    engine.applyPreDecision(
        engine.evaluate(
            socketContext(
                OperationType.SOCKET_BIND,
                null,
                InvocationContext.UNKNOWN,
                address instanceof java.net.InetAddress inet
                    ? new InetSocketAddress(inet, port)
                    : null,
                InvocationContext.UNKNOWN)));
  }

  /**
   * Called before a datagram is sent or received.
   *
   * @param operation {@code "DATAGRAM_SEND"} or {@code "DATAGRAM_RECEIVE"}
   * @param channel the datagram channel
   * @param peer the destination of a send ({@code null}: the channel's connected peer); ignored for
   *     a receive, whose peer is the connected one if any
   * @param length the datagram's length (send) or the space for it (receive)
   * @return {@code length}; {@link ChaosDispatcher#DATAGRAM_DROPPED} when a send is suppressed; the
   *     {@code DispatchEngine.corruptFlag} encoding when the datagram received must be corrupted
   * @throws Throwable if an active scenario fails the call
   */
  public int beforeDatagram(
      final String operation, final Object channel, final Object peer, final int length)
      throws Throwable {
    final OperationType operationType =
        "DATAGRAM_SEND".equals(operation)
            ? OperationType.DATAGRAM_SEND
            : OperationType.DATAGRAM_RECEIVE;
    if (engine.idle(operationType)) {
      return length;
    }
    final java.net.SocketAddress target =
        operationType == OperationType.DATAGRAM_SEND && peer instanceof java.net.SocketAddress a
            ? a
            : peerOf(channel);
    final RuntimeDecision decision =
        engine.evaluate(
            socketContext(operationType, channel, length, target, InvocationContext.UNKNOWN));
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
      engine.applyDelayAndGate(decision);
      return ChaosDispatcher.DATAGRAM_DROPPED;
    }
    return engine.readCount(decision, length);
  }

  /**
   * The context of a socket event on {@code endpoint} with peer {@code peer}: an IP peer matches by
   * address, port and host name, a Unix-domain peer by its path as the host.
   */
  private InvocationContext socketContext(
      final OperationType operation,
      final @Nullable Object endpoint,
      final int byteCount,
      final java.net.@Nullable SocketAddress peer,
      final int timeoutMillis) {
    final InetSocketAddress inet = peer instanceof InetSocketAddress address ? address : null;
    return new InvocationContext(
        operation,
        endpoint == null ? "sun.nio.ch.Net" : endpoint.getClass().getName(),
        null,
        inet != null ? remoteHost(inet.getAddress()) : peer == null ? null : peer.toString(),
        false,
        null,
        null,
        engine.scopeContext.currentSessionId(),
        byteCount,
        null,
        inet == null ? null : inet.getAddress(),
        inet == null ? InvocationContext.UNKNOWN : inet.getPort(),
        null,
        timeoutMillis);
  }

  /**
   * The peer of a connected socket or socket channel, or {@code null} when it has none (not
   * connected, closed, or another kind of endpoint).
   */
  static java.net.@Nullable SocketAddress peerOf(final Object endpoint) {
    if (endpoint instanceof java.net.Socket socket) {
      return socket.getInetAddress() == null
          ? null
          : new InetSocketAddress(socket.getInetAddress(), socket.getPort());
    }
    try {
      if (endpoint instanceof java.nio.channels.SocketChannel channel) {
        return channel.getRemoteAddress();
      }
      if (endpoint instanceof java.nio.channels.DatagramChannel channel) {
        return channel.getRemoteAddress();
      }
    } catch (final java.io.IOException closed) {
      return null;
    }
    return null;
  }

  /**
   * Called before {@link java.nio.channels.SocketChannel#write(java.nio.ByteBuffer)}.
   *
   * @param channel the channel being written to
   * @param remaining the bytes the buffer holds
   * @return the bytes to write: {@code remaining}, or fewer under a torn write
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public int beforeNioChannelWrite(final Object channel, final int remaining) throws Throwable {
    if (engine.idle(OperationType.NIO_CHANNEL_WRITE)) {
      return remaining;
    }
    return engine.readCount(
        engine.evaluate(
            new InvocationContext(
                OperationType.NIO_CHANNEL_WRITE,
                channel == null ? "java.nio.channels.SocketChannel" : channel.getClass().getName(),
                null,
                null,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                remaining,
                null,
                null,
                InvocationContext.UNKNOWN,
                null,
                InvocationContext.UNKNOWN)),
        remaining);
  }

  /**
   * Called before {@link java.net.Socket#close()} to apply socket-close scenarios.
   *
   * @param socket the socket being closed
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeSocketClose(final Object socket) throws Throwable {
    if (engine.idle(OperationType.SOCKET_CLOSE)) {
      return;
    }
    final RuntimeDecision decision =
        engine.evaluate(
            socketContext(
                OperationType.SOCKET_CLOSE,
                socket,
                InvocationContext.UNKNOWN,
                peerOf(socket),
                InvocationContext.UNKNOWN));
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.ABORTIVE_CLOSE) {
      engine.applyDelayAndGate(decision);
      lingerZero(socket);
      return;
    }
    engine.applyPreDecision(decision);
  }

  /**
   * Sets linger 0 on an open socket or socket channel, so its close discards unsent data and sends
   * RST instead of FIN.
   */
  private static void lingerZero(final Object endpoint) {
    try {
      if (endpoint instanceof java.net.Socket socket && !socket.isClosed()) {
        socket.setSoLinger(true, 0);
      } else if (endpoint instanceof java.nio.channels.NetworkChannel channel
          && channel.isOpen()
          && channel.supportedOptions().contains(java.net.StandardSocketOptions.SO_LINGER)) {
        channel.setOption(java.net.StandardSocketOptions.SO_LINGER, 0);
      }
    } catch (final java.io.IOException alreadyBroken) {
      // the connection is already gone; close normally
    }
  }

  /**
   * The host a {@code NetworkSelector} pattern is matched against for an established connection:
   * the host name the address was created or resolved with when known, otherwise its IP literal.
   * Read from {@link java.net.InetAddress#toString()} ({@code "host/ip"}), which never triggers a
   * reverse lookup — unlike {@link java.net.InetAddress#getHostName()}.
   */
  static @Nullable String remoteHost(final @Nullable Object remoteAddress) {
    if (!(remoteAddress instanceof java.net.InetAddress address)) {
      return null;
    }
    final String text = address.toString();
    final int separator = text.indexOf('/');
    if (separator > 0) {
      return text.substring(0, separator);
    }
    return address.getHostAddress();
  }

  /**
   * Called before {@link javax.net.ssl.SSLSocket#startHandshake()} or {@link
   * javax.net.ssl.SSLEngine#beginHandshake()}.
   *
   * @param socket the {@code SSLSocket} or {@code SSLEngine} instance
   * @throws Throwable if an active scenario throws to simulate a TLS handshake failure
   */
  public void beforeSslHandshake(final @Nullable Object socket) throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.SSL_HANDSHAKE)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.SSL_HANDSHAKE,
                    socket == null ? "javax.net.ssl.SSLSocket" : socket.getClass().getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  private static @Nullable String extractRemoteHost(final Object socketAddress) {
    if (socketAddress instanceof InetSocketAddress inetSocketAddress) {
      final String host = inetSocketAddress.getHostString();
      return host != null ? host : inetSocketAddress.toString();
    }
    return socketAddress == null ? null : socketAddress.toString();
  }
}

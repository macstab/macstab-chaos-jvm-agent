package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.ChannelBuffers;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.jspecify.annotations.Nullable;

/**
 * ByteBuddy {@link Advice} classes for TCP sockets and socket channels ({@code SOCKET_*}) and TLS
 * handshakes ({@code SSL_HANDSHAKE}).
 *
 * <p>Each nested class targets one method or overload family. The outer class is not instantiated;
 * it exists only as a namespace.
 */
final class SocketAdvice {
  private SocketAdvice() {}

  /** Intercepts {@code NioSocketImpl.shutdownInput} / {@code shutdownOutput} (SOCKET_SHUTDOWN). */
  static final class SocketShutdownAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object impl,
        @Advice.FieldValue("address") final java.net.InetAddress remoteAddress,
        @Advice.FieldValue("port") final int remotePort)
        throws Throwable {
      BootstrapDispatcher.beforeSocketShutdown(impl, remoteAddress, remotePort);
    }
  }

  /** Intercepts {@code SocketChannel.shutdownInput} / {@code shutdownOutput} (SOCKET_SHUTDOWN). */
  static final class SocketChannelShutdownAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeSocketShutdown(channel, null, 0);
    }
  }

  // ── Socket channels as sockets (SOCKET_*) ────────────────────────────────

  /** Intercepts {@code SocketChannel.connect(SocketAddress)} (SOCKET_CONNECT, no timeout). */
  static final class SocketChannelConnectAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object channel,
        @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object socketAddress)
        throws Throwable {
      socketAddress = BootstrapDispatcher.beforeSocketConnect(channel, socketAddress, 0);
    }
  }

  /** Intercepts {@code ServerSocketChannel.accept()} (SOCKET_ACCEPT). */
  static final class SocketChannelAcceptAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeSocketAccept(channel);
    }
  }

  /**
   * Intercepts {@code SocketChannel.read(ByteBuffer)} (SOCKET_READ): a trickle limits the buffer, a
   * corruption flips one bit of the bytes read.
   */
  static final class SocketChannelReadAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
        @Advice.This final Object channel, @Advice.Argument(0) final java.nio.ByteBuffer buffer)
        throws Throwable {
      return BootstrapDispatcher.beforeSocketChannelRead(channel, buffer);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final ChannelBuffers shaping, @Advice.Return final int read) {
      if (shaping != null) {
        shaping.finish(read);
      }
    }
  }

  /** Intercepts {@code SocketChannel.read(ByteBuffer[], int, int)} (SOCKET_READ, scattering). */
  static final class SocketChannelScatterReadAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer[] buffers,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length)
        throws Throwable {
      return BootstrapDispatcher.beforeSocketChannelRead(channel, buffers, offset, length);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final ChannelBuffers shaping, @Advice.Return final long read) {
      if (shaping != null) {
        shaping.finish(read);
      }
    }
  }

  /** Intercepts {@code SocketChannel.write(ByteBuffer)} (SOCKET_WRITE): torn is a short count. */
  static final class SocketChannelWriteAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
        @Advice.This final Object channel, @Advice.Argument(0) final java.nio.ByteBuffer buffer)
        throws Throwable {
      return BootstrapDispatcher.beforeSocketChannelWrite(channel, buffer);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final ChannelBuffers shaping, @Advice.Return final int written) {
      if (shaping != null) {
        shaping.finish(written);
      }
    }
  }

  /** Intercepts {@code SocketChannel.write(ByteBuffer[], int, int)} (SOCKET_WRITE, gathering). */
  static final class SocketChannelGatherWriteAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer[] buffers,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length)
        throws Throwable {
      return BootstrapDispatcher.beforeSocketChannelWrite(channel, buffers, offset, length);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final ChannelBuffers shaping, @Advice.Return final long written) {
      if (shaping != null) {
        shaping.finish(written);
      }
    }
  }

  /**
   * Intercepts {@code AbstractInterruptibleChannel.close()} of a socket channel (SOCKET_CLOSE),
   * while it is still open, so a {@code connectionReset()} can set linger 0 before it closes.
   */
  static final class SocketChannelCloseAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      if (channel instanceof java.nio.channels.SocketChannel
          && ((java.nio.channels.Channel) channel).isOpen()) {
        BootstrapDispatcher.beforeSocketClose(channel);
      }
    }
  }

  // ── B2: Socket / Network ──────────────────────────────────────────────────

  /** Intercepts {@code Socket.connect(SocketAddress, int)}. */
  static final class SocketConnectAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object socket,
        @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC)
            Object socketAddress,
        @Advice.Argument(1) final int timeoutMillis)
        throws Throwable {
      // A RedirectEffect returns another endpoint to connect to.
      socketAddress = BootstrapDispatcher.beforeSocketConnect(socket, socketAddress, timeoutMillis);
    }
  }

  /** Intercepts {@code ServerSocket.accept()}. */
  static final class SocketAcceptAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object serverSocket) throws Throwable {
      BootstrapDispatcher.beforeSocketAccept(serverSocket);
    }
  }

  /**
   * Intercepts {@code NioSocketImpl.read(byte[], int, int)}. The remote address is read from the
   * inherited {@code SocketImpl.address} field — the advice is inlined into the socket
   * implementation, so no reflection is involved — and reported, so {@code NetworkSelector} host
   * patterns match reads.
   */
  static final class SocketReadAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream,
        @Advice.Argument(value = 2, readOnly = false) int len,
        @Advice.FieldValue("address") final java.net.InetAddress remoteAddress,
        @Advice.FieldValue("port") final int remotePort,
        @Advice.FieldValue("timeout") final int soTimeoutMillis)
        throws Throwable {
      final int count =
          BootstrapDispatcher.beforeSocketRead(
              stream, len, remoteAddress, remotePort, soTimeoutMillis);
      if (count < 0) {
        len = -count - 1;
        return true;
      }
      len = count;
      return false;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Argument(1) final int offset,
        @Advice.Return final int read) {
      if (corrupt) {
        BootstrapDispatcher.corruptBytes(buffer, offset, read);
      }
    }
  }

  /**
   * Intercepts {@code NioSocketImpl.write(byte[], int, int)} with the byte count and the remote
   * address (see {@link SocketReadAdvice}).
   */
  static final class SocketWriteBulkAdvice {
    /** Returns the requested length when the write is torn, else {@code -1}. */
    @Advice.OnMethodEnter
    static int enter(
        @Advice.This final Object stream,
        @Advice.Argument(value = 2, readOnly = false) int len,
        @Advice.FieldValue("address") final java.net.InetAddress remoteAddress,
        @Advice.FieldValue("port") final int remotePort)
        throws Throwable {
      final int count =
          BootstrapDispatcher.beforeSocketWrite(stream, len, remoteAddress, remotePort);
      if (count >= 0 && count < len) {
        final int requested = len;
        len = count;
        return requested;
      }
      return -1;
    }

    /**
     * A torn write sent its prefix; the connection is now reset for real (linger 0, then close, so
     * the peer reads a reset rather than a clean end of stream) and the write fails as reset.
     *
     * <p>Inlined into {@code NioSocketImpl}: the reflective calls of its own protected {@code
     * setOption} and {@code close} pass the access check because the caller is that class.
     */
    @Advice.OnMethodExit
    static void exit(
        @Advice.This final Object impl,
        @Advice.Enter final int requested,
        @Advice.Argument(2) final int len)
        throws Throwable {
      if (requested >= 0) {
        // The agent's own reflection and close: no scenario applies to them.
        BootstrapDispatcher.beginInternalCall();
        try {
          impl.getClass()
              .getDeclaredMethod("setOption", java.net.SocketOption.class, Object.class)
              .invoke(impl, java.net.StandardSocketOptions.SO_LINGER, 0);
          impl.getClass().getDeclaredMethod("close").invoke(impl);
        } catch (final Throwable ignored) {
          // The failure below is still thrown; only the peer's view degrades to a plain close.
        } finally {
          BootstrapDispatcher.endInternalCall();
        }
        throw BootstrapDispatcher.tornWriteFailure(len, requested);
      }
    }
  }

  /** Intercepts {@code Socket.close()}. */
  static final class SocketCloseAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object socket) throws Throwable {
      BootstrapDispatcher.beforeSocketClose(socket);
    }
  }

  // ── B16: SSL/TLS handshake ────────────────────────────────────────────────

  /**
   * Intercepts {@link javax.net.ssl.SSLSocket#startHandshake()} and {@link
   * javax.net.ssl.SSLEngine#beginHandshake()}.
   *
   * <p>An active scenario can inject latency to simulate slow TLS negotiation or throw {@link
   * javax.net.ssl.SSLHandshakeException} to simulate certificate validation failures.
   */
  static final class SslHandshakeAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object socket) throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_SSL_HANDSHAKE)) {
        try {
          BootstrapDispatcher.beforeSslHandshake(socket);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_SSL_HANDSHAKE);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_SSL_HANDSHAKE);
    }
  }

  /**
   * Intercepts {@code sun.nio.ch.Net.bind(ProtocolFamily, FileDescriptor, InetAddress, int)}
   * (SOCKET_BIND). Static: no {@code @Advice.This}.
   */
  static final class SocketBindAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(2) final Object address, @Advice.Argument(3) final int port)
        throws Throwable {
      BootstrapDispatcher.beforeSocketBind(address, port);
    }
  }
}

package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.ChannelBuffers;
import net.bytebuddy.asm.Advice;
import org.jspecify.annotations.Nullable;

/**
 * ByteBuddy {@link Advice} classes for UDP datagram sockets and channels ({@code DATAGRAM_SEND},
 * {@code DATAGRAM_RECEIVE}).
 *
 * <p>Each nested class targets one method or overload family. The outer class is not instantiated;
 * it exists only as a namespace.
 */
final class DatagramAdvice {
  private DatagramAdvice() {}

  // ── UDP (DATAGRAM_*) ─────────────────────────────────────────────────────

  /**
   * Intercepts {@code DatagramChannel.send(ByteBuffer, SocketAddress)}: a dropped datagram is not
   * sent, and the call reports its length as sent.
   */
  static final class DatagramSendAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static int enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer source,
        @Advice.Argument(1) final Object target)
        throws Throwable {
      return BootstrapDispatcher.beforeDatagramSend(channel, source, target, false);
    }

    @Advice.OnMethodExit
    static void exit(@Advice.Enter final int dropped, @Advice.Return(readOnly = false) int sent) {
      if (dropped != 0) {
        sent = dropped - 1;
      }
    }
  }

  /** Intercepts {@code DatagramChannel.write(ByteBuffer)} of a connected channel, as a send. */
  static final class DatagramWriteAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static int enter(
        @Advice.This final Object channel, @Advice.Argument(0) final java.nio.ByteBuffer source)
        throws Throwable {
      return BootstrapDispatcher.beforeDatagramSend(channel, source, null, true);
    }

    @Advice.OnMethodExit
    static void exit(@Advice.Enter final int dropped, @Advice.Return(readOnly = false) int sent) {
      if (dropped != 0) {
        sent = dropped - 1;
      }
    }
  }

  /**
   * Intercepts {@code DatagramChannelImpl.blockingSend(DatagramPacket)}: {@code DatagramSocket}.
   * One send, although the JDK carries it out through the channel's own {@code send} ({@link
   * BootstrapDispatcher#SCOPE_DATAGRAM_SEND}).
   */
  static final class DatagramPacketSendAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object channel, @Advice.Argument(0) final java.net.DatagramPacket packet)
        throws Throwable {
      final boolean drop = BootstrapDispatcher.dropDatagramPacket(channel, packet);
      // Entered after the dispatch: when it throws, no exit advice runs to leave the scope.
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_DATAGRAM_SEND);
      return drop;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DATAGRAM_SEND);
    }
  }

  /** Intercepts {@code DatagramChannel.receive(ByteBuffer)}: a corruption flips one bit. */
  static final class DatagramReceiveAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
        @Advice.This final Object channel, @Advice.Argument(0) final java.nio.ByteBuffer target)
        throws Throwable {
      return BootstrapDispatcher.beforeDatagramReceive(channel, target);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final ChannelBuffers shaping, @Advice.Return final Object sender) {
      if (shaping != null) {
        shaping.finish(sender == null ? 0L : ChannelBuffers.RECEIVED);
      }
    }
  }

  /** Intercepts {@code DatagramChannel.read(ByteBuffer)} of a connected channel, as a receive. */
  static final class DatagramReadAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
        @Advice.This final Object channel, @Advice.Argument(0) final java.nio.ByteBuffer target)
        throws Throwable {
      return BootstrapDispatcher.beforeDatagramReceive(channel, target);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final ChannelBuffers shaping, @Advice.Return final int read) {
      if (shaping != null) {
        shaping.finish(read);
      }
    }
  }

  /**
   * Intercepts {@code DatagramChannelImpl.blockingReceive(DatagramPacket, long)}: {@code
   * DatagramSocket}. A corruption flips one bit of the packet's data.
   */
  static final class DatagramPacketReceiveAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object channel, @Advice.Argument(0) final java.net.DatagramPacket packet)
        throws Throwable {
      return BootstrapDispatcher.corruptDatagramPacket(channel, packet);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt,
        @Advice.Argument(0) final java.net.DatagramPacket packet) {
      if (corrupt) {
        BootstrapDispatcher.corruptBytes(packet.getData(), packet.getOffset(), packet.getLength());
      }
    }
  }
}

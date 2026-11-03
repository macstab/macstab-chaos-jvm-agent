package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.ChannelBuffers;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} classes for JVM-level interception points wired in Phase 2.
 *
 * <p>Each inner class targets one specific method or overload family. The outer class is not
 * instantiated; it exists only as a namespace.
 */
final class JvmRuntimeAdvice {
  private JvmRuntimeAdvice() {}

  // ── A1: Clock ─────────────────────────────────────────────────────────────

  /** Rewrites the return value of {@code System.currentTimeMillis()} for clock-skew injection. */
  static final class ClockMillisAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) long returned) throws Throwable {
      returned = BootstrapDispatcher.adjustClockMillis(returned);
    }
  }

  /** Rewrites the return value of {@code System.nanoTime()} for clock-skew injection. */
  static final class ClockNanosAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) long returned) throws Throwable {
      returned = BootstrapDispatcher.adjustClockNanos(returned);
    }
  }

  /**
   * Rewrites the return value of {@link java.time.Instant#now()} for clock-skew injection.
   *
   * <p>{@code Instant.now()} is a regular Java static method that delegates to {@code
   * Clock.systemUTC().instant()}, so unlike {@link System#currentTimeMillis()} it is neither {@code
   * native} nor {@code @IntrinsicCandidate} and can be instrumented at exit.
   */
  static final class InstantNowAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) java.time.Instant returned) throws Throwable {
      returned = BootstrapDispatcher.adjustInstantNow(returned);
    }
  }

  /**
   * Rewrites the return value of {@link java.time.LocalDateTime#now()} (no-argument variant) for
   * clock-skew injection.
   */
  static final class LocalDateTimeNowAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) java.time.LocalDateTime returned)
        throws Throwable {
      returned = BootstrapDispatcher.adjustLocalDateTimeNow(returned);
    }
  }

  /**
   * Rewrites the return value of {@link java.time.LocalDateTime#now(java.time.ZoneId)} for
   * clock-skew injection, mapping through the zone it was called with.
   */
  static final class LocalDateTimeNowInZoneAdvice {
    @Advice.OnMethodExit
    static void exit(
        @Advice.Argument(0) final java.time.ZoneId zone,
        @Advice.Return(readOnly = false) java.time.LocalDateTime returned)
        throws Throwable {
      returned = BootstrapDispatcher.adjustLocalDateTimeNowInZone(returned, zone);
    }
  }

  /**
   * Rewrites the return value of {@link java.time.ZonedDateTime#now()} and {@link
   * java.time.ZonedDateTime#now(java.time.ZoneId)} for clock-skew injection.
   */
  static final class ZonedDateTimeNowAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) java.time.ZonedDateTime returned)
        throws Throwable {
      returned = BootstrapDispatcher.adjustZonedDateTimeNow(returned);
    }
  }

  /**
   * Adjusts the embedded epoch-milliseconds of a freshly-constructed {@link java.util.Date} for
   * clock-skew injection.
   *
   * <p>{@link java.util.Date#Date()} calls {@link System#currentTimeMillis()} and stores it
   * internally. Direct interception of {@code currentTimeMillis()} is impossible (see {@code
   * JdkInstrumentationInstaller} for the JVM-level analysis), so we instead wrap the constructor
   * and overwrite the embedded value via {@link java.util.Date#setTime(long)} on exit.
   *
   * <p>The {@code self} parameter is typed as {@link Object} rather than {@link java.util.Date}:
   * {@code java.util.Date} is eagerly referenced during early JVM bootstrap by {@code
   * java.util.logging.SimpleFormatter}, and inlining an advice that names {@code Date} in its
   * signature can cause a {@link ClassCircularityError} if any logging happens while the
   * retransformed constructor body is still being verified by the JVM.
   */
  static final class DateNewAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.This final Object self) throws Throwable {
      final java.util.Date date = (java.util.Date) self;
      final long realMillis = date.getTime();
      final long adjusted = BootstrapDispatcher.adjustDateNew(realMillis);
      if (adjusted != realMillis) {
        date.setTime(adjusted);
      }
    }
  }

  // ── A2: GC ────────────────────────────────────────────────────────────────

  /**
   * Intercepts {@code System.gc()} and {@code Runtime.gc()}. Skips the GC call when a
   * SUPPRESS-terminating scenario is active.
   *
   * <p>When {@code enter()} returns {@code true}, ByteBuddy's {@code skipOn =
   * Advice.OnNonDefaultValue.class} mechanism causes the real GC call body to be skipped entirely.
   * Because {@code gc()} is {@code void}, no exit advice is required and no return value needs to
   * be overwritten.
   */
  static final class GcRequestAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter() throws Throwable {
      return BootstrapDispatcher.beforeGcRequest();
    }
  }

  // ── A3: Exit ──────────────────────────────────────────────────────────────

  /**
   * Intercepts {@code Runtime.exit(int)} and {@code Runtime.halt(int)}. {@code System.exit(int)}
   * delegates to {@code Runtime.exit(int)}, so both entry points are covered exactly once. Throws
   * {@code SecurityException} when a SUPPRESS scenario is active.
   */
  static final class ExitRequestAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final int status) throws Throwable {
      BootstrapDispatcher.beforeExitRequest(status);
    }
  }

  // ── A4: Reflection ────────────────────────────────────────────────────────

  /** Intercepts {@code Method.invoke(Object, Object[])} for reflection chaos. */
  static final class ReflectionInvokeAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object method, @Advice.Argument(0) final Object target)
        throws Throwable {
      BootstrapDispatcher.beforeReflectionInvoke(method, target);
    }
  }

  // ── A5: Direct buffer ─────────────────────────────────────────────────────

  /** Intercepts {@code ByteBuffer.allocateDirect(int)}. */
  static final class DirectBufferAllocateAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final int capacity) throws Throwable {
      BootstrapDispatcher.beforeDirectBufferAllocate(capacity);
    }
  }

  // ── A6: Deserialization ───────────────────────────────────────────────────

  /** Intercepts {@code ObjectInputStream.readObject()}. */
  static final class ObjectDeserializeAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream) throws Throwable {
      BootstrapDispatcher.beforeObjectDeserialize(stream);
    }
  }

  // ── A7: Class define ──────────────────────────────────────────────────────

  /**
   * Intercepts {@code ClassLoader.defineClass(String, byte[], int, int)} and {@code
   * ClassLoader.defineClass(String, ByteBuffer, ProtectionDomain)}.
   */
  static final class ClassDefineAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object loader, @Advice.Argument(0) final String className)
        throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLASS_DEFINE)) {
        try {
          BootstrapDispatcher.beforeClassDefine(loader, className);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLASS_DEFINE);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLASS_DEFINE);
    }
  }

  // ── A8: Monitor / Park ────────────────────────────────────────────────────

  /**
   * Intercepts {@code AbstractQueuedSynchronizer.acquire(int)} and related methods as a proxy for
   * {@code MONITOR_ENTER}.
   */
  static final class MonitorEnterAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This(optional = true) final Object lock) throws Throwable {
      // Pass the actual AQS (or @This-null for static-context edge cases) through so scenario
      // matchers that filter by MonitorSelector.monitorClass see the real runtime type instead
      // of the constant AQS string the dispatcher previously substituted.
      BootstrapDispatcher.beforeMonitorEnter(lock);
    }
  }

  /**
   * Intercepts {@code LockSupport.park(Object)}, {@code parkNanos}, and {@code parkUntil} as a
   * proxy for {@code THREAD_PARK}.
   */
  static final class ThreadParkAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_THREAD_PARK)) {
        try {
          BootstrapDispatcher.beforeThreadPark();
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_THREAD_PARK);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_THREAD_PARK);
    }
  }

  // ── B1: NIO ───────────────────────────────────────────────────────────────

  /**
   * Intercepts {@code Selector.select()} and {@code Selector.selectNow()} (no-argument variants).
   *
   * <p><b>Skip semantics:</b> {@code enter()} is annotated with {@code skipOn =
   * Advice.OnNonDefaultValue.class}. When it returns {@code true} (spurious-wakeup scenario
   * active), ByteBuddy skips the real {@code select()} body entirely. {@code exit()} is always
   * invoked; the {@code spurious} parameter receives the boolean returned by {@code enter()}, and
   * when it is {@code true} the writable {@code returned} field is overwritten with {@code 0},
   * making the method appear to have returned zero ready-channel keys — a valid spurious wakeup
   * from the caller's perspective.
   */
  static final class NioSelectNoArgAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.This final Object selector) throws Throwable {
      return BootstrapDispatcher.beforeNioSelect(selector, 0L);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean spurious, @Advice.Return(readOnly = false) int returned) {
      if (spurious) {
        returned = 0;
      }
    }
  }

  /**
   * Intercepts {@code Selector.selectNow()} — the non-blocking poll variant.
   *
   * <p>Identical to {@link NioSelectNoArgAdvice} in every respect except the timeout sentinel: the
   * dispatcher call passes {@code -1L} rather than {@code 0L} so scenario matchers can distinguish
   * the blocking {@code select()} from the non-blocking {@code selectNow()} without heuristics.
   * Sharing {@code NioSelectNoArgAdvice} between the two previously collapsed both calls to {@code
   * timeoutMillis=0L}, making a {@code selectNow()}-only scenario fire on the blocking {@code
   * select()} too (and vice versa).
   */
  static final class NioSelectNowAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.This final Object selector) throws Throwable {
      return BootstrapDispatcher.beforeNioSelect(selector, -1L);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean spurious, @Advice.Return(readOnly = false) int returned) {
      if (spurious) {
        returned = 0;
      }
    }
  }

  /**
   * Intercepts {@code Selector.select(long timeout)}.
   *
   * <p><b>Skip semantics:</b> identical to {@link NioSelectNoArgAdvice}. When {@code enter()}
   * returns {@code true}, the real {@code select(long)} body is skipped. The {@code spurious}
   * parameter in {@code exit()} receives the return value of {@code enter()}, and when it is {@code
   * true} the writable {@code returned} field is set to {@code 0} to simulate a spurious timeout
   * wakeup with no ready channels.
   */
  static final class NioSelectTimeoutAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object selector, @Advice.Argument(0) final long timeoutMillis)
        throws Throwable {
      return BootstrapDispatcher.beforeNioSelect(selector, timeoutMillis);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean spurious, @Advice.Return(readOnly = false) int returned) {
      if (spurious) {
        returned = 0;
      }
    }
  }

  /**
   * Intercepts {@code Selector.select(Consumer, long)}. Identical to {@link NioSelectTimeoutAdvice}
   * except that the timeout is the second argument.
   */
  static final class NioSelectConsumerTimeoutAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object selector, @Advice.Argument(1) final long timeoutMillis)
        throws Throwable {
      return BootstrapDispatcher.beforeNioSelect(selector, timeoutMillis);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean spurious, @Advice.Return(readOnly = false) int returned) {
      if (spurious) {
        returned = 0;
      }
    }
  }

  /** Intercepts {@code ReadableByteChannel.read(ByteBuffer)} (NIO_CHANNEL_READ). */
  static final class NioChannelReadAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeNioChannelOp("NIO_CHANNEL_READ", channel);
    }
  }

  /** Intercepts {@code WritableByteChannel.write(ByteBuffer)} (NIO_CHANNEL_WRITE). */
  static final class NioChannelWriteAdvice {
    @Advice.OnMethodEnter
    static int enter(
        @Advice.This final Object channel, @Advice.Argument(0) final java.nio.ByteBuffer buffer)
        throws Throwable {
      final int requested = buffer == null ? -1 : buffer.remaining();
      final int count = BootstrapDispatcher.beforeNioChannelWrite(channel, requested);
      if (buffer != null && count >= 0 && count < requested) {
        final int originalLimit = buffer.limit();
        buffer.limit(buffer.position() + count);
        return originalLimit;
      }
      return -1;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final int originalLimit,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer) {
      if (originalLimit >= 0) {
        buffer.limit(originalLimit);
      }
    }
  }

  /**
   * Intercepts {@code ScatteringByteChannel.read(ByteBuffer[], int, int)} of a socket channel
   * (NIO_CHANNEL_READ).
   */
  static final class NioChannelScatterReadAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeNioChannelOp("NIO_CHANNEL_READ", channel);
    }
  }

  /**
   * Intercepts {@code GatheringByteChannel.write(ByteBuffer[], int, int)} of a socket channel
   * (NIO_CHANNEL_WRITE): a torn write limits the buffers, so the channel writes fewer bytes.
   */
  static final class NioChannelGatherWriteAdvice {
    @Advice.OnMethodEnter
    static ChannelBuffers enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer[] buffers,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length)
        throws Throwable {
      return BootstrapDispatcher.beforeNioChannelWrite(channel, buffers, offset, length);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final ChannelBuffers shaping, @Advice.Return final long written) {
      if (shaping != null) {
        shaping.finish(written);
      }
    }
  }

  // ── Socket channels as sockets (SOCKET_*) ────────────────────────────────

  /** Intercepts {@code SocketChannel.connect(SocketAddress)} (SOCKET_CONNECT, no timeout). */
  static final class SocketChannelConnectAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object channel, @Advice.Argument(0) final Object socketAddress)
        throws Throwable {
      BootstrapDispatcher.beforeSocketConnect(channel, socketAddress, 0);
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
    static ChannelBuffers enter(
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
    static ChannelBuffers enter(
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
    static ChannelBuffers enter(
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
    static ChannelBuffers enter(
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

  /** Intercepts {@code SocketChannel.connect(SocketAddress)} (NIO_CHANNEL_CONNECT). */
  static final class NioChannelConnectAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeNioChannelOp("NIO_CHANNEL_CONNECT", channel);
    }
  }

  /** Intercepts {@code ServerSocketChannel.accept()} (NIO_CHANNEL_ACCEPT). */
  static final class NioChannelAcceptAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeNioChannelOp("NIO_CHANNEL_ACCEPT", channel);
    }
  }

  // ── B2: Socket / Network ──────────────────────────────────────────────────

  /** Intercepts {@code Socket.connect(SocketAddress, int)}. */
  static final class SocketConnectAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object socket,
        @Advice.Argument(0) final Object socketAddress,
        @Advice.Argument(1) final int timeoutMillis)
        throws Throwable {
      BootstrapDispatcher.beforeSocketConnect(socket, socketAddress, timeoutMillis);
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
   * implementation, so no reflection is involved — and lets {@code NetworkSelector} host patterns
   * match reads, which previously never matched because no host was reported.
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
        try {
          impl.getClass()
              .getDeclaredMethod("setOption", java.net.SocketOption.class, Object.class)
              .invoke(impl, java.net.StandardSocketOptions.SO_LINGER, 0);
          impl.getClass().getDeclaredMethod("close").invoke(impl);
        } catch (final Throwable ignored) {
          // The failure below is still thrown; only the peer's view degrades to a plain close.
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

  // ── B5: JNDI ──────────────────────────────────────────────────────────────

  /** Intercepts {@code InitialContext.lookup(String)}. */
  static final class JndiLookupAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object context, @Advice.Argument(0) final String name)
        throws Throwable {
      BootstrapDispatcher.beforeJndiLookup(context, name);
    }
  }

  // ── B6: Serialization ─────────────────────────────────────────────────────

  /** Intercepts {@code ObjectOutputStream.writeObject(Object)}. */
  static final class ObjectSerializeAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream, @Advice.Argument(0) final Object obj)
        throws Throwable {
      BootstrapDispatcher.beforeObjectSerialize(stream, obj);
    }
  }

  // ── B7: Native library ────────────────────────────────────────────────────

  /**
   * Intercepts {@code System.loadLibrary(String)} and {@code System.load(String)}.
   *
   * <p>Instrumented as a static method — no {@code @Advice.This}.
   */
  static final class NativeLibraryLoadAdvice {
    /**
     * {@code Runtime.loadLibrary0(Class caller, String libname)} — argument 0 is the calling {@code
     * Class}, argument 1 is the library name string.
     */
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(1) final String libraryName) throws Throwable {
      BootstrapDispatcher.beforeNativeLibraryLoad(libraryName);
    }
  }

  static final class NativeLibrariesLoadAdvice {
    /**
     * {@code jdk.internal.loader.NativeLibraries.load(NativeLibraryImpl impl, String name, boolean
     * isBuiltin, boolean isJNI)} — argument 0 is the {@code NativeLibraryImpl} instance, argument 1
     * is the library name string. Kept as a separate class from {@link NativeLibraryLoadAdvice} so
     * that if the JDK signature changes the wrong index is caught at instrumentation time rather
     * than silently reading a non-name argument.
     */
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(1) final String libraryName) throws Throwable {
      BootstrapDispatcher.beforeNativeLibraryLoad(libraryName);
    }
  }

  // ── B8: CompletableFuture cancel ──────────────────────────────────────────

  /**
   * Intercepts {@code CompletableFuture.cancel(boolean)}.
   *
   * <p><b>Skip semantics:</b> when {@code enter()} returns {@code true} (SUPPRESS scenario active),
   * ByteBuddy's {@code skipOn = Advice.OnNonDefaultValue.class} causes the real {@code cancel()}
   * body to be skipped — the future is left in its current state. {@code exit()} always runs; when
   * {@code suppressed} is {@code true} it overwrites the writable {@code returned} field with
   * {@code true}, making the caller believe the cancellation succeeded even though it was silently
   * dropped.
   */
  static final class AsyncCancelAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object future, @Advice.Argument(0) final boolean mayInterruptIfRunning)
        throws Throwable {
      return BootstrapDispatcher.beforeAsyncCancel(future, mayInterruptIfRunning);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean suppressed, @Advice.Return(readOnly = false) boolean returned) {
      if (suppressed) {
        returned = true;
      }
    }
  }

  // ── B9: ZIP ───────────────────────────────────────────────────────────────

  /** Intercepts {@code Inflater.inflate(...)} variants for compression chaos. */
  static final class ZipInflateAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_ZIP_INFLATE)) {
        try {
          BootstrapDispatcher.beforeZipInflate();
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_ZIP_INFLATE);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_ZIP_INFLATE);
    }
  }

  /** Intercepts {@code Deflater.deflate(...)} variants for compression chaos. */
  static final class ZipDeflateAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_ZIP_DEFLATE)) {
        try {
          BootstrapDispatcher.beforeZipDeflate();
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_ZIP_DEFLATE);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_ZIP_DEFLATE);
    }
  }

  // ── B12: ThreadLocal ──────────────────────────────────────────────────────

  /**
   * Intercepts {@code ThreadLocal.get()} to allow null-injection or delay on ThreadLocal reads.
   *
   * <p><b>Reentrancy guard — identity check:</b> {@code BootstrapDispatcher.DEPTH} is itself a
   * {@code ThreadLocal<int[]>}. Without special handling, instrumenting {@code ThreadLocal.get()}
   * would cause infinite recursion:
   *
   * <pre>
   *   ThreadLocal.get() [any call]
   *     → ThreadLocalGetAdvice.enter()
   *       → BootstrapDispatcher.beforeThreadLocalGet()
   *         → DEPTH.get()   ← ThreadLocal.get() again!
   *           → ThreadLocalGetAdvice.enter()
   *             → ... StackOverflowError
   * </pre>
   *
   * <p>The fix: before delegating to the dispatcher, compare the intercepted {@code ThreadLocal}
   * instance to {@code BootstrapDispatcher.DEPTH} by identity ({@code ==}). If they are the same
   * object, return {@code false} immediately — no delegation, no recursion. This is safe because:
   *
   * <ul>
   *   <li>{@code BootstrapDispatcher.DEPTH} is a {@code static final} singleton in the bootstrap
   *       classloader. Identity comparison is stable and correct for the JVM lifetime.
   *   <li>Bootstrap-classloader classes are visible from all classloaders, so the field reference
   *       is always accessible from this advice.
   *   <li>The check is a single reference comparison — zero allocation, nanosecond cost.
   *   <li>All other internal {@code ThreadLocal} reads (e.g. {@code ScopeContext.sessionStack}) are
   *       already protected by the {@code DEPTH > 0} guard inside the dispatch methods of {@code
   *       BootstrapDispatcher}, because they are only read during chaos evaluation which happens
   *       after DEPTH has been incremented to 1.
   * </ul>
   */
  static final class ThreadLocalGetAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.This final Object threadLocal) throws Throwable {
      // Guard: skip interception of the dispatcher's own reentrancy-depth ThreadLocal.
      // Without this check, DEPTH.get() inside the BootstrapDispatcher guard would re-trigger
      // this advice, causing a StackOverflowError before the DEPTH guard can protect us.
      // Identity comparison is safe: DEPTH is a static-final bootstrap-CL singleton.
      if (threadLocal == BootstrapDispatcher.depthThreadLocal()) {
        return false;
      }
      return BootstrapDispatcher.beforeThreadLocalGet(threadLocal);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean suppressed, @Advice.Return(readOnly = false) Object returned) {
      if (suppressed) {
        returned = null;
      }
    }
  }

  /**
   * Intercepts {@code ThreadLocal.set(Object)}.
   *
   * <p><b>Skip-without-exit pattern:</b> this advice has only an enter method annotated with {@code
   * skipOn = Advice.OnNonDefaultValue.class} and no exit method. When {@code enter()} returns
   * {@code true}, the real {@code ThreadLocal.set()} body is silently skipped — the value is never
   * stored — and execution continues after the method call site as if it had returned normally. No
   * exit advice is needed because {@code set()} is {@code void} and there is no return value to
   * overwrite.
   */
  static final class ThreadLocalSetAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object threadLocal, @Advice.Argument(0) final Object value)
        throws Throwable {
      // Same reentrancy guard as ThreadLocalGetAdvice: skip the dispatcher's own DEPTH ThreadLocal.
      // set() is not called by DEPTH (which only uses get/set via initialValue), but the guard
      // is symmetric for correctness and defence-in-depth.
      if (threadLocal == BootstrapDispatcher.depthThreadLocal()) {
        return false;
      }
      return BootstrapDispatcher.beforeThreadLocalSet(threadLocal, value);
    }
  }

  // ── B13: JMX ──────────────────────────────────────────────────────────────

  /**
   * Intercepts {@code MBeanServer.invoke(ObjectName, String, Object[], String[])} for JMX chaos.
   */
  static final class JmxInvokeAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object server,
        @Advice.Argument(0) final Object objectName,
        @Advice.Argument(1) final String operationName)
        throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JMX)) {
        try {
          BootstrapDispatcher.beforeJmxInvoke(server, objectName, operationName);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JMX);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JMX);
    }
  }

  /**
   * Intercepts {@code MBeanServer.getAttribute(ObjectName, String)} for JMX attribute read chaos.
   */
  static final class JmxGetAttrAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object server,
        @Advice.Argument(0) final Object objectName,
        @Advice.Argument(1) final String attribute)
        throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_JMX)) {
        try {
          BootstrapDispatcher.beforeJmxGetAttr(server, objectName, attribute);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JMX);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_JMX);
    }
  }

  // ── B14: Thread.sleep ─────────────────────────────────────────────────────

  /**
   * Intercepts {@link Thread#sleep(long)} so that an active scenario can suppress the sleep
   * (returning immediately) or inject an exception.
   *
   * <p>{@code skipOn = Advice.OnNonDefaultValue.class}: when {@code enter()} returns {@code true}
   * the real {@code sleep()} body is skipped — the calling thread returns immediately as if the
   * sleep completed. This exposes race conditions hidden by artificial pauses and tests retry loops
   * that back off via {@code Thread.sleep}.
   */
  static final class ThreadSleepAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.Argument(0) final long millis) throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_THREAD_SLEEP)) {
        return false;
      }
      try {
        return BootstrapDispatcher.beforeThreadSleep(millis);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_THREAD_SLEEP);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_THREAD_SLEEP);
    }
  }

  /**
   * Intercepts {@link Thread#sleep(java.time.Duration)}, which does not delegate to {@code
   * sleep(long)} on JDK 21+ and was previously not instrumented. Same skip semantics as {@link
   * ThreadSleepAdvice}; the duration is passed to the dispatcher in milliseconds, saturating for
   * durations beyond {@code Long.MAX_VALUE} ms.
   */
  static final class ThreadSleepDurationAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.Argument(0) final java.time.Duration duration) throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_THREAD_SLEEP)) {
        return false;
      }
      try {
        long millis;
        try {
          millis = duration == null ? 0L : duration.toMillis();
        } catch (final ArithmeticException overflow) {
          millis = duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return BootstrapDispatcher.beforeThreadSleep(millis);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_THREAD_SLEEP);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_THREAD_SLEEP);
    }
  }

  // ── B15: DNS resolution ───────────────────────────────────────────────────

  /**
   * Intercepts {@link java.net.InetAddress#getByName(String)} and {@link
   * java.net.InetAddress#getAllByName(String)}. {@code getByName} delegates to {@code
   * getAllByName}; the {@code SCOPE_DNS} call-depth scope evaluates a lookup once.
   *
   * <p>These are static methods, so no {@code @Advice.This} is present. The hostname argument is
   * forwarded to the dispatcher, which can inject latency or throw {@link
   * java.net.UnknownHostException}.
   */
  static final class DnsResolveAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(value = 0, readOnly = false) String hostname)
        throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_DNS)) {
        try {
          hostname = BootstrapDispatcher.beforeDnsResolve(hostname);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DNS);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DNS);
    }
  }

  /**
   * Intercepts {@link java.net.InetAddress#getLocalHost()}, which has no arguments.
   *
   * <p>Passes {@code null} as the hostname to signal the local-host lookup case to the dispatcher.
   */
  static final class DnsLocalHostAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_DNS)) {
        try {
          BootstrapDispatcher.beforeDnsResolve(null);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DNS);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DNS);
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

  // ── B17: File I/O ─────────────────────────────────────────────────────────

  /**
   * Intercepts the single-byte {@link java.io.FileInputStream#read()} (one byte requested). The
   * stream's {@code path} field is the path it was opened with, or {@code null} for a stream built
   * from a {@link java.io.FileDescriptor}.
   *
   * <p>Passes {@code "FILE_IO_READ"} as the operation tag so the dispatcher can route to the
   * correct {@link com.macstab.chaos.jvm.api.OperationType#FILE_IO_READ} scenario.
   */
  static final class FileReadByteAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream, @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileIo("FILE_IO_READ", stream, path, 1) < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt, @Advice.Return(readOnly = false) int value) {
      if (corrupt) {
        value = BootstrapDispatcher.corruptByte(value);
      }
    }
  }

  /** Intercepts {@link java.io.FileInputStream#read(byte[])} ({@code b.length} requested). */
  static final class FileReadArrayAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileIo(
              "FILE_IO_READ", stream, path, buffer == null ? -1 : buffer.length)
          < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Return final int read) {
      if (corrupt) {
        BootstrapDispatcher.corruptBytes(buffer, 0, read);
      }
    }
  }

  /** Intercepts {@link java.io.FileInputStream#read(byte[], int, int)} ({@code len} requested). */
  static final class FileReadRangeAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream,
        @Advice.Argument(2) final int len,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileIo("FILE_IO_READ", stream, path, len) < 0;
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
   * Intercepts the single-byte {@link java.io.FileOutputStream#write(int)}.
   *
   * <p>Passes {@code "FILE_IO_WRITE"} as the operation tag so the dispatcher can route to the
   * correct {@link com.macstab.chaos.jvm.api.OperationType#FILE_IO_WRITE} scenario.
   */
  static final class FileWriteByteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object stream, @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo("FILE_IO_WRITE", stream, path, 1);
    }
  }

  /** Intercepts {@link java.io.FileOutputStream#write(byte[])} ({@code b.length} bytes). */
  static final class FileWriteArrayAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object stream,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          "FILE_IO_WRITE", stream, path, buffer == null ? -1 : buffer.length);
    }
  }

  /** Intercepts {@link java.io.FileOutputStream#write(byte[], int, int)} ({@code len} bytes). */
  static final class FileWriteRangeAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object stream,
        @Advice.Argument(2) final int len,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo("FILE_IO_WRITE", stream, path, len);
    }
  }

  /**
   * Intercepts {@code FileChannelImpl.read(ByteBuffer[, long])}: one channel read, reporting the
   * bytes requested ({@code remaining()}) and the channel's path, and corrupting the bytes read
   * under a {@code CorruptReadEffect}.
   */
  static final class FileChannelReadAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileIo(
              "FILE_IO_READ", channel, path, buffer == null ? -1 : buffer.remaining())
          < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer,
        @Advice.Return final int read) {
      if (corrupt) {
        BootstrapDispatcher.corruptBuffer(buffer, read);
      }
    }
  }

  /**
   * Intercepts {@code FileChannelImpl.write(ByteBuffer[, long])}. Under a {@code TornWriteEffect}
   * the buffer's limit is lowered for the call, so the channel writes fewer bytes and returns that
   * count, and restored afterwards.
   */
  static final class FileChannelWriteAdvice {
    @Advice.OnMethodEnter
    static int enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      final int requested = buffer == null ? -1 : buffer.remaining();
      final int count = BootstrapDispatcher.beforeFileIo("FILE_IO_WRITE", channel, path, requested);
      if (buffer != null && count >= 0 && count < requested) {
        final int originalLimit = buffer.limit();
        buffer.limit(buffer.position() + count);
        return originalLimit;
      }
      return -1;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final int originalLimit,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer) {
      if (originalLimit >= 0) {
        buffer.limit(originalLimit);
      }
    }
  }

  /** Intercepts {@code FileChannelImpl.read(ByteBuffer[], int, int)}, a scattering read. */
  static final class FileChannelScatterReadAdvice {
    @Advice.OnMethodEnter
    static ChannelBuffers enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer[] buffers,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileChannelIo(
          "FILE_IO_READ", channel, path, buffers, offset, length);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final ChannelBuffers shaping, @Advice.Return final long read) {
      if (shaping != null) {
        shaping.finish(read);
      }
    }
  }

  /** Intercepts {@code FileChannelImpl.write(ByteBuffer[], int, int)}, a gathering write. */
  static final class FileChannelGatherWriteAdvice {
    @Advice.OnMethodEnter
    static ChannelBuffers enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer[] buffers,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileChannelIo(
          "FILE_IO_WRITE", channel, path, buffers, offset, length);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final ChannelBuffers shaping, @Advice.Return final long written) {
      if (shaping != null) {
        shaping.finish(written);
      }
    }
  }

  /** Intercepts {@code FileChannelImpl.force(boolean)}: fsync of a channel. */
  static final class FileChannelForceAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object channel, @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo("FILE_IO_SYNC", channel, path, -1);
    }
  }

  /**
   * Intercepts {@code FileChannelImpl.truncate(long)} and {@code RandomAccessFile.setLength(long)}:
   * both hold the file's path in a {@code path} field.
   */
  static final class FileTruncateAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object file, @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo("FILE_IO_TRUNCATE", file, path, -1);
    }
  }

  /**
   * Intercepts {@code AbstractFileSystemProvider.delete(Path)} and {@code deleteIfExists(Path)},
   * which {@code Files.delete} and {@code Files.deleteIfExists} reach.
   */
  static final class FilesDeleteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object provider, @Advice.Argument(0) final java.nio.file.Path file)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          "FILE_IO_DELETE", provider, file == null ? null : file.toString(), -1);
    }
  }

  /**
   * Intercepts {@code AbstractFileSystemProvider.deleteIfExists(Path)}. An injected {@code ENOENT}
   * returns {@code false}, as the real method does for a missing file; other failures throw.
   */
  static final class FilesDeleteIfExistsAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object provider, @Advice.Argument(0) final java.nio.file.Path file)
        throws Throwable {
      try {
        BootstrapDispatcher.beforeFileIo(
            "FILE_IO_DELETE", provider, file == null ? null : file.toString(), -1);
        return false;
      } catch (final java.nio.file.NoSuchFileException missing) {
        return true;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean missing, @Advice.Return(readOnly = false) boolean deleted) {
      if (missing) {
        deleted = false;
      }
    }
  }

  /**
   * Intercepts the private {@code open(String, ...)} of {@code FileInputStream}, {@code
   * FileOutputStream} and {@code RandomAccessFile}: the file a constructor opens. Opens of archives
   * are skipped: they are class loading (see {@link BootstrapDispatcher#isArchive(String)}).
   */
  static final class FileOpenAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream, @Advice.Argument(0) final String name)
        throws Throwable {
      if (!BootstrapDispatcher.isArchive(name)) {
        BootstrapDispatcher.beforeFileIo("FILE_IO_OPEN", stream, name, -1);
      }
    }
  }

  /**
   * Intercepts a platform file system provider's {@code newByteChannel} / {@code newFileChannel}.
   */
  static final class FilesOpenAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object provider, @Advice.Argument(0) final java.nio.file.Path path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          "FILE_IO_OPEN", provider, path == null ? null : path.toString(), -1);
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

  /** Intercepts a file system provider's {@code move(Path, Path, CopyOption...)}. */
  static final class FilesMoveAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object provider, @Advice.Argument(0) final java.nio.file.Path source)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          "FILE_IO_MOVE", provider, source == null ? null : source.toString(), -1);
    }
  }

  /**
   * Intercepts {@link java.io.File#delete()}. {@code java.io} reports a failed delete by returning
   * {@code false}, so an injected I/O failure skips the delete and returns {@code false}.
   */
  static final class FileDeleteAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object file, @Advice.FieldValue("path") final String path)
        throws Throwable {
      try {
        BootstrapDispatcher.beforeFileIo("FILE_IO_DELETE", file, path, -1);
        return false;
      } catch (final java.io.IOException failed) {
        return true;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean failed, @Advice.Return(readOnly = false) boolean deleted) {
      if (failed) {
        deleted = false;
      }
    }
  }

  /**
   * Intercepts {@link java.io.File#renameTo(java.io.File)}; an injected I/O failure skips the
   * rename and returns {@code false}, as {@link FileDeleteAdvice} does for deletes.
   */
  static final class FileRenameAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object file, @Advice.FieldValue("path") final String path)
        throws Throwable {
      try {
        BootstrapDispatcher.beforeFileIo("FILE_IO_MOVE", file, path, -1);
        return false;
      } catch (final java.io.IOException failed) {
        return true;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean failed, @Advice.Return(readOnly = false) boolean renamed) {
      if (failed) {
        renamed = false;
      }
    }
  }

  /**
   * Intercepts {@code ProcessBuilder.start(Redirect[])}: every process start. The receiver is
   * declared as {@code Object}: an advice signature naming {@code ProcessBuilder} makes ByteBuddy
   * resolve that class while it is being loaded and transformed (ClassCircularityError).
   */
  static final class ProcessStartAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object builder) throws Throwable {
      final java.util.List<String> command = ((ProcessBuilder) builder).command();
      BootstrapDispatcher.beforeProcessStart(command.isEmpty() ? null : command.get(0));
    }
  }

  /** Intercepts {@link java.io.FileDescriptor#sync()}: fsync of a stream's descriptor. */
  static final class FileDescriptorSyncAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object descriptor) throws Throwable {
      BootstrapDispatcher.beforeFileIo("FILE_IO_SYNC", descriptor, null, -1);
    }
  }

  /**
   * Intercepts {@code RandomAccessFile.read()}. Reads of archives are skipped: see {@link
   * BootstrapDispatcher#isArchive(String)}.
   */
  static final class RandomAccessReadByteAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object file, @Advice.FieldValue("path") final String path)
        throws Throwable {
      return !BootstrapDispatcher.isArchive(path)
          && BootstrapDispatcher.beforeFileIo("FILE_IO_READ", file, path, 1) < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt, @Advice.Return(readOnly = false) int value) {
      if (corrupt) {
        value = BootstrapDispatcher.corruptByte(value);
      }
    }
  }

  /**
   * Intercepts {@code RandomAccessFile.readBytes(byte[], int, int)}, the funnel of every array
   * read. Reads of archives are skipped: see {@link BootstrapDispatcher#isArchive(String)}.
   */
  static final class RandomAccessReadRangeAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object file,
        @Advice.Argument(2) final int len,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return !BootstrapDispatcher.isArchive(path)
          && BootstrapDispatcher.beforeFileIo("FILE_IO_READ", file, path, len) < 0;
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
   * Reshapes the answer of {@link java.net.InetAddress#getAllByName(String)} under a {@code
   * DnsAnswerEffect}: on return, or on an {@link java.net.UnknownHostException} an override can
   * replace. {@code getByName} resolves through it, so both see the reshaped answer.
   */
  static final class DnsAnswerAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Argument(0) final String hostname,
        @Advice.Return(readOnly = false) java.net.InetAddress[] answer,
        @Advice.Thrown(readOnly = false) Throwable failure)
        throws Throwable {
      if (failure != null && !(failure instanceof java.net.UnknownHostException)) {
        return;
      }
      final java.net.InetAddress[] reshaped =
          BootstrapDispatcher.afterDnsAnswer(hostname, answer, failure);
      if (reshaped != null) {
        answer = reshaped;
        failure = null;
      }
    }
  }
}

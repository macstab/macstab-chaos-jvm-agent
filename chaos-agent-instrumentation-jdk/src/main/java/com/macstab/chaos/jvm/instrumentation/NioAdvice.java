package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.ChannelBuffers;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import net.bytebuddy.asm.Advice;
import org.jspecify.annotations.Nullable;

/**
 * ByteBuddy {@link Advice} classes for NIO selectors and selectable channels ({@code NIO_*}).
 *
 * <p>Each nested class targets one method or overload family. The outer class is not instantiated;
 * it exists only as a namespace.
 */
final class NioAdvice {
  private NioAdvice() {}

  // ── B1: NIO ───────────────────────────────────────────────────────────────

  /**
   * Intercepts the blocking {@code Selector.select()} variants; {@code selectNow()} has its own
   * advice below.
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

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final Object selector,
        @Advice.Enter final boolean spurious,
        @Advice.Return(readOnly = false) int returned) {
      BootstrapDispatcher.afterNioSelect(selector);
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
   * Sharing {@code NioSelectNoArgAdvice} between the two would collapse both calls to {@code
   * timeoutMillis=0L}, making a {@code selectNow()}-only scenario fire on the blocking {@code
   * select()} too (and vice versa).
   */
  static final class NioSelectNowAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.This final Object selector) throws Throwable {
      return BootstrapDispatcher.beforeNioSelect(selector, -1L);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final Object selector,
        @Advice.Enter final boolean spurious,
        @Advice.Return(readOnly = false) int returned) {
      BootstrapDispatcher.afterNioSelect(selector);
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

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final Object selector,
        @Advice.Enter final boolean spurious,
        @Advice.Return(readOnly = false) int returned) {
      BootstrapDispatcher.afterNioSelect(selector);
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

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final Object selector,
        @Advice.Enter final boolean spurious,
        @Advice.Return(readOnly = false) int returned) {
      BootstrapDispatcher.afterNioSelect(selector);
      if (spurious) {
        returned = 0;
      }
    }
  }

  /** Intercepts {@code ReadableByteChannel.read(ByteBuffer)} (NIO_CHANNEL_READ). */
  static final class NioChannelReadAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeNioChannelOp(OperationTags.NIO_CHANNEL_READ, channel);
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
      BootstrapDispatcher.beforeNioChannelOp(OperationTags.NIO_CHANNEL_READ, channel);
    }
  }

  /**
   * Intercepts {@code GatheringByteChannel.write(ByteBuffer[], int, int)} of a socket channel
   * (NIO_CHANNEL_WRITE): a torn write limits the buffers, so the channel writes fewer bytes.
   */
  static final class NioChannelGatherWriteAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
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

  /**
   * After {@code Selector.wakeup()} of a selector implementation (its close wakes it up too): ends
   * a {@code timeout()} stall of a select on it, as the wakeup ends a real select.
   */
  static final class SelectorWakeupAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.This final Object selector) {
      BootstrapDispatcher.selectorWoken(selector);
    }
  }

  /** Intercepts {@code SocketChannel.connect(SocketAddress)} (NIO_CHANNEL_CONNECT). */
  static final class NioChannelConnectAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeNioChannelOp(OperationTags.NIO_CHANNEL_CONNECT, channel);
    }
  }

  /** Intercepts {@code ServerSocketChannel.accept()} (NIO_CHANNEL_ACCEPT). */
  static final class NioChannelAcceptAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeNioChannelOp(OperationTags.NIO_CHANNEL_ACCEPT, channel);
    }
  }
}

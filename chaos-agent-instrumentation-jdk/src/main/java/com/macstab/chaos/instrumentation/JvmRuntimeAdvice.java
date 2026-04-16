package com.macstab.chaos.instrumentation;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
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
   * Intercepts {@code System.exit(int)} and {@code Runtime.halt(int)}. Throws {@code
   * SecurityException} when a SUPPRESS scenario is active.
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
      BootstrapDispatcher.beforeClassDefine(loader, className);
    }
  }

  // ── A8: Monitor / Park ────────────────────────────────────────────────────

  /**
   * Intercepts {@code AbstractQueuedSynchronizer.acquire(int)} and related methods as a proxy for
   * {@code MONITOR_ENTER}.
   */
  static final class MonitorEnterAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      BootstrapDispatcher.beforeMonitorEnter();
    }
  }

  /**
   * Intercepts {@code LockSupport.park(Object)}, {@code parkNanos}, and {@code parkUntil} as a
   * proxy for {@code THREAD_PARK}.
   */
  static final class ThreadParkAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      BootstrapDispatcher.beforeThreadPark();
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
    static void enter(@Advice.This final Object channel) throws Throwable {
      BootstrapDispatcher.beforeNioChannelOp("NIO_CHANNEL_WRITE", channel);
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

  /** Intercepts socket {@code InputStream.read(...)} variants. */
  static final class SocketReadAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream) throws Throwable {
      BootstrapDispatcher.beforeSocketRead(stream);
    }
  }

  /**
   * Intercepts socket {@code OutputStream.write(byte[], int, int)} and {@code
   * OutputStream.write(int)}.
   */
  static final class SocketWriteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream) throws Throwable {
      BootstrapDispatcher.beforeSocketWrite(stream, 0);
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
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final String libraryName) throws Throwable {
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
      BootstrapDispatcher.beforeZipInflate();
    }
  }

  /** Intercepts {@code Deflater.deflate(...)} variants for compression chaos. */
  static final class ZipDeflateAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      BootstrapDispatcher.beforeZipDeflate();
    }
  }

  // ── B12: ThreadLocal ──────────────────────────────────────────────────────

  /**
   * Intercepts {@code ThreadLocal.get()}.
   *
   * <p><b>Skip semantics:</b> when {@code enter()} returns {@code true} (SUPPRESS scenario active),
   * ByteBuddy's {@code skipOn = Advice.OnNonDefaultValue.class} skips the real {@code
   * ThreadLocal.get()} body so the thread-local storage is never consulted. {@code exit()} always
   * runs; when {@code suppressed} is {@code true} it sets the writable {@code returned} field to
   * {@code null}, making the caller observe a {@code null} value as if the thread-local had never
   * been set.
   */
  static final class ThreadLocalGetAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.This final Object threadLocal) throws Throwable {
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
      BootstrapDispatcher.beforeJmxInvoke(server, objectName, operationName);
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
      BootstrapDispatcher.beforeJmxGetAttr(server, objectName, attribute);
    }
  }
}

package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
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
   * Rewrites the return value of {@link java.time.ZonedDateTime#now()} (no-argument variant) for
   * clock-skew injection.
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
   * Intercepts socket {@code OutputStream.write(int)} — the single-byte variant. Passes {@code 1}
   * as the length so size-gated scenarios can distinguish per-byte writes from bulk writes.
   */
  static final class SocketWriteSingleByteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream) throws Throwable {
      BootstrapDispatcher.beforeSocketWrite(stream, 1);
    }
  }

  /**
   * Intercepts socket {@code OutputStream.write(byte[], int, int)} — the bulk variant. Passes the
   * actual {@code len} argument so {@code NetworkSelector} rules that filter on write byte count
   * receive the real size instead of a hardcoded {@code 0}.
   */
  static final class SocketWriteBulkAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream, @Advice.Argument(2) final int len)
        throws Throwable {
      BootstrapDispatcher.beforeSocketWrite(stream, len);
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
   * Intercepts {@code ThreadLocal.get()} to allow null-injection or delay on ThreadLocal reads.
   *
   * <p><b>Reentrancy guard — identity check:</b> {@code BootstrapDispatcher.DEPTH} is itself a
   * {@code ThreadLocal<int[]>}. Without special handling, instrumenting {@code ThreadLocal.get()}
   * would cause infinite recursion:
   *
   * <pre>
   *   ThreadLocal.get() [any call]
   *     → ThreadLocalGetAdvice.enter()
   *       → BootstrapDispatcher.invoke()
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
   *       already protected by the {@code DEPTH > 0} guard inside {@code
   *       BootstrapDispatcher.invoke()}, because they are only read during chaos evaluation which
   *       happens after DEPTH has been incremented to 1.
   * </ul>
   */
  static final class ThreadLocalGetAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.This final Object threadLocal) throws Throwable {
      // Guard: skip interception of the dispatcher's own reentrancy-depth ThreadLocal.
      // Without this check, DEPTH.get() inside BootstrapDispatcher.invoke() would re-trigger
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
      return BootstrapDispatcher.beforeThreadSleep(millis);
    }
  }

  // ── B15: DNS resolution ───────────────────────────────────────────────────

  /**
   * Intercepts {@link java.net.InetAddress#getByName(String)} and {@link
   * java.net.InetAddress#getAllByName(String)}.
   *
   * <p>These are static methods, so no {@code @Advice.This} is present. The hostname argument is
   * forwarded to the dispatcher, which can inject latency or throw {@link
   * java.net.UnknownHostException}.
   */
  static final class DnsResolveAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) final String hostname) throws Throwable {
      BootstrapDispatcher.beforeDnsResolve(hostname);
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
      BootstrapDispatcher.beforeDnsResolve(null);
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
      BootstrapDispatcher.beforeSslHandshake(socket);
    }
  }

  // ── B17: File I/O ─────────────────────────────────────────────────────────

  /**
   * Intercepts {@link java.io.FileInputStream#read(byte[], int, int)} and the single-byte {@link
   * java.io.FileInputStream#read()} before bytes are consumed from the underlying file descriptor.
   *
   * <p>Passes {@code "FILE_IO_READ"} as the operation tag so the dispatcher can route to the
   * correct {@link com.macstab.chaos.jvm.api.OperationType#FILE_IO_READ} scenario.
   */
  static final class FileReadAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream) throws Throwable {
      BootstrapDispatcher.beforeFileIo("FILE_IO_READ", stream);
    }
  }

  /**
   * Intercepts {@link java.io.FileOutputStream#write(byte[], int, int)} and the single-byte {@link
   * java.io.FileOutputStream#write(int)} before bytes are written to the underlying file
   * descriptor.
   *
   * <p>Passes {@code "FILE_IO_WRITE"} as the operation tag so the dispatcher can route to the
   * correct {@link com.macstab.chaos.jvm.api.OperationType#FILE_IO_WRITE} scenario.
   */
  static final class FileWriteAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream) throws Throwable {
      BootstrapDispatcher.beforeFileIo("FILE_IO_WRITE", stream);
    }
  }
}

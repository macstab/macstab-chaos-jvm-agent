package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} classes for JVM runtime services: GC and exit requests, reflection,
 * direct buffers, serialization, class definition, monitors and parking, JNDI, native libraries,
 * {@code CompletableFuture} cancellation, ZIP, {@code ThreadLocal}, JMX and {@code Thread.sleep}.
 * Clocks, NIO, sockets, datagrams, DNS, files and processes have their own advice classes.
 *
 * <p>Each nested class targets one method or overload family. The outer class is not instantiated;
 * it exists only as a namespace.
 */
final class JvmRuntimeAdvice {
  private JvmRuntimeAdvice() {}

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
      // of a constant AQS string.
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
   * <p>The guard: before delegating to the dispatcher, compare the intercepted {@code ThreadLocal}
   * instance to {@code BootstrapDispatcher.DEPTH} by identity ({@code ==}). If they are the same
   * object, return {@code false} immediately — no delegation, no recursion. This is safe because:
   *
   * <ul>
   *   <li>{@code BootstrapDispatcher.DEPTH} is a {@code static final} singleton in the bootstrap
   *       classloader. Identity comparison is stable and correct for the JVM lifetime.
   *   <li>Bootstrap-classloader classes are visible from all classloaders, so the field reference
   *       is always accessible from this advice.
   *   <li>The check is a single reference comparison — zero allocation, nanosecond cost.
   *   <li>The agent's other {@code ThreadLocal}s (e.g. {@code ScopeContext}'s session stack) are
   *       read outside a dispatch too — binding a session runs on the application's thread at depth
   *       0 — so they are not protected by the {@code DEPTH > 0} guard. They are marked {@code
   *       AgentOwned} in core, and the dispatcher skips them after its idle check.
   * </ul>
   */
  static final class ThreadLocalGetAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.This final Object threadLocal) throws Throwable {
      // Guard: skip interception of the dispatcher's own reentrancy-depth ThreadLocal.
      // Without this check, DEPTH.get() inside the BootstrapDispatcher guard would re-trigger
      // this advice, causing a StackOverflowError before the DEPTH guard can protect us.
      // Identity comparison is safe: DEPTH is a static-final bootstrap-CL singleton.
      if (BootstrapDispatcher.isDepthThreadLocal(threadLocal)) {
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
      if (BootstrapDispatcher.isDepthThreadLocal(threadLocal)) {
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
   * sleep(long)} on JDK 21+ and so needs its own advice. Same skip semantics as {@link
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
}

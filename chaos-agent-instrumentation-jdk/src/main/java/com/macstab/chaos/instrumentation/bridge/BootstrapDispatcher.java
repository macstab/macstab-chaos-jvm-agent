package com.macstab.chaos.instrumentation.bridge;

import java.lang.invoke.MethodHandle;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

public final class BootstrapDispatcher {
  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  private static volatile Object delegate;
  private static volatile MethodHandle[] handles;

  // ── Phase 1 handles (0-14) ────────────────────────────────────────────────
  public static final int DECORATE_EXECUTOR_RUNNABLE = 0;
  public static final int DECORATE_EXECUTOR_CALLABLE = 1;
  public static final int BEFORE_THREAD_START = 2;
  public static final int BEFORE_WORKER_RUN = 3;
  public static final int BEFORE_FORK_JOIN_TASK_RUN = 4;
  public static final int ADJUST_SCHEDULE_DELAY = 5;
  public static final int BEFORE_SCHEDULED_TICK = 6;
  public static final int BEFORE_QUEUE_OPERATION = 7;
  public static final int BEFORE_BOOLEAN_QUEUE_OPERATION = 8;
  public static final int BEFORE_COMPLETABLE_FUTURE_COMPLETE = 9;
  public static final int BEFORE_CLASS_LOAD = 10;
  public static final int AFTER_RESOURCE_LOOKUP = 11;
  public static final int DECORATE_SHUTDOWN_HOOK = 12;
  public static final int RESOLVE_SHUTDOWN_HOOK = 13;
  public static final int BEFORE_EXECUTOR_SHUTDOWN = 14;

  // ── Phase 2 handles (15-41) ───────────────────────────────────────────────
  public static final int ADJUST_CLOCK_MILLIS = 15;
  public static final int ADJUST_CLOCK_NANOS = 16;
  public static final int BEFORE_GC_REQUEST = 17;
  public static final int BEFORE_EXIT_REQUEST = 18;
  public static final int BEFORE_REFLECTION_INVOKE = 19;
  public static final int BEFORE_DIRECT_BUFFER_ALLOCATE = 20;
  public static final int BEFORE_OBJECT_DESERIALIZE = 21;
  public static final int BEFORE_CLASS_DEFINE = 22;
  public static final int BEFORE_MONITOR_ENTER = 23;
  public static final int BEFORE_THREAD_PARK = 24;
  public static final int BEFORE_NIO_SELECT = 25;
  public static final int BEFORE_NIO_CHANNEL_OP = 26;
  public static final int BEFORE_SOCKET_CONNECT = 27;
  public static final int BEFORE_SOCKET_ACCEPT = 28;
  public static final int BEFORE_SOCKET_READ = 29;
  public static final int BEFORE_SOCKET_WRITE = 30;
  public static final int BEFORE_SOCKET_CLOSE = 31;
  public static final int BEFORE_JNDI_LOOKUP = 32;
  public static final int BEFORE_OBJECT_SERIALIZE = 33;
  public static final int BEFORE_NATIVE_LIBRARY_LOAD = 34;
  public static final int BEFORE_ASYNC_CANCEL = 35;
  public static final int BEFORE_ZIP_INFLATE = 36;
  public static final int BEFORE_ZIP_DEFLATE = 37;
  public static final int BEFORE_THREAD_LOCAL_GET = 38;
  public static final int BEFORE_THREAD_LOCAL_SET = 39;
  public static final int BEFORE_JMX_INVOKE = 40;
  public static final int BEFORE_JMX_GET_ATTR = 41;

  public static final int HANDLE_COUNT = 42;

  private BootstrapDispatcher() {}

  public static void install(final Object bridgeDelegate, final MethodHandle[] methodHandles) {
    handles = methodHandles;
    delegate = bridgeDelegate;
  }

  // ── Phase 1 dispatch methods ───────────────────────────────────────────────

  public static Runnable decorateExecutorRunnable(
      final String operation, final Object executor, final Runnable task) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? task
              : (Runnable) h[DECORATE_EXECUTOR_RUNNABLE].invoke(d, operation, executor, task);
        },
        task);
  }

  public static <T> Callable<T> decorateExecutorCallable(
      final String operation, final Object executor, final Callable<T> task) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d == null || h == null) return task;
          @SuppressWarnings("unchecked")
          Callable<T> result =
              (Callable<T>) h[DECORATE_EXECUTOR_CALLABLE].invoke(d, operation, executor, task);
          return result;
        },
        task);
  }

  public static void beforeThreadStart(final Thread thread) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_THREAD_START].invoke(d, thread);
          }
          return null;
        },
        null);
  }

  public static void beforeWorkerRun(
      final Object executor, final Thread worker, final Runnable task) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_WORKER_RUN].invoke(d, executor, worker, task);
          }
          return null;
        },
        null);
  }

  public static void beforeForkJoinTaskRun(final ForkJoinTask<?> task) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_FORK_JOIN_TASK_RUN].invoke(d, task);
          }
          return null;
        },
        null);
  }

  public static long adjustScheduleDelay(
      final String operation,
      final Object executor,
      final Object task,
      final long delay,
      final boolean periodic)
      throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? delay
              : (long)
                  h[ADJUST_SCHEDULE_DELAY].invoke(d, operation, executor, task, delay, periodic);
        },
        delay);
  }

  public static boolean beforeScheduledTick(
      final Object executor, final Object task, final boolean periodic) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              || (boolean) h[BEFORE_SCHEDULED_TICK].invoke(d, executor, task, periodic);
        },
        true);
  }

  public static void beforeQueueOperation(final String operation, final Object queue)
      throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_QUEUE_OPERATION].invoke(d, operation, queue);
          }
          return null;
        },
        null);
  }

  public static Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? null
              : (Boolean) h[BEFORE_BOOLEAN_QUEUE_OPERATION].invoke(d, operation, queue);
        },
        null);
  }

  public static Boolean beforeCompletableFutureComplete(
      final String operation, final CompletableFuture<?> future, final Object payload)
      throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? null
              : (Boolean)
                  h[BEFORE_COMPLETABLE_FUTURE_COMPLETE].invoke(d, operation, future, payload);
        },
        null);
  }

  public static void beforeClassLoad(final ClassLoader loader, final String className)
      throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_CLASS_LOAD].invoke(d, loader, className);
          }
          return null;
        },
        null);
  }

  public static URL afterResourceLookup(
      final ClassLoader loader, final String name, final URL currentValue) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? currentValue
              : (URL) h[AFTER_RESOURCE_LOOKUP].invoke(d, loader, name, currentValue);
        },
        currentValue);
  }

  public static Thread decorateShutdownHook(final Thread hook) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? hook
              : (Thread) h[DECORATE_SHUTDOWN_HOOK].invoke(d, hook);
        },
        hook);
  }

  public static Thread resolveShutdownHook(final Thread original) {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? original
              : (Thread) h[RESOLVE_SHUTDOWN_HOOK].invoke(d, original);
        },
        original);
  }

  public static void beforeExecutorShutdown(
      final String operation, final Object executor, final long timeoutMillis) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_EXECUTOR_SHUTDOWN].invoke(d, operation, executor, timeoutMillis);
          }
          return null;
        },
        null);
  }

  // ── Phase 2 dispatch methods ───────────────────────────────────────────────

  /** Returns the (possibly skewed) millis clock value. */
  public static long adjustClockMillis(final long realMillis) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? realMillis
              : (long) h[ADJUST_CLOCK_MILLIS].invoke(d, realMillis);
        },
        realMillis);
  }

  /** Returns the (possibly skewed) nanos clock value. */
  public static long adjustClockNanos(final long realNanos) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? realNanos
              : (long) h[ADJUST_CLOCK_NANOS].invoke(d, realNanos);
        },
        realNanos);
  }

  /**
   * Called before {@code System.gc()} or {@code Runtime.gc()}. Returns {@code true} if GC should be
   * suppressed (advice skips the call), {@code false} to allow it.
   */
  public static boolean beforeGcRequest() throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null) && (boolean) h[BEFORE_GC_REQUEST].invoke(d);
        },
        false);
  }

  /** Called before {@code System.exit(status)} or {@code Runtime.halt(status)}. */
  public static void beforeExitRequest(final int status) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_EXIT_REQUEST].invoke(d, status);
          }
          return null;
        },
        null);
  }

  /** Called before {@code Method.invoke(Object, Object...)}. */
  public static void beforeReflectionInvoke(final Object method, final Object target)
      throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_REFLECTION_INVOKE].invoke(d, method, target);
          }
          return null;
        },
        null);
  }

  /** Called before {@code ByteBuffer.allocateDirect(capacity)}. */
  public static void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_DIRECT_BUFFER_ALLOCATE].invoke(d, capacity);
          }
          return null;
        },
        null);
  }

  /** Called before {@code ObjectInputStream.readObject()}. */
  public static void beforeObjectDeserialize(final Object stream) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_OBJECT_DESERIALIZE].invoke(d, stream);
          }
          return null;
        },
        null);
  }

  /** Called before {@code ClassLoader.defineClass(...)}. */
  public static void beforeClassDefine(final Object loader, final String className)
      throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_CLASS_DEFINE].invoke(d, loader, className);
          }
          return null;
        },
        null);
  }

  /** Called before AQS {@code acquire} — proxy for monitor contention. */
  public static void beforeMonitorEnter() throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_MONITOR_ENTER].invoke(d);
          }
          return null;
        },
        null);
  }

  /** Called before {@code LockSupport.park*}. */
  public static void beforeThreadPark() throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_THREAD_PARK].invoke(d);
          }
          return null;
        },
        null);
  }

  /**
   * Called before {@code Selector.select()}. Returns {@code true} if the select should be
   * intercepted as a spurious wakeup (advice returns 0), {@code false} for normal execution.
   */
  public static boolean beforeNioSelect(final Object selector, final long timeoutMillis)
      throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null)
              && (boolean) h[BEFORE_NIO_SELECT].invoke(d, selector, timeoutMillis);
        },
        false);
  }

  /** Called before NIO channel read/write/connect/accept. */
  public static void beforeNioChannelOp(final String operation, final Object channel)
      throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_NIO_CHANNEL_OP].invoke(d, operation, channel);
          }
          return null;
        },
        null);
  }

  /** Called before {@code Socket.connect(SocketAddress, int)}. */
  public static void beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_SOCKET_CONNECT].invoke(d, socket, socketAddress, timeoutMillis);
          }
          return null;
        },
        null);
  }

  /** Called before {@code ServerSocket.accept()}. */
  public static void beforeSocketAccept(final Object serverSocket) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_SOCKET_ACCEPT].invoke(d, serverSocket);
          }
          return null;
        },
        null);
  }

  /** Called before a socket read. */
  public static void beforeSocketRead(final Object stream) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_SOCKET_READ].invoke(d, stream);
          }
          return null;
        },
        null);
  }

  /** Called before a socket write. */
  public static void beforeSocketWrite(final Object stream, final int len) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_SOCKET_WRITE].invoke(d, stream, len);
          }
          return null;
        },
        null);
  }

  /** Called before {@code Socket.close()}. */
  public static void beforeSocketClose(final Object socket) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_SOCKET_CLOSE].invoke(d, socket);
          }
          return null;
        },
        null);
  }

  /** Called before {@code InitialContext.lookup(name)}. */
  public static void beforeJndiLookup(final Object context, final String name) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_JNDI_LOOKUP].invoke(d, context, name);
          }
          return null;
        },
        null);
  }

  /** Called before {@code ObjectOutputStream.writeObject(obj)}. */
  public static void beforeObjectSerialize(final Object stream, final Object obj) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_OBJECT_SERIALIZE].invoke(d, stream, obj);
          }
          return null;
        },
        null);
  }

  /** Called before {@code System.loadLibrary(name)} or {@code System.load(name)}. */
  public static void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_NATIVE_LIBRARY_LOAD].invoke(d, libraryName);
          }
          return null;
        },
        null);
  }

  /**
   * Called before {@code CompletableFuture.cancel(mayInterruptIfRunning)}. Returns {@code true} if
   * cancel should be suppressed (advice returns {@code true} without cancelling), {@code false} for
   * normal execution.
   */
  public static boolean beforeAsyncCancel(final Object future, final boolean mayInterruptIfRunning)
      throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null)
              && (boolean) h[BEFORE_ASYNC_CANCEL].invoke(d, future, mayInterruptIfRunning);
        },
        false);
  }

  /** Called before {@code Inflater.inflate(...)}. */
  public static void beforeZipInflate() throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_ZIP_INFLATE].invoke(d);
          }
          return null;
        },
        null);
  }

  /** Called before {@code Deflater.deflate(...)}. */
  public static void beforeZipDeflate() throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_ZIP_DEFLATE].invoke(d);
          }
          return null;
        },
        null);
  }

  /**
   * Called before {@code ThreadLocal.get()}. Returns {@code true} if the get should return {@code
   * null} (suppress), {@code false} for normal execution.
   */
  public static boolean beforeThreadLocalGet(final Object threadLocal) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null)
              && (boolean) h[BEFORE_THREAD_LOCAL_GET].invoke(d, threadLocal);
        },
        false);
  }

  /**
   * Called before {@code ThreadLocal.set(value)}. Returns {@code true} if the set should be
   * suppressed, {@code false} for normal execution.
   */
  public static boolean beforeThreadLocalSet(final Object threadLocal, final Object value)
      throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null)
              && (boolean) h[BEFORE_THREAD_LOCAL_SET].invoke(d, threadLocal, value);
        },
        false);
  }

  /** Called before {@code MBeanServer.invoke(...)}. */
  public static void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_JMX_INVOKE].invoke(d, server, objectName, operationName);
          }
          return null;
        },
        null);
  }

  /** Called before {@code MBeanServer.getAttribute(...)}. */
  public static void beforeJmxGetAttr(
      final Object server, final Object objectName, final String attribute) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_JMX_GET_ATTR].invoke(d, server, objectName, attribute);
          }
          return null;
        },
        null);
  }

  // ── Internal helpers ───────────────────────────────────────────────────────

  private static <T> T invoke(final ThrowingSupplier<T> supplier, final T fallback) {
    if (DEPTH.get() > 0) {
      return fallback;
    }
    DEPTH.set(DEPTH.get() + 1);
    try {
      return supplier.get();
    } catch (Throwable throwable) {
      sneakyThrow(throwable);
      return fallback;
    } finally {
      final int next = DEPTH.get() - 1;
      if (next == 0) {
        DEPTH.remove();
      } else {
        DEPTH.set(next);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(final Throwable throwable) throws T {
    throw (T) throwable;
  }

  @FunctionalInterface
  interface ThrowingSupplier<T> {
    T get() throws Throwable;
  }
}

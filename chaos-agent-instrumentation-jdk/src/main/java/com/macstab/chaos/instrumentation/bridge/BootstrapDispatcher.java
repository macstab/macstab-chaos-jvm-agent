package com.macstab.chaos.instrumentation.bridge;

import java.lang.invoke.MethodHandle;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

/**
 * Bootstrap-classloader-resident static dispatcher that routes intercepted JDK operations to the
 * chaos runtime.
 *
 * <h2>Classloader isolation</h2>
 *
 * <p>ByteBuddy {@link net.bytebuddy.asm.Advice @Advice} classes woven into JDK methods (e.g. {@code
 * Thread.start()}, {@code System.currentTimeMillis()}) execute in whatever classloader loaded the
 * target class — which for JDK classes is the bootstrap classloader. Normal agent classes live in
 * the agent classloader and are therefore invisible to bootstrap-loaded code.
 *
 * <p>To bridge this gap, {@code BootstrapDispatcher} is packaged into a temporary JAR and appended
 * to the bootstrap classpath at agent startup (via {@code
 * Instrumentation.appendToBootstrapClassLoaderSearch}). Once loaded by the bootstrap classloader,
 * it is visible to all instrumented JDK code and can be called directly from advice.
 *
 * <h2>MethodHandle bridge</h2>
 *
 * <p>The actual {@code ChaosRuntime} implementation lives in the agent classloader and is
 * unreachable by name from bootstrap code. The bridge is established at startup via {@link
 * #install}: the agent classloader passes in a {@code BridgeDelegate} instance (as {@code Object}
 * to avoid class-not-found errors in bootstrap code) and a pre-built {@code MethodHandle[]} of size
 * {@link #HANDLE_COUNT}. Each element is a handle bound to the corresponding method on the
 * delegate. Integer constants ({@link #DECORATE_EXECUTOR_RUNNABLE} … {@link #BEFORE_JMX_GET_ATTR})
 * serve as stable indices into this array.
 *
 * <h2>Reentrancy guard</h2>
 *
 * <p>Chaos processing itself calls instrumented JDK methods (e.g. {@code Thread.sleep}, {@code
 * System.currentTimeMillis}). Without protection this would recurse infinitely. The {@code DEPTH}
 * {@link ThreadLocal} counts nested dispatch calls on the current thread. If {@code DEPTH > 0} when
 * a dispatch method is entered, the method returns its safe fallback immediately without invoking
 * the delegate. The {@code ThreadLocal} is {@linkplain ThreadLocal#remove() removed} when the
 * outermost call unwinds to avoid memory leaks in thread pools.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Both {@link #delegate} and {@link #handles} are {@code volatile}. Each dispatch method
 * snapshot-reads both fields into local variables before use; this eliminates time-of-check /
 * time-of-use races across the two-field publication protocol used by {@link #install}.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #install} must be called exactly once before any dispatch method can route to the
 * delegate. Before {@code install}, all dispatch methods return their documented fallback values,
 * allowing the JVM to start cleanly without chaos enabled.
 */
public final class BootstrapDispatcher {
  /**
   * Per-thread reentrancy depth counter. Private to prevent external mutation; exposed via {@link
   * #depthThreadLocal()} for the sole purpose of identity-comparison in {@code
   * ThreadLocalGetAdvice} / {@code ThreadLocalSetAdvice} to break the instrumentation recursion
   * that would otherwise occur when {@code ThreadLocal.get()} is intercepted. See {@code
   * JvmRuntimeAdvice.ThreadLocalGetAdvice} for the full reentrancy analysis.
   */
  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  /**
   * Returns the {@link ThreadLocal} instance used internally as the reentrancy depth counter.
   *
   * <p>This method exists solely to allow advice classes ({@code ThreadLocalGetAdvice}, {@code
   * ThreadLocalSetAdvice}) to perform an identity check:
   *
   * <pre>{@code
   * if (threadLocal == BootstrapDispatcher.depthThreadLocal()) return false;
   * }</pre>
   *
   * <p>That check prevents infinite recursion when {@code ThreadLocal.get()} is globally
   * instrumented: the dispatcher's own depth counter read would otherwise re-trigger the advice.
   * The returned reference must never be mutated by callers.
   *
   * @return the depth {@code ThreadLocal}; never null
   */
  public static ThreadLocal<Integer> depthThreadLocal() {
    return DEPTH;
  }

  private static volatile Object delegate;
  private static volatile MethodHandle[] handles;

  /**
   * Indices into the {@link #handles} array for Phase 1 (concurrency / scheduling) interception
   * points. Values are stable and must match the array built by {@code
   * JdkInstrumentationInstaller.buildMethodHandles()}.
   */
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

  /**
   * Indices into the {@link #handles} array for Phase 2 (JVM-level) interception points. Values are
   * stable and must match the array built by {@code
   * JdkInstrumentationInstaller.buildMethodHandles()}.
   */
  // ── Phase 2 handles (15-45) ───────────────────────────────────────────────
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
  public static final int ADJUST_INSTANT_NOW = 42;
  public static final int ADJUST_LOCAL_DATE_TIME_NOW = 43;
  public static final int ADJUST_ZONED_DATE_TIME_NOW = 44;
  public static final int ADJUST_DATE_NEW = 45;
  public static final int BEFORE_HTTP_SEND = 46;
  public static final int BEFORE_HTTP_SEND_ASYNC = 47;
  public static final int BEFORE_JDBC_CONNECTION_ACQUIRE = 48;
  public static final int BEFORE_JDBC_STATEMENT_EXECUTE = 49;
  public static final int BEFORE_JDBC_PREPARED_STATEMENT = 50;
  public static final int BEFORE_JDBC_TRANSACTION_COMMIT = 51;
  public static final int BEFORE_JDBC_TRANSACTION_ROLLBACK = 52;

  // ── Phase 2 handles (53-56) ───────────────────────────────────────────────
  public static final int BEFORE_THREAD_SLEEP = 53;

  public static final int BEFORE_DNS_RESOLVE = 54;
  public static final int BEFORE_SSL_HANDSHAKE = 55;
  public static final int BEFORE_FILE_IO = 56;

  /** Total number of method-handle slots; equals the highest index plus one. */
  public static final int HANDLE_COUNT = 57;

  private BootstrapDispatcher() {}

  /**
   * Wires the delegate and its pre-built method handles into this dispatcher.
   *
   * <p>Must be called exactly once, by the agent classloader, after this class has been loaded into
   * the bootstrap classloader. The write order — handles before delegate — is intentional: a racing
   * reader that snapshots a non-null {@code delegate} is guaranteed to also see a non-null {@code
   * handles} array.
   *
   * @param bridgeDelegate the {@link com.macstab.chaos.instrumentation.bridge.BridgeDelegate}
   *     instance from the agent classloader; passed as {@code Object} to avoid a {@code
   *     ClassNotFoundException} in bootstrap code
   * @param methodHandles array of exactly {@link #HANDLE_COUNT} {@link
   *     java.lang.invoke.MethodHandle} instances, indexed by the public integer constants defined
   *     on this class; each handle is pre-bound to {@code bridgeDelegate} so that dispatch calls
   *     need only supply the per-invocation arguments
   */
  public static void install(final Object bridgeDelegate, final MethodHandle[] methodHandles) {
    handles = methodHandles;
    delegate = bridgeDelegate;
  }

  // ── Phase 1 dispatch methods ───────────────────────────────────────────────

  /**
   * Gives the active chaos scenario an opportunity to wrap the submitted runnable before it is
   * handed to the executor.
   *
   * @param operation a string tag identifying the submission call site (e.g. {@code "execute"},
   *     {@code "submit"}); never {@code null}
   * @param executor the {@link java.util.concurrent.Executor} that will run the task; may be {@code
   *     null} when the calling context is not an executor subclass
   * @param task the runnable to potentially wrap; never {@code null}
   * @return the (possibly wrapped) runnable; equals {@code task} when the dispatcher is not yet
   *     installed, when the reentrancy guard fires, or when no active scenario applies
   * @throws Throwable if the delegate throws — propagated to the advice via sneaky-throw
   */
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

  /**
   * Gives the active chaos scenario an opportunity to wrap the submitted callable before it is
   * handed to the executor.
   *
   * @param <T> the callable's return type
   * @param operation a string tag identifying the submission call site; never {@code null}
   * @param executor the executor that will run the task; may be {@code null}
   * @param task the callable to potentially wrap; never {@code null}
   * @return the (possibly wrapped) callable; equals {@code task} as the fallback
   * @throws Throwable if the delegate throws
   */
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

  /**
   * Called immediately before {@link Thread#start()} transfers the thread to the OS scheduler.
   *
   * <p>An active scenario may inject a delay, throw to block the start, or record the event for
   * observability.
   *
   * @param thread the thread about to be started; never {@code null}
   * @throws Throwable if an active scenario injects an exception to suppress the start
   */
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

  /**
   * Called at the top of a thread-pool worker's run loop, before the next task is dequeued.
   *
   * @param executor the thread pool owning this worker; may be {@code null}
   * @param worker the worker thread currently executing; never {@code null}
   * @param task the task about to run, when available from the framework; may be {@code null}
   * @throws Throwable if an active scenario injects an exception to stall or disrupt the worker
   */
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

  /**
   * Called before a {@link java.util.concurrent.ForkJoinTask} begins execution on a {@link
   * java.util.concurrent.ForkJoinPool} worker.
   *
   * @param task the fork-join task about to execute; never {@code null}
   * @throws Throwable if an active scenario injects an exception
   */
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

  /**
   * Allows an active scenario to alter the scheduling delay of a {@link
   * java.util.concurrent.ScheduledExecutorService} submission.
   *
   * @param operation a tag identifying the scheduling call (e.g. {@code "schedule"}, {@code
   *     "scheduleAtFixedRate"}); never {@code null}
   * @param executor the scheduled executor; may be {@code null}
   * @param task the task being scheduled; may be {@code null}
   * @param delay the requested delay in milliseconds; may be zero or negative for immediate
   *     execution
   * @param periodic {@code true} if the task is a fixed-rate or fixed-delay repeating task
   * @return the (possibly modified) delay in milliseconds; equals {@code delay} as the fallback
   * @throws Throwable if the delegate throws
   */
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

  /**
   * Called before each execution of a scheduled task (both one-shot and periodic).
   *
   * @param executor the scheduled executor owning the task; may be {@code null}
   * @param task the task about to fire; may be {@code null}
   * @param periodic {@code true} if the task repeats
   * @return {@code true} if the task should execute normally; {@code false} if an active SUPPRESS
   *     scenario has vetoed this tick. Returns {@code true} as the fallback so that tasks proceed
   *     without an installed delegate.
   * @throws Throwable if the delegate throws
   */
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

  /**
   * Called before a blocking-queue operation such as {@code put}, {@code take}, or {@code offer}.
   *
   * @param operation a string tag identifying the queue method (e.g. {@code "put"}, {@code
   *     "take"}); never {@code null}
   * @param queue the queue instance; may be {@code null} if not available from the advice context
   * @throws Throwable if an active scenario injects an exception or decides to throw
   */
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

  /**
   * Called before a boolean-returning queue operation such as {@code offer(e, timeout, unit)}.
   *
   * @param operation a string tag identifying the queue method; never {@code null}
   * @param queue the queue instance; may be {@code null}
   * @return {@code Boolean.TRUE} to force the method to return {@code true} (SUPPRESS), {@code
   *     Boolean.FALSE} to force {@code false}, or {@code null} to let the original call proceed;
   *     returns {@code null} as the fallback
   * @throws Throwable if the delegate throws
   */
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

  /**
   * Called before a {@link java.util.concurrent.CompletableFuture} completion method ({@code
   * complete}, {@code completeExceptionally}, {@code cancel}).
   *
   * @param operation a tag identifying the completion call (e.g. {@code "complete"}, {@code
   *     "completeExceptionally"}); never {@code null}
   * @param future the future being completed; never {@code null}
   * @param payload the value or exception passed to the completion method, or {@code null} for
   *     calls with no payload
   * @return {@code Boolean.TRUE} to suppress the real completion, {@code Boolean.FALSE} to force
   *     the real completion, or {@code null} to let the original call proceed; returns {@code null}
   *     as the fallback
   * @throws Throwable if the delegate throws
   */
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

  /**
   * Called inside {@link ClassLoader#loadClass(String)} before class resolution begins.
   *
   * @param loader the classloader being asked to load the class; never {@code null}
   * @param className the binary name of the class (e.g. {@code "com.example.Foo"}); never {@code
   *     null}
   * @throws Throwable if an active scenario wants to simulate a {@link ClassNotFoundException}
   */
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

  /**
   * Called after {@link ClassLoader#getResource(String)} returns, allowing a scenario to substitute
   * or nullify the resolved URL.
   *
   * @param loader the classloader that performed the lookup; never {@code null}
   * @param name the resource name as passed to {@code getResource}; never {@code null}
   * @param currentValue the URL returned by the real lookup, or {@code null} if the resource was
   *     not found
   * @return the URL that should be returned to the caller; may differ from {@code currentValue}
   *     when an active scenario is replacing or suppressing the resource; equals {@code
   *     currentValue} as the fallback
   * @throws Throwable if the delegate throws
   */
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

  /**
   * Wraps a shutdown hook thread before it is registered with the JVM, allowing a scenario to track
   * or intercept JVM shutdown.
   *
   * @param hook the shutdown-hook thread submitted by the application; never {@code null}
   * @return the (possibly wrapped) thread to register; equals {@code hook} as the fallback
   * @throws Throwable if the delegate throws
   */
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

  /**
   * Resolves the original application hook from a previously wrapped hook thread.
   *
   * <p>Called when the JVM removes a shutdown hook (e.g. via {@code Runtime.removeShutdownHook}),
   * so that the lookup key matches the wrapper registered by {@link #decorateShutdownHook}.
   *
   * @param original the thread passed to {@code removeShutdownHook}; never {@code null}
   * @return the registered wrapper thread, or {@code original} if no wrapping occurred or the
   *     delegate is not installed
   */
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

  /**
   * Called before an executor service's {@code shutdown} or {@code shutdownNow}.
   *
   * @param operation a tag identifying the shutdown variant ({@code "shutdown"} or {@code
   *     "shutdownNow"}); never {@code null}
   * @param executor the executor being shut down; never {@code null}
   * @param timeoutMillis the await-termination timeout supplied by the caller, in milliseconds;
   *     {@code 0} if the call was {@code shutdown()} with no explicit timeout
   * @throws Throwable if an active scenario wants to inject a failure before shutdown completes
   */
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

  /**
   * Returns the chaos-adjusted wall-clock time in milliseconds.
   *
   * <p>Called by {@code ClockMillisAdvice} to rewrite the return value of {@code
   * System.currentTimeMillis()}.
   *
   * @param realMillis the value returned by the real {@code System.currentTimeMillis()} call
   * @return the (possibly skewed) millisecond timestamp; equals {@code realMillis} when no active
   *     clock-skew scenario applies or the delegate is not installed
   * @throws Throwable if the delegate throws
   */
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

  /**
   * Returns the chaos-adjusted monotonic time in nanoseconds.
   *
   * <p>Called by {@code ClockNanosAdvice} to rewrite the return value of {@code System.nanoTime()}.
   *
   * @param realNanos the value returned by the real {@code System.nanoTime()} call
   * @return the (possibly skewed) nanosecond timestamp; equals {@code realNanos} as the fallback
   * @throws Throwable if the delegate throws
   */
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
   * Returns the chaos-adjusted {@link java.time.Instant} for {@code Instant.now()} interception.
   *
   * @param realInstant the value returned by the real {@code Instant.now()} call; never {@code
   *     null}
   * @return the (possibly skewed) instant; equals {@code realInstant} as the fallback
   * @throws Throwable if the delegate throws
   */
  public static java.time.Instant adjustInstantNow(final java.time.Instant realInstant)
      throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? realInstant
              : (java.time.Instant) h[ADJUST_INSTANT_NOW].invoke(d, realInstant);
        },
        realInstant);
  }

  /**
   * Returns the chaos-adjusted {@link java.time.LocalDateTime} for {@code LocalDateTime.now()}
   * interception.
   *
   * @param realValue the value returned by the real {@code LocalDateTime.now()} call; never {@code
   *     null}
   * @return the (possibly skewed) local date-time; equals {@code realValue} as the fallback
   * @throws Throwable if the delegate throws
   */
  public static java.time.LocalDateTime adjustLocalDateTimeNow(
      final java.time.LocalDateTime realValue) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? realValue
              : (java.time.LocalDateTime) h[ADJUST_LOCAL_DATE_TIME_NOW].invoke(d, realValue);
        },
        realValue);
  }

  /**
   * Returns the chaos-adjusted {@link java.time.ZonedDateTime} for {@code ZonedDateTime.now()}
   * interception.
   *
   * @param realValue the value returned by the real {@code ZonedDateTime.now()} call; never {@code
   *     null}
   * @return the (possibly skewed) zoned date-time; equals {@code realValue} as the fallback
   * @throws Throwable if the delegate throws
   */
  public static java.time.ZonedDateTime adjustZonedDateTimeNow(
      final java.time.ZonedDateTime realValue) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? realValue
              : (java.time.ZonedDateTime) h[ADJUST_ZONED_DATE_TIME_NOW].invoke(d, realValue);
        },
        realValue);
  }

  /**
   * Returns the chaos-adjusted epoch-millisecond value for the embedded time of a freshly
   * constructed {@link java.util.Date}.
   *
   * @param realMillis the millisecond value captured by the {@code Date()} constructor via {@code
   *     System.currentTimeMillis()}
   * @return the (possibly skewed) millisecond timestamp; equals {@code realMillis} as the fallback
   * @throws Throwable if the delegate throws
   */
  public static long adjustDateNew(final long realMillis) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d == null || h == null)
              ? realMillis
              : (long) h[ADJUST_DATE_NEW].invoke(d, realMillis);
        },
        realMillis);
  }

  /**
   * Called before {@code System.gc()} or {@code Runtime.gc()}.
   *
   * <p>The returned flag drives the {@code skipOn = Advice.OnNonDefaultValue.class} mechanism in
   * {@code GcRequestAdvice}: returning {@code true} causes ByteBuddy to skip the GC call entirely.
   *
   * @return {@code true} if an active SUPPRESS scenario wants to block the garbage-collection
   *     request; {@code false} to allow it. Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
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

  /**
   * Called before {@code System.exit(status)} or {@code Runtime.halt(status)}.
   *
   * <p>An active SUPPRESS scenario may throw {@link SecurityException} to abort the exit; a THROW
   * scenario may inject any other exception.
   *
   * @param status the exit status code passed by the application
   * @throws SecurityException if an active SUPPRESS scenario blocks the exit
   * @throws Throwable if any other scenario-driven exception is injected
   */
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

  /**
   * Called before {@link java.lang.reflect.Method#invoke(Object, Object...)}.
   *
   * @param method the {@link java.lang.reflect.Method} about to be invoked; never {@code null}
   * @param target the object on which the method is being invoked; {@code null} for static methods
   * @throws Throwable if an active scenario injects an exception to abort the reflective call
   */
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

  /**
   * Called before {@link java.nio.ByteBuffer#allocateDirect(int)}.
   *
   * @param capacity the number of bytes requested for the direct buffer; non-negative
   * @throws Throwable if an active scenario wants to simulate an {@link OutOfMemoryError} or
   *     another allocation failure
   */
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

  /**
   * Called before {@link java.io.ObjectInputStream#readObject()}.
   *
   * @param stream the {@link java.io.ObjectInputStream} about to deserialize an object; never
   *     {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate corrupt or hostile
   *     serialized data
   */
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

  /**
   * Called before {@code ClassLoader.defineClass(...)}.
   *
   * @param loader the classloader defining the class; never {@code null}
   * @param className the binary name of the class being defined, or {@code null} when the caller
   *     did not supply a name
   * @throws Throwable if an active scenario wants to simulate a class-definition failure
   */
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

  /**
   * Called before {@code AbstractQueuedSynchronizer.acquire(int)} as a proxy for monitor-enter
   * contention.
   *
   * <p>An active scenario may inject a delay to simulate lock contention.
   *
   * @throws Throwable if an active scenario injects an exception
   */
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

  /**
   * Called before {@code LockSupport.park(Object)}, {@code parkNanos}, or {@code parkUntil}.
   *
   * <p>An active scenario may inject a delay before the park call to simulate scheduler pressure.
   *
   * @throws Throwable if an active scenario injects an exception
   */
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
   * Called before {@link java.nio.channels.Selector#select()} or {@link
   * java.nio.channels.Selector#select(long)}.
   *
   * <p>The returned flag drives the {@code skipOn} mechanism in {@code NioSelectNoArgAdvice} and
   * {@code NioSelectTimeoutAdvice}: returning {@code true} causes the advice to skip the real
   * {@code select} call and return {@code 0} (simulating a spurious wakeup).
   *
   * @param selector the {@link java.nio.channels.Selector} about to block; never {@code null}
   * @param timeoutMillis the timeout parameter as passed to the overload; {@code 0} for the
   *     no-argument variant
   * @return {@code true} to force a spurious wakeup; {@code false} for normal execution. Returns
   *     {@code false} as the fallback.
   * @throws Throwable if the delegate throws
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

  /**
   * Called before a NIO channel read, write, connect, or accept operation.
   *
   * @param operation one of {@code "NIO_CHANNEL_READ"}, {@code "NIO_CHANNEL_WRITE"}, {@code
   *     "NIO_CHANNEL_CONNECT"}, or {@code "NIO_CHANNEL_ACCEPT"}; never {@code null}
   * @param channel the NIO channel instance; never {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate an I/O error
   */
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

  /**
   * Called before {@link java.net.Socket#connect(java.net.SocketAddress, int)}.
   *
   * @param socket the socket initiating the connection; never {@code null}
   * @param socketAddress the remote endpoint; never {@code null}
   * @param timeoutMillis the connection timeout; {@code 0} for infinite timeout
   * @throws Throwable if an active scenario injects a failure to simulate a connection refusal or
   *     timeout
   */
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

  /**
   * Called before {@link java.net.ServerSocket#accept()}.
   *
   * @param serverSocket the server socket about to block waiting for a connection; never {@code
   *     null}
   * @throws Throwable if an active scenario injects a failure to simulate an accept error
   */
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

  /**
   * Called before a socket input-stream read operation.
   *
   * @param stream the socket {@link java.io.InputStream}; never {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate a read error or timeout
   */
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

  /**
   * Called before a socket output-stream write operation.
   *
   * @param stream the socket {@link java.io.OutputStream}; never {@code null}
   * @param len the number of bytes about to be written; {@code 0} when the exact count is
   *     unavailable from the advice context
   * @throws Throwable if an active scenario injects a failure to simulate a write error
   */
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

  /**
   * Called before {@link java.net.Socket#close()}.
   *
   * @param socket the socket being closed; never {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate a close error
   */
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

  /**
   * Called before {@link javax.naming.InitialContext#lookup(String)}.
   *
   * @param context the {@code InitialContext} performing the lookup; never {@code null}
   * @param name the JNDI name being looked up; never {@code null}
   * @throws Throwable if an active scenario injects a {@link javax.naming.NamingException} to
   *     simulate a JNDI failure
   */
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

  /**
   * Called before {@link java.io.ObjectOutputStream#writeObject(Object)}.
   *
   * @param stream the {@link java.io.ObjectOutputStream} performing the serialization; never {@code
   *     null}
   * @param obj the object about to be serialized; may be {@code null} (serialization of null is
   *     valid)
   * @throws Throwable if an active scenario injects a failure to simulate a serialization error
   */
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

  /**
   * Called before {@code System.loadLibrary(name)} or {@code System.load(path)}.
   *
   * @param libraryName the library name (for {@code loadLibrary}) or the absolute path (for {@code
   *     load}); never {@code null}
   * @throws Throwable if an active scenario wants to simulate an {@link UnsatisfiedLinkError} or
   *     block native library loading
   */
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
   * Called before {@link java.util.concurrent.CompletableFuture#cancel(boolean)}.
   *
   * <p>Returning {@code true} causes {@code AsyncCancelAdvice} to skip the real cancel call and
   * return {@code true} to the caller, simulating a successful cancel that never actually
   * cancelled.
   *
   * @param future the future whose cancellation is being intercepted; never {@code null}
   * @param mayInterruptIfRunning the flag passed by the application to {@code cancel}
   * @return {@code true} if the cancel should be suppressed (advice returns {@code true} without
   *     cancelling); {@code false} for normal execution. Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
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

  /**
   * Called before {@code Inflater.inflate(...)}.
   *
   * <p>An active scenario may inject a delay to simulate slow decompression under load.
   *
   * @throws Throwable if an active scenario injects an exception
   */
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

  /**
   * Called before {@code Deflater.deflate(...)}.
   *
   * <p>An active scenario may inject a delay to simulate slow compression under load.
   *
   * @throws Throwable if an active scenario injects an exception
   */
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
   * Called before {@link ThreadLocal#get()}.
   *
   * <p>Returning {@code true} causes {@code ThreadLocalGetAdvice} to skip the real get and return
   * {@code null} to the caller, simulating an absent thread-local value.
   *
   * @param threadLocal the {@link ThreadLocal} being read; never {@code null}
   * @return {@code true} to suppress the get and return {@code null}; {@code false} for normal
   *     execution. Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
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
   * Called before {@link ThreadLocal#set(Object)}.
   *
   * <p>Returning {@code true} causes {@code ThreadLocalSetAdvice} to skip the real set, silently
   * discarding the value.
   *
   * @param threadLocal the {@link ThreadLocal} being written; never {@code null}
   * @param value the value the application is attempting to store; may be {@code null}
   * @return {@code true} to suppress the set; {@code false} for normal execution. Returns {@code
   *     false} as the fallback.
   * @throws Throwable if the delegate throws
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

  /**
   * Called before {@code MBeanServer.invoke(ObjectName, String, Object[], String[])}.
   *
   * @param server the {@code MBeanServer} performing the operation; never {@code null}
   * @param objectName the {@code ObjectName} of the target MBean; never {@code null}
   * @param operationName the name of the MBean operation being invoked; never {@code null}
   * @throws Throwable if an active scenario injects a {@link javax.management.MBeanException} or
   *     other JMX failure
   */
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

  /**
   * Called before {@code MBeanServer.getAttribute(ObjectName, String)}.
   *
   * @param server the {@code MBeanServer} performing the attribute read; never {@code null}
   * @param objectName the {@code ObjectName} of the target MBean; never {@code null}
   * @param attribute the name of the attribute being read; never {@code null}
   * @throws Throwable if an active scenario injects a {@link javax.management.MBeanException} or
   *     other JMX failure
   */
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

  /**
   * Called before a synchronous HTTP client {@code send} (Java {@code HttpClient.send}, {@code
   * okhttp3.RealCall.execute}, Apache HttpComponents {@code CloseableHttpClient.execute}).
   *
   * <p>Returning {@code true} causes the advice to skip the real send call. The advice is
   * responsible for throwing {@link com.macstab.chaos.api.ChaosHttpSuppressException} when the call
   * is suppressed so the caller observes a terminal failure instead of a dropped request.
   *
   * @param url the request URL in {@code scheme://host/path} form; may be {@code null} when the
   *     advice cannot extract a URL
   * @return {@code true} if the send should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeHttpSend(final String url) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null) && (boolean) h[BEFORE_HTTP_SEND].invoke(d, url);
        },
        false);
  }

  /**
   * Called before an asynchronous HTTP client send (Java {@code HttpClient.sendAsync}, {@code
   * okhttp3.RealCall.enqueue}, Spring WebClient {@code HttpClientConnect.connect}).
   *
   * <p>Returning {@code true} causes the advice to skip the real send call.
   *
   * @param url the request URL in {@code scheme://host/path} form; may be {@code null}
   * @return {@code true} if the send should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeHttpSendAsync(final String url) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null) && (boolean) h[BEFORE_HTTP_SEND_ASYNC].invoke(d, url);
        },
        false);
  }

  /**
   * Called before a JDBC connection is acquired from a pool (HikariCP, c3p0).
   *
   * <p>Returning {@code true} causes the advice to throw {@link
   * com.macstab.chaos.api.ChaosJdbcSuppressException}.
   *
   * @param poolName the pool identifier; may be {@code null}
   * @return {@code true} if acquisition should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null)
              && (boolean) h[BEFORE_JDBC_CONNECTION_ACQUIRE].invoke(d, poolName);
        },
        false);
  }

  /**
   * Called before a {@link java.sql.Statement} execute call.
   *
   * @param sql the SQL statement; may be {@code null}
   * @return {@code true} if the statement should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeJdbcStatementExecute(final String sql) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null)
              && (boolean) h[BEFORE_JDBC_STATEMENT_EXECUTE].invoke(d, sql);
        },
        false);
  }

  /**
   * Called before a {@link java.sql.Connection#prepareStatement(String)} call.
   *
   * @param sql the SQL statement being prepared; may be {@code null}
   * @return {@code true} if preparation should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeJdbcPreparedStatement(final String sql) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null)
              && (boolean) h[BEFORE_JDBC_PREPARED_STATEMENT].invoke(d, sql);
        },
        false);
  }

  /**
   * Called before a {@link java.sql.Connection#commit()} call.
   *
   * @return {@code true} if the commit should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeJdbcTransactionCommit() throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null) && (boolean) h[BEFORE_JDBC_TRANSACTION_COMMIT].invoke(d);
        },
        false);
  }

  /**
   * Called before a {@link java.sql.Connection#rollback()} call.
   *
   * @return {@code true} if the rollback should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeJdbcTransactionRollback() throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null)
              && (boolean) h[BEFORE_JDBC_TRANSACTION_ROLLBACK].invoke(d);
        },
        false);
  }

  /**
   * Called before {@link Thread#sleep(long)}.
   *
   * <p>Returns {@code true} to skip the sleep entirely (simulating a spurious wake-up or
   * timeout-cancellation); {@code false} for normal execution. Returns {@code false} as the
   * fallback.
   *
   * @param millis the sleep duration in milliseconds as passed to {@code Thread.sleep}
   * @return {@code true} to suppress the sleep; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeThreadSleep(final long millis) throws Throwable {
    return invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          return (d != null && h != null) && (boolean) h[BEFORE_THREAD_SLEEP].invoke(d, millis);
        },
        false);
  }

  /**
   * Called before {@link java.net.InetAddress#getByName(String)}, {@link
   * java.net.InetAddress#getAllByName(String)}, or {@link java.net.InetAddress#getLocalHost()}.
   *
   * @param hostname the hostname being resolved; {@code null} for {@code getLocalHost()}
   * @throws Throwable if an active scenario injects an exception to simulate a DNS failure
   */
  public static void beforeDnsResolve(final String hostname) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_DNS_RESOLVE].invoke(d, hostname);
          }
          return null;
        },
        null);
  }

  /**
   * Called before {@link javax.net.ssl.SSLSocket#startHandshake()} or {@link
   * javax.net.ssl.SSLEngine#beginHandshake()}.
   *
   * @param socket the {@code SSLSocket} or {@code SSLEngine} instance; never {@code null}
   * @throws Throwable if an active scenario injects an exception to simulate a TLS handshake
   *     failure
   */
  public static void beforeSslHandshake(final Object socket) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_SSL_HANDSHAKE].invoke(d, socket);
          }
          return null;
        },
        null);
  }

  /**
   * Called before a {@link java.io.FileInputStream#read} or {@link java.io.FileOutputStream#write}
   * call.
   *
   * @param operation {@code "FILE_IO_READ"} or {@code "FILE_IO_WRITE"}; never {@code null}
   * @param stream the {@code FileInputStream} or {@code FileOutputStream} instance; never {@code
   *     null}
   * @throws Throwable if an active scenario injects an exception to simulate an I/O failure
   */
  public static void beforeFileIo(final String operation, final Object stream) throws Throwable {
    invoke(
        () -> {
          final MethodHandle[] h = handles;
          final Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_FILE_IO].invoke(d, operation, stream);
          }
          return null;
        },
        null);
  }

  // ── Internal helpers ───────────────────────────────────────────────────────

  /**
   * Reentrancy-guarded dispatch trampoline shared by all public dispatch methods.
   *
   * <p>If {@code DEPTH > 0} (i.e., the current thread is already inside a chaos dispatch), this
   * method returns {@code fallback} immediately without invoking {@code supplier}. This prevents
   * infinite recursion when chaos-processing code itself calls an instrumented JDK method.
   *
   * <p>On normal completion or on exception, the {@code DEPTH} counter is decremented. When it
   * returns to zero the {@link ThreadLocal} is {@linkplain ThreadLocal#remove() removed} to prevent
   * ThreadLocal leaks in pooled threads.
   *
   * @param <T> the return type
   * @param supplier the lambda that snapshot-reads {@link #handles} and {@link #delegate} and
   *     invokes the appropriate method handle; must not be {@code null}
   * @param fallback the value to return when the reentrancy guard fires or when {@code supplier}
   *     throws and no rethrow is needed
   * @return the value produced by {@code supplier}, or {@code fallback} on reentrancy
   * @throws Throwable any exception thrown by {@code supplier}, rethrown via {@link
   *     #sneakyThrow(Throwable)} to avoid checked-exception declaration pollution
   */
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

  /**
   * Rethrows any {@link Throwable} without requiring a checked-exception declaration.
   *
   * <p>Uses an unchecked cast to the generic type parameter to fool the compiler. The JVM does not
   * enforce checked exceptions at runtime, so this is safe — the exception is rethrown unchanged
   * with its original type and stack trace.
   *
   * @param <T> a type parameter constrained to {@link Throwable}; bound by the compiler to {@link
   *     RuntimeException} so the method signature declares {@code throws T} without a
   *     checked-exception obligation on callers
   * @param throwable the exception to rethrow; never {@code null}
   * @throws T always — this method never returns normally
   */
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(final Throwable throwable) throws T {
    throw (T) throwable;
  }

  /**
   * A supplier that is permitted to throw any {@link Throwable}, used as the lambda type for the
   * dispatch trampoline in {@link #invoke(ThrowingSupplier, Object)}.
   *
   * @param <T> the type of the supplied value
   */
  @FunctionalInterface
  interface ThrowingSupplier<T> {
    T get() throws Throwable;
  }
}

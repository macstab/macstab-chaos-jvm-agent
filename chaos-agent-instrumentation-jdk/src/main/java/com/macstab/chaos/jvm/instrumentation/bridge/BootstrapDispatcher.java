package com.macstab.chaos.jvm.instrumentation.bridge;

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
 * {@link #HANDLE_COUNT}. Each element is an unbound virtual {@link MethodHandle} resolved against
 * the {@link BridgeDelegate} interface; every dispatch call supplies the delegate instance as the
 * first argument at invocation time. Integer constants ({@link #DECORATE_EXECUTOR_RUNNABLE} …
 * {@link #BEFORE_JMX_GET_ATTR}) serve as stable indices into this array.
 *
 * <h2>Reentrancy guard</h2>
 *
 * <p>Chaos processing itself calls instrumented JDK methods (e.g. {@code Thread.sleep}, {@code
 * System.currentTimeMillis}). Without protection this would recurse infinitely. The {@code DEPTH}
 * {@link ThreadLocal} counts nested dispatch calls on the current thread. If {@code DEPTH > 0} when
 * a dispatch method is entered, the method returns its safe fallback immediately without invoking
 * the delegate. The same per-thread array also holds the call-depth scope counters (see {@link
 * #enterScope(int)}), so it is kept for the thread's lifetime rather than removed on unwind.
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
   *
   * <p>An {@code int[]} (index 0: reentrancy depth, then one counter per call-depth scope) is held
   * rather than an {@link Integer} so each dispatch method's guard performs exactly one {@link
   * ThreadLocal#get()} (per call) and mutates the counter in place — avoiding both a second {@code
   * get()} and the Integer autoboxing that a {@code set(get()+1)} pair would incur. This matters
   * because every intercepted JDK call hits this guard.
   */
  private static final ThreadLocal<int[]> DEPTH =
      ThreadLocal.withInitial(() -> new int[BootstrapDispatcher.SCOPE_COUNT]);

  // ── Call-depth scopes ──────────────────────────────────────────────────────
  //
  // Many instrumented methods call another instrumented method for the same logical operation:
  // InetAddress.getByName → getAllByName, a generic bridge → its typed twin, a pool proxy →
  // the driver statement, PgConnection.prepareStatement(String) → prepareStatement(String, int,
  // int, int), CloseableHttpClient.execute(request) → execute(request, context), a class loader
  // → its parent. Without a guard each nested hop is evaluated as a separate call: delays add
  // up, probability is rolled several times and maxApplications runs out early.
  //
  // Advice for such operations brackets the method with enterScope/exitScope. enterScope
  // returns true only for the outermost call on the current thread, and only that call
  // dispatches. The counters live in the same per-thread array as the reentrancy depth (index
  // 0), so ThreadLocalGetAdvice's identity check on DEPTH also covers them and no second
  // instrumented ThreadLocal is introduced.

  /** Scope index for DNS resolution ({@code InetAddress.getByName} / {@code getAllByName}). */
  public static final int SCOPE_DNS = 1;

  /** Scope index for {@code Inflater.inflate} overloads. */
  public static final int SCOPE_ZIP_INFLATE = 2;

  /** Scope index for {@code Deflater.deflate} overloads. */
  public static final int SCOPE_ZIP_DEFLATE = 3;

  /** Scope index for {@code MBeanServer.invoke} / {@code getAttribute} (server → interceptor). */
  public static final int SCOPE_JMX = 4;

  /** Scope index for {@code BlockingQueue} put / offer / take / poll (bridges, put → offer). */
  public static final int SCOPE_QUEUE = 5;

  /** Scope index for {@code ClassLoader.loadClass} (overloads and parent delegation). */
  public static final int SCOPE_CLASS_LOAD = 6;

  /** Scope index for {@code ClassLoader.defineClass} overloads. */
  public static final int SCOPE_CLASS_DEFINE = 7;

  /** Scope index for {@code ClassLoader.getResource} (parent delegation). */
  public static final int SCOPE_RESOURCE_LOAD = 8;

  /** Scope index for {@code LockSupport.park*} overloads. */
  public static final int SCOPE_THREAD_PARK = 9;

  /** Scope index for {@code Connection.prepareStatement} / {@code prepareCall} overloads. */
  public static final int SCOPE_JDBC_PREPARE = 10;

  /** Scope index for {@code Statement.execute*} overloads and proxy → driver delegation. */
  public static final int SCOPE_JDBC_EXECUTE = 11;

  /** Scope index for {@code Connection.commit} (proxy → driver). */
  public static final int SCOPE_JDBC_COMMIT = 12;

  /** Scope index for {@code Connection.rollback} (proxy → driver). */
  public static final int SCOPE_JDBC_ROLLBACK = 13;

  /** Scope index for HTTP client execute / send overloads. */
  public static final int SCOPE_HTTP = 14;

  /** Scope index for TLS handshakes (wrapping engines delegate to the JDK engine). */
  public static final int SCOPE_SSL_HANDSHAKE = 15;

  /** Scope index for thread start ({@code VirtualThread.start()} → {@code start(container)}). */
  public static final int SCOPE_THREAD_START = 16;

  /** Scope index for {@code Thread.sleep} overloads. */
  public static final int SCOPE_THREAD_SLEEP = 17;

  /** Scope index for client-library calls that delegate to a wrapped client (R2DBC, AWS SDK). */
  public static final int SCOPE_CLIENT = 18;

  /**
   * Scope index held while a thread pool creates a worker ({@code ThreadPoolExecutor.addWorker}): a
   * thread started there serves many tasks and must not inherit the starter's session.
   */
  public static final int SCOPE_POOL_WORKER = 19;

  /** Size of the per-thread counter array: index 0 is the reentrancy depth, then the scopes. */
  static final int SCOPE_COUNT = 20;

  /**
   * Enters a call-depth scope on the current thread.
   *
   * <p>Advice must pair every call with exactly one {@link #exitScope(int)}: from the {@code
   * OnMethodExit(onThrowable = Throwable.class)} advice on normal and exceptional exits, and from
   * the enter advice itself when the dispatch it performed throws (ByteBuddy does not run exit
   * advice when enter advice throws).
   *
   * @param scope one of the {@code SCOPE_*} constants
   * @return {@code true} when this is the outermost call of the scope on the current thread, i.e.
   *     the call that should be evaluated
   */
  public static boolean enterScope(final int scope) {
    return DEPTH.get()[scope]++ == 0;
  }

  /**
   * Leaves a call-depth scope entered with {@link #enterScope(int)}.
   *
   * @param scope one of the {@code SCOPE_*} constants
   * @return {@code true} when the outermost call of the scope has now left it
   */
  public static boolean exitScope(final int scope) {
    final int[] counters = DEPTH.get();
    if (counters[scope] > 0) {
      counters[scope]--;
    }
    return counters[scope] == 0;
  }

  /**
   * Marks the start of agent-internal work on the current thread that runs outside a dispatch, such
   * as the reflective URL and pool-name extraction in the HTTP and JDBC advice. While marked, every
   * dispatch returns its fallback, so the agent's own {@code Method.invoke} calls are not evaluated
   * as application reflection. Must be paired with {@link #endInternalCall()} in a {@code finally}
   * block.
   */
  public static void beginInternalCall() {
    DEPTH.get()[0]++;
  }

  /** Ends agent-internal work started with {@link #beginInternalCall()}. */
  public static void endInternalCall() {
    final int[] counters = DEPTH.get();
    if (counters[0] > 0) {
      counters[0]--;
    }
  }

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
  public static ThreadLocal<int[]> depthThreadLocal() {
    return DEPTH;
  }

  /**
   * The agent-classloader-resident delegate ({@code BridgeDelegate}) installed by {@link #install}.
   * Volatile so dispatch methods observe the write without locking. Written after {@link #handles}
   * to uphold the publication invariant described in {@link #install}.
   */
  private static volatile Object delegate;

  /**
   * Pre-built {@link MethodHandle} array of size {@link #HANDLE_COUNT}. Each element is an unbound
   * virtual handle resolved against the {@link BridgeDelegate} interface; dispatch methods pass
   * {@link #delegate} as the first invocation argument. Written before {@code delegate} by {@link
   * #install} so a reader observing a non-null {@code delegate} is guaranteed to also see a
   * complete handles array.
   */
  private static volatile MethodHandle[] handles;

  // ── Phase 1 handles (0-14): concurrency / scheduling interception points ────
  /** Handle index for {@link #decorateExecutorRunnable(String, Object, Runnable)}. */
  public static final int DECORATE_EXECUTOR_RUNNABLE = 0;

  /** Handle index for {@link #decorateExecutorCallable(String, Object, Callable)}. */
  public static final int DECORATE_EXECUTOR_CALLABLE = 1;

  /** Handle index for {@link #beforeThreadStart(Thread)}. */
  public static final int BEFORE_THREAD_START = 2;

  /** Handle index for {@link #beforeWorkerRun(Object, Thread, Runnable)}. */
  public static final int BEFORE_WORKER_RUN = 3;

  /** Handle index for {@link #beforeForkJoinTaskRun(ForkJoinTask)}. */
  public static final int BEFORE_FORK_JOIN_TASK_RUN = 4;

  /** Handle index for {@link #beforeScheduledTick(Object, Object, boolean)}. */
  public static final int BEFORE_SCHEDULED_TICK = 5;

  /** Handle index for {@link #beforeQueueOperation(String, Object)}. */
  public static final int BEFORE_QUEUE_OPERATION = 6;

  /** Handle index for {@link #beforeBooleanQueueOperation(String, Object)}. */
  public static final int BEFORE_BOOLEAN_QUEUE_OPERATION = 7;

  /**
   * Handle index for {@link #beforeCompletableFutureComplete(String, CompletableFuture, Object)}.
   */
  public static final int BEFORE_COMPLETABLE_FUTURE_COMPLETE = 8;

  /** Handle index for {@link #beforeClassLoad(ClassLoader, String)}. */
  public static final int BEFORE_CLASS_LOAD = 9;

  /** Handle index for {@link #afterResourceLookup(ClassLoader, String, java.net.URL)}. */
  public static final int AFTER_RESOURCE_LOOKUP = 10;

  /** Handle index for {@link #decorateShutdownHook(Thread)}. */
  public static final int DECORATE_SHUTDOWN_HOOK = 11;

  /** Handle index for {@link #resolveShutdownHook(Thread)}. */
  public static final int RESOLVE_SHUTDOWN_HOOK = 12;

  /** Handle index for {@link #beforeExecutorShutdown(String, Object, long)}. */
  public static final int BEFORE_EXECUTOR_SHUTDOWN = 13;

  // ── Phase 2 handles (15-45): JVM-level interception points ─────────────────
  /** Handle index for {@link #adjustClockMillis(long)}. */
  public static final int ADJUST_CLOCK_MILLIS = 14;

  /** Handle index for {@link #adjustClockNanos(long)}. */
  public static final int ADJUST_CLOCK_NANOS = 15;

  /** Handle index for {@link #beforeGcRequest()}. */
  public static final int BEFORE_GC_REQUEST = 16;

  /** Handle index for {@link #beforeExitRequest(int)}. */
  public static final int BEFORE_EXIT_REQUEST = 17;

  /** Handle index for {@link #beforeReflectionInvoke(Object, Object)}. */
  public static final int BEFORE_REFLECTION_INVOKE = 18;

  /** Handle index for {@link #beforeDirectBufferAllocate(int)}. */
  public static final int BEFORE_DIRECT_BUFFER_ALLOCATE = 19;

  /** Handle index for {@link #beforeObjectDeserialize(Object)}. */
  public static final int BEFORE_OBJECT_DESERIALIZE = 20;

  /** Handle index for {@link #beforeClassDefine(Object, String)}. */
  public static final int BEFORE_CLASS_DEFINE = 21;

  /** Handle index for {@link #beforeMonitorEnter(Object)}. */
  public static final int BEFORE_MONITOR_ENTER = 22;

  /** Handle index for {@link #beforeThreadPark()}. */
  public static final int BEFORE_THREAD_PARK = 23;

  /** Handle index for {@link #beforeNioSelect(Object, long)}. */
  public static final int BEFORE_NIO_SELECT = 24;

  /** Handle index for {@link #beforeNioChannelOp(String, Object)}. */
  public static final int BEFORE_NIO_CHANNEL_OP = 25;

  /** Handle index for {@link #beforeSocketConnect(Object, Object, int)}. */
  public static final int BEFORE_SOCKET_CONNECT = 26;

  /** Handle index for {@link #beforeSocketAccept(Object)}. */
  public static final int BEFORE_SOCKET_ACCEPT = 27;

  /** Handle index for {@link #beforeSocketRead(Object, int, Object, int, int)}. */
  public static final int BEFORE_SOCKET_READ = 28;

  /** Handle index for {@link #beforeSocketWrite(Object, int, Object, int)}. */
  public static final int BEFORE_SOCKET_WRITE = 29;

  /** Handle index for {@link #beforeSocketClose(Object)}. */
  public static final int BEFORE_SOCKET_CLOSE = 30;

  /** Handle index for {@link #beforeJndiLookup(Object, String)}. */
  public static final int BEFORE_JNDI_LOOKUP = 31;

  /** Handle index for {@link #beforeObjectSerialize(Object, Object)}. */
  public static final int BEFORE_OBJECT_SERIALIZE = 32;

  /** Handle index for {@link #beforeNativeLibraryLoad(String)}. */
  public static final int BEFORE_NATIVE_LIBRARY_LOAD = 33;

  /** Handle index for {@link #beforeAsyncCancel(Object, boolean)}. */
  public static final int BEFORE_ASYNC_CANCEL = 34;

  /** Handle index for {@link #beforeZipInflate()}. */
  public static final int BEFORE_ZIP_INFLATE = 35;

  /** Handle index for {@link #beforeZipDeflate()}. */
  public static final int BEFORE_ZIP_DEFLATE = 36;

  /** Handle index for {@link #beforeThreadLocalGet(Object)}. */
  public static final int BEFORE_THREAD_LOCAL_GET = 37;

  /** Handle index for {@link #beforeThreadLocalSet(Object, Object)}. */
  public static final int BEFORE_THREAD_LOCAL_SET = 38;

  /** Handle index for {@link #beforeJmxInvoke(Object, Object, String)}. */
  public static final int BEFORE_JMX_INVOKE = 39;

  /** Handle index for {@link #beforeJmxGetAttr(Object, Object, String)}. */
  public static final int BEFORE_JMX_GET_ATTR = 40;

  /** Handle index for {@link #adjustInstantNow(java.time.Instant)}. */
  public static final int ADJUST_INSTANT_NOW = 41;

  /** Handle index for {@link #adjustLocalDateTimeNow(java.time.LocalDateTime)}. */
  public static final int ADJUST_LOCAL_DATE_TIME_NOW = 42;

  /** Handle index for {@link #adjustZonedDateTimeNow(java.time.ZonedDateTime)}. */
  public static final int ADJUST_ZONED_DATE_TIME_NOW = 43;

  /** Handle index for {@link #adjustDateNew(long)}. */
  public static final int ADJUST_DATE_NEW = 44;

  /** Handle index for {@link #beforeHttpSend(String)}. */
  public static final int BEFORE_HTTP_SEND = 45;

  /** Handle index for {@link #beforeHttpSendAsync(String)}. */
  public static final int BEFORE_HTTP_SEND_ASYNC = 46;

  /** Handle index for {@link #beforeJdbcConnectionAcquire(String)}. */
  public static final int BEFORE_JDBC_CONNECTION_ACQUIRE = 47;

  /** Handle index for {@link #beforeJdbcStatementExecute(String)}. */
  public static final int BEFORE_JDBC_STATEMENT_EXECUTE = 48;

  /** Handle index for {@link #beforeJdbcPreparedStatement(String)}. */
  public static final int BEFORE_JDBC_PREPARED_STATEMENT = 49;

  /** Handle index for {@link #beforeJdbcTransactionCommit()}. */
  public static final int BEFORE_JDBC_TRANSACTION_COMMIT = 50;

  /** Handle index for {@link #beforeJdbcTransactionRollback()}. */
  public static final int BEFORE_JDBC_TRANSACTION_ROLLBACK = 51;

  // ── Phase 2 handles (53-56) ───────────────────────────────────────────────
  /** Handle index for {@link #beforeThreadSleep(long)}. */
  public static final int BEFORE_THREAD_SLEEP = 52;

  /** Handle index for {@link #beforeDnsResolve(String)}. */
  public static final int BEFORE_DNS_RESOLVE = 53;

  /** Handle index for {@link #beforeSslHandshake(Object)}. */
  public static final int BEFORE_SSL_HANDSHAKE = 54;

  /** Handle index for {@link #beforeFileIo(String, Object, String, int)}. */
  public static final int BEFORE_FILE_IO = 55;

  /**
   * Handle index for {@link #adjustLocalDateTimeNowInZone(java.time.LocalDateTime,
   * java.time.ZoneId)}.
   */
  public static final int ADJUST_LOCAL_DATE_TIME_NOW_IN_ZONE = 56;

  /** Handle index for {@link #beforeMethodEnter(Class, String, String)}. */
  public static final int BEFORE_METHOD_ENTER = 57;

  /** Handle index for {@link #afterMethodExit(Class, String, String, Object)}. */
  public static final int AFTER_METHOD_EXIT = 58;

  /** Handle index for {@link #beforeClientCall(String, Object, String, String, Object)}. */
  public static final int BEFORE_CLIENT_CALL = 59;

  /** Handle index for {@link #afterDnsAnswer(String, java.net.InetAddress[], Throwable)}. */
  public static final int AFTER_DNS_ANSWER = 60;

  /** Handle index for {@link #captureSession(Object)}. */
  public static final int CAPTURE_SESSION = 61;

  /** Handle index for {@link #enterSession(Object)}. */
  public static final int ENTER_SESSION = 62;

  /** Handle index for {@link #exitSession(Object)}. */
  public static final int EXIT_SESSION = 63;

  /** Handle index for {@link #beforeNioChannelWrite(Object, int)}. */
  public static final int BEFORE_NIO_CHANNEL_WRITE = 64;

  /** Handle index for {@link #beforeProcessStart(String)}. */
  public static final int BEFORE_PROCESS_START = 65;

  /** Handle index for {@link #currentSessionId()}. */
  public static final int CURRENT_SESSION_ID = 66;

  /** Total number of method-handle slots; equals the highest index plus one. */
  public static final int HANDLE_COUNT = 67;

  /** The header that carries a session id between services; {@code ChaosSession.HEADER}. */
  public static final String SESSION_HEADER = "X-Chaos-Session";

  /** Keeps every header when the HTTP client advice copies a request to add the session header. */
  public static final java.util.function.BiPredicate<String, String> ALL_HEADERS =
      (name, value) -> true;

  /** Utility class; do not instantiate. */
  private BootstrapDispatcher() {}

  /**
   * Wires the delegate and its pre-built method handles into this dispatcher.
   *
   * <p>Must be called exactly once, by the agent classloader, after this class has been loaded into
   * the bootstrap classloader. The write order — handles before delegate — is intentional: a racing
   * reader that snapshots a non-null {@code delegate} is guaranteed to also see a non-null {@code
   * handles} array.
   *
   * @param bridgeDelegate the {@link com.macstab.chaos.jvm.instrumentation.bridge.BridgeDelegate}
   *     instance from the agent classloader; passed as {@code Object} to avoid a {@code
   *     ClassNotFoundException} in bootstrap code
   * @param methodHandles array of exactly {@link #HANDLE_COUNT} {@link
   *     java.lang.invoke.MethodHandle} instances, indexed by the public integer constants defined
   *     on this class; each handle is an unbound virtual handle resolved against {@link
   *     BridgeDelegate}, and dispatch calls pass {@code bridgeDelegate} as the first argument on
   *     every invocation
   */
  public static synchronized void install(
      final Object bridgeDelegate, final MethodHandle[] methodHandles) {
    // Serialise concurrent install() calls so the (handles, delegate) pair is never torn across
    // two competing installers. Without this lock, thread A's handles = X followed by
    // thread B's delegate = Y2 could leave an advice thread observing `handles` from install A
    // bound against `delegate` from install B, producing method-handle invocations with the
    // wrong receiver and a hard classloader mismatch. A single-writer discipline under the
    // class monitor removes the interleaving; the fields remain volatile so advice-path readers
    // still see the final pair without needing the lock. install(null, null) remains supported
    // as an explicit uninstall — callers (tests, per-classloader teardown) rely on it.
    handles = methodHandles;
    delegate = bridgeDelegate;
  }

  /**
   * Returns the delegate wired by {@link #install}, or {@code null} when none is installed. Used by
   * the installer to detect a second copy of the agent, loaded by another class loader, before it
   * replaces the first copy's delegate: the dispatcher is JVM-wide (bootstrap-loaded) while each
   * agent copy has its own runtime.
   *
   * @return the installed delegate, or {@code null}
   */
  public static Object installedDelegate() {
    return delegate;
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return task;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? task
          : (Runnable) h[DECORATE_EXECUTOR_RUNNABLE].invoke(d, operation, executor, task);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return task;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return task;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d == null || h == null) return task;
      @SuppressWarnings("unchecked")
      Callable<T> result =
          (Callable<T>) h[DECORATE_EXECUTOR_CALLABLE].invoke(d, operation, executor, task);
      return result;
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return task;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        // Pool workers (created inside addWorker, or fork-join workers) serve many tasks, each
        // carrying its submitter's session; the worker itself must not inherit the starter's.
        final boolean poolWorker =
            depth[SCOPE_POOL_WORKER] > 0
                || thread instanceof java.util.concurrent.ForkJoinWorkerThread;
        h[BEFORE_THREAD_START].invoke(d, thread, poolWorker);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_WORKER_RUN].invoke(d, executor, worker, task);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a {@link java.util.concurrent.ForkJoinTask} begins execution on a {@link
   * java.util.concurrent.ForkJoinPool} worker.
   *
   * @param task the fork-join task about to execute; never {@code null}
   * @throws Throwable if an active scenario injects an exception
   */
  public static void beforeForkJoinTaskRun(final ForkJoinTask<?> task) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_FORK_JOIN_TASK_RUN].invoke(d, task);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return true;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          || (boolean) h[BEFORE_SCHEDULED_TICK].invoke(d, executor, task, periodic);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return true;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_QUEUE_OPERATION].invoke(d, operation, queue);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return null;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? null
          : (Boolean) h[BEFORE_BOOLEAN_QUEUE_OPERATION].invoke(d, operation, queue);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return null;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return null;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? null
          : (Boolean) h[BEFORE_COMPLETABLE_FUTURE_COMPLETE].invoke(d, operation, future, payload);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return null;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_CLASS_LOAD].invoke(d, loader, className);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return currentValue;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? currentValue
          : (URL) h[AFTER_RESOURCE_LOOKUP].invoke(d, loader, name, currentValue);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return currentValue;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return hook;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null) ? hook : (Thread) h[DECORATE_SHUTDOWN_HOOK].invoke(d, hook);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return hook;
    } finally {
      depth[0]--;
    }
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
    // Use a safe (non-sneaky-throwing) fallback path: any chaos exception here would propagate
    // out of Runtime.removeShutdownHook() to the application, which never expects it. Return
    // the original hook unmodified on any error so the JVM shutdown sequencing is unaffected.
    try {
      final int[] depth = DEPTH.get();
      if (depth[0] > 0) {
        return original;
      }
      depth[0]++;
      try {
        final MethodHandle[] h = handles;
        final Object d = delegate;
        return (d == null || h == null)
            ? original
            : (Thread) h[RESOLVE_SHUTDOWN_HOOK].invoke(d, original);
      } finally {
        depth[0]--;
      }
    } catch (final Throwable ignored) {
      return original;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_EXECUTOR_SHUTDOWN].invoke(d, operation, executor, timeoutMillis);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return realMillis;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? realMillis
          : (long) h[ADJUST_CLOCK_MILLIS].invoke(d, realMillis);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return realMillis;
    } finally {
      depth[0]--;
    }
  }

  /**
   * {@link System#currentTimeMillis()} through the clock-skew dispatch: the replacement woven into
   * application call sites when clock call-site weaving is on ({@code clockCallSites}). The JIT
   * intrinsic cannot be advised itself; its callers can.
   *
   * @return the possibly skewed wall-clock time
   * @throws Throwable if the delegate throws
   */
  public static long currentTimeMillis() throws Throwable {
    return adjustClockMillis(System.currentTimeMillis());
  }

  /**
   * {@link System#nanoTime()} through the clock-skew dispatch; see {@link #currentTimeMillis()}.
   *
   * @return the possibly skewed monotonic time
   * @throws Throwable if the delegate throws
   */
  public static long nanoTime() throws Throwable {
    return adjustClockNanos(System.nanoTime());
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return realNanos;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? realNanos
          : (long) h[ADJUST_CLOCK_NANOS].invoke(d, realNanos);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return realNanos;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return realInstant;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? realInstant
          : (java.time.Instant) h[ADJUST_INSTANT_NOW].invoke(d, realInstant);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return realInstant;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Rewrites the result of {@code LocalDateTime.now(ZoneId)} for clock skew; the zone is needed to
   * map the local date-time to an instant and back.
   *
   * @param realValue the real local date-time
   * @param zone the zone {@code now} was called with
   * @return the (possibly skewed) local date-time; {@code realValue} as the fallback
   * @throws Throwable if the delegate throws
   */
  public static java.time.LocalDateTime adjustLocalDateTimeNowInZone(
      final java.time.LocalDateTime realValue, final java.time.ZoneId zone) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return realValue;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? realValue
          : (java.time.LocalDateTime)
              h[ADJUST_LOCAL_DATE_TIME_NOW_IN_ZONE].invoke(d, realValue, zone);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return realValue;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return realValue;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? realValue
          : (java.time.LocalDateTime) h[ADJUST_LOCAL_DATE_TIME_NOW].invoke(d, realValue);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return realValue;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return realValue;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? realValue
          : (java.time.ZonedDateTime) h[ADJUST_ZONED_DATE_TIME_NOW].invoke(d, realValue);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return realValue;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return realMillis;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? realMillis
          : (long) h[ADJUST_DATE_NEW].invoke(d, realMillis);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return realMillis;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null) && (boolean) h[BEFORE_GC_REQUEST].invoke(d);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_EXIT_REQUEST].invoke(d, status);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_REFLECTION_INVOKE].invoke(d, method, target);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before {@link java.nio.ByteBuffer#allocateDirect(int)}.
   *
   * @param capacity the number of bytes requested for the direct buffer; non-negative
   * @throws Throwable if an active scenario wants to simulate an {@link OutOfMemoryError} or
   *     another allocation failure
   */
  public static void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_DIRECT_BUFFER_ALLOCATE].invoke(d, capacity);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_OBJECT_DESERIALIZE].invoke(d, stream);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_CLASS_DEFINE].invoke(d, loader, className);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before {@code AbstractQueuedSynchronizer.acquire(int)} as a proxy for monitor-enter
   * contention.
   *
   * <p>An active scenario may inject a delay to simulate lock contention.
   *
   * @param lock the monitor object whose acquisition is about to occur
   * @throws Throwable if an active scenario injects an exception
   */
  public static void beforeMonitorEnter(final Object lock) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_MONITOR_ENTER].invoke(d, lock);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before {@code LockSupport.park(Object)}, {@code parkNanos}, or {@code parkUntil}.
   *
   * <p>An active scenario may inject a delay before the park call to simulate scheduler pressure.
   *
   * @throws Throwable if an active scenario injects an exception
   */
  public static void beforeThreadPark() throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_THREAD_PARK].invoke(d);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null)
          && (boolean) h[BEFORE_NIO_SELECT].invoke(d, selector, timeoutMillis);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_NIO_CHANNEL_OP].invoke(d, operation, channel);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_SOCKET_CONNECT].invoke(d, socket, socketAddress, timeoutMillis);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before {@link java.net.ServerSocket#accept()}.
   *
   * @param serverSocket the server socket about to block waiting for a connection; never {@code
   *     null}
   * @throws Throwable if an active scenario injects a failure to simulate an accept error
   */
  public static void beforeSocketAccept(final Object serverSocket) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_SOCKET_ACCEPT].invoke(d, serverSocket);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a socket input-stream read operation.
   *
   * @param stream the socket implementation being read from; never {@code null}
   * @param len the number of bytes requested
   * @param remoteAddress the peer address ({@code java.net.InetAddress}); {@code null} when the
   *     socket is not connected
   * @param remotePort the peer port
   * @param soTimeoutMillis the socket's {@code SO_TIMEOUT}; {@code 0} for infinite
   * @return the byte count to read: {@code len}, lower under a trickle, or {@code -(count) - 1}
   *     when the bytes read must be corrupted (see {@link #corruptBytes})
   * @throws Throwable if an active scenario injects a failure to simulate a read error or timeout
   */
  public static int beforeSocketRead(
      final Object stream,
      final int len,
      final Object remoteAddress,
      final int remotePort,
      final int soTimeoutMillis)
      throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return len;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? len
          : (int)
              h[BEFORE_SOCKET_READ].invoke(
                  d, stream, len, remoteAddress, remotePort, soTimeoutMillis);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return len;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a socket output-stream write operation.
   *
   * @param stream the socket {@link java.io.OutputStream}; never {@code null}
   * @param len the number of bytes about to be written
   * @param remoteAddress the peer address ({@code java.net.InetAddress}); {@code null} when the
   *     socket is not connected
   * @param remotePort the peer port
   * @return the bytes to write: {@code len}, or a shorter prefix under a torn write, after which
   *     the advice throws {@link #tornWriteFailure(int, int)}
   * @throws Throwable if an active scenario injects a failure to simulate a write error
   */
  public static int beforeSocketWrite(
      final Object stream, final int len, final Object remoteAddress, final int remotePort)
      throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return len;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? len
          : (int) h[BEFORE_SOCKET_WRITE].invoke(d, stream, len, remoteAddress, remotePort);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return len;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before {@link java.nio.channels.SocketChannel#write(java.nio.ByteBuffer)}.
   *
   * @param channel the channel being written to
   * @param remaining the bytes the buffer holds
   * @return the bytes to write: {@code remaining}, or fewer under a torn write
   * @throws Throwable if an active scenario injects a failure to simulate a write error
   */
  public static int beforeNioChannelWrite(final Object channel, final int remaining)
      throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return remaining;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? remaining
          : (int) h[BEFORE_NIO_CHANNEL_WRITE].invoke(d, channel, remaining);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return remaining;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a child process is started.
   *
   * @param command the program, the first element of the command
   * @throws Throwable if an active scenario fails the start
   */
  public static void beforeProcessStart(final String command) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_PROCESS_START].invoke(d, command);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Returns the session id to send as {@link #SESSION_HEADER} on a request to {@code host}: the
   * calling thread's session when {@link SessionHeaderPolicy} allows the host, else {@code null}.
   *
   * @param host the request's host
   * @return the session id, or {@code null} when nothing must be sent
   */
  public static String sessionHeaderFor(final String host) {
    return SessionHeaderPolicy.allows(host) ? currentSessionId() : null;
  }

  /**
   * Returns the id of the session the calling thread is bound to.
   *
   * @return the session id, or {@code null} outside any session, inside the agent or before the
   *     agent is installed
   */
  public static String currentSessionId() {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return null;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null) ? null : (String) h[CURRENT_SESSION_ID].invoke(d);
    } catch (final Throwable throwable) {
      return null;
    } finally {
      depth[0]--;
    }
  }

  /**
   * The failure of a torn socket-stream write: the prefix was sent, then the connection was reset.
   * A stream write cannot return short, so this is how the peer and the writer see it.
   *
   * @param written the bytes sent before the failure
   * @param requested the bytes the caller asked to write
   * @return the exception for the advice to throw
   */
  public static java.net.SocketException tornWriteFailure(final int written, final int requested) {
    return new java.net.SocketException(
        "Connection reset (chaos: torn write, " + written + " of " + requested + " bytes sent)");
  }

  /**
   * Called before {@link java.net.Socket#close()}.
   *
   * @param socket the socket being closed; never {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate a close error
   */
  public static void beforeSocketClose(final Object socket) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_SOCKET_CLOSE].invoke(d, socket);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_JNDI_LOOKUP].invoke(d, context, name);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_OBJECT_SERIALIZE].invoke(d, stream, obj);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_NATIVE_LIBRARY_LOAD].invoke(d, libraryName);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null)
          && (boolean) h[BEFORE_ASYNC_CANCEL].invoke(d, future, mayInterruptIfRunning);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before {@code Inflater.inflate(...)}.
   *
   * <p>An active scenario may inject a delay to simulate slow decompression under load.
   *
   * @throws Throwable if an active scenario injects an exception
   */
  public static void beforeZipInflate() throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_ZIP_INFLATE].invoke(d);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before {@code Deflater.deflate(...)}.
   *
   * <p>An active scenario may inject a delay to simulate slow compression under load.
   *
   * @throws Throwable if an active scenario injects an exception
   */
  public static void beforeZipDeflate() throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_ZIP_DEFLATE].invoke(d);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null)
          && (boolean) h[BEFORE_THREAD_LOCAL_GET].invoke(d, threadLocal);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null)
          && (boolean) h[BEFORE_THREAD_LOCAL_SET].invoke(d, threadLocal, value);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_JMX_INVOKE].invoke(d, server, objectName, operationName);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_JMX_GET_ATTR].invoke(d, server, objectName, attribute);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a synchronous HTTP client {@code send} (Java {@code HttpClient.send}, {@code
   * okhttp3.RealCall.execute}, Apache HttpComponents {@code CloseableHttpClient.execute}).
   *
   * <p>Returning {@code true} causes the advice to skip the real send call. The advice is
   * responsible for throwing {@link com.macstab.chaos.jvm.api.ChaosHttpSuppressException} when the
   * call is suppressed so the caller observes a terminal failure instead of a dropped request.
   *
   * @param url the request URL in {@code scheme://host/path} form; may be {@code null} when the
   *     advice cannot extract a URL
   * @return {@code true} if the send should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeHttpSend(final String url) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null) && (boolean) h[BEFORE_HTTP_SEND].invoke(d, url);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null) && (boolean) h[BEFORE_HTTP_SEND_ASYNC].invoke(d, url);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a JDBC connection is acquired from a pool (HikariCP, c3p0).
   *
   * <p>Returning {@code true} causes the advice to throw {@link
   * com.macstab.chaos.jvm.api.ChaosJdbcSuppressException}.
   *
   * @param poolName the pool identifier; may be {@code null}
   * @return {@code true} if acquisition should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null)
          && (boolean) h[BEFORE_JDBC_CONNECTION_ACQUIRE].invoke(d, poolName);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null) && (boolean) h[BEFORE_JDBC_STATEMENT_EXECUTE].invoke(d, sql);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null) && (boolean) h[BEFORE_JDBC_PREPARED_STATEMENT].invoke(d, sql);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a {@link java.sql.Connection#commit()} call.
   *
   * @return {@code true} if the commit should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeJdbcTransactionCommit() throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null) && (boolean) h[BEFORE_JDBC_TRANSACTION_COMMIT].invoke(d);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a {@link java.sql.Connection#rollback()} call.
   *
   * @return {@code true} if the rollback should be suppressed; {@code false} for normal execution.
   *     Returns {@code false} as the fallback.
   * @throws Throwable if the delegate throws
   */
  public static boolean beforeJdbcTransactionRollback() throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null) && (boolean) h[BEFORE_JDBC_TRANSACTION_ROLLBACK].invoke(d);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return false;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d != null && h != null) && (boolean) h[BEFORE_THREAD_SLEEP].invoke(d, millis);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return false;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before {@link java.net.InetAddress#getByName(String)}, {@link
   * java.net.InetAddress#getAllByName(String)}, or {@link java.net.InetAddress#getLocalHost()}.
   *
   * @param hostname the hostname being resolved; {@code null} for {@code getLocalHost()}
   * @return the name to resolve: {@code hostname}, or another under a {@code DnsRewriteEffect}
   * @throws Throwable if an active scenario injects an exception to simulate a DNS failure
   */
  public static String beforeDnsResolve(final String hostname) throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return hostname;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? hostname
          : (String) h[BEFORE_DNS_RESOLVE].invoke(d, hostname);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return hostname;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called when {@link java.net.InetAddress#getAllByName(String)} returns or fails.
   *
   * @param hostname the name that was looked up
   * @param answer the resolver's answer, or {@code null} when it failed
   * @param failure the lookup's failure, or {@code null}
   * @return the answer to return instead, or {@code null} to keep the original outcome
   * @throws Throwable if an active scenario rejects the lookup
   */
  public static java.net.InetAddress[] afterDnsAnswer(
      final String hostname, final java.net.InetAddress[] answer, final Throwable failure)
      throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return null;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? null
          : (java.net.InetAddress[]) h[AFTER_DNS_ANSWER].invoke(d, hostname, answer, failure);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return null;
    } finally {
      depth[0]--;
    }
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
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_SSL_HANDSHAKE].invoke(d, socket);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a {@link java.io.FileInputStream#read} or {@link java.io.FileOutputStream#write}
   * call.
   *
   * @param operation {@code "FILE_IO_READ"} or {@code "FILE_IO_WRITE"}; never {@code null}
   * @param stream the {@code FileInputStream} or {@code FileOutputStream} instance; never {@code
   *     null}
   * @param path the path the stream was opened with, or {@code null}
   * @param byteCount the bytes read (requested) or written by the call
   * @return {@code byteCount}, or {@code -(byteCount) - 1} when a read's bytes must be corrupted
   * @throws Throwable if an active scenario injects an exception to simulate an I/O failure
   */
  public static int beforeFileIo(
      final String operation, final Object stream, final String path, final int byteCount)
      throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return byteCount;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? byteCount
          : (int) h[BEFORE_FILE_IO].invoke(d, operation, stream, path, byteCount);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return byteCount;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Flips one random bit in {@code count} bytes of {@code buffer} starting at {@code offset}: the
   * corruption a {@code CorruptReadEffect} applies to the bytes a read returned. Called by the read
   * advice after the real read; makes no dispatch.
   *
   * @param buffer the buffer the read filled
   * @param offset where the read started writing
   * @param count the number of bytes the read returned; nothing happens when {@code <= 0}
   */
  public static void corruptBytes(final byte[] buffer, final int offset, final int count) {
    if (buffer == null || count <= 0) {
      return;
    }
    final java.util.concurrent.ThreadLocalRandom random =
        java.util.concurrent.ThreadLocalRandom.current();
    final int index = offset + random.nextInt(count);
    buffer[index] = (byte) (buffer[index] ^ (1 << random.nextInt(8)));
  }

  /**
   * Whether {@code path} is an archive the JVM reads classes from ({@code .jar}, {@code .zip},
   * {@code .jmod}). {@code ZipFile} reads such archives through a shared {@code RandomAccessFile}:
   * it seeks, then reads. A dispatch between the two can load classes from the same archive, which
   * moves the file pointer and makes the outer read return the wrong bytes. Archive reads are class
   * loading, so they are not evaluated at all.
   *
   * @param path the file path, or {@code null}
   * @return {@code true} for an archive
   */
  public static boolean isArchive(final String path) {
    return path != null
        && (path.endsWith(".jar") || path.endsWith(".zip") || path.endsWith(".jmod"));
  }

  /**
   * Flips one random bit in the {@code count} bytes a channel read just put into {@code buffer}
   * (the bytes before its position).
   *
   * @param buffer the buffer the read filled
   * @param count the number of bytes the read returned; nothing happens when {@code <= 0}
   */
  public static void corruptBuffer(final java.nio.ByteBuffer buffer, final int count) {
    if (buffer == null || count <= 0 || buffer.isReadOnly() || count > buffer.position()) {
      return;
    }
    final java.util.concurrent.ThreadLocalRandom random =
        java.util.concurrent.ThreadLocalRandom.current();
    final int index = buffer.position() - count + random.nextInt(count);
    buffer.put(index, (byte) (buffer.get(index) ^ (1 << random.nextInt(8))));
  }

  /**
   * Flips one random bit of a byte returned by a single-byte {@code read()}.
   *
   * @param value the value the read returned
   * @return the corrupted value, or {@code value} at end of stream ({@code -1})
   */
  public static int corruptByte(final int value) {
    return value < 0
        ? value
        : (value ^ (1 << java.util.concurrent.ThreadLocalRandom.current().nextInt(8))) & 0xFF;
  }

  /**
   * Called on entry to a method woven for a {@code MethodSelector}.
   *
   * @param declaringType the class declaring the method
   * @param methodName the method name
   * @param descriptor the method's JVM descriptor
   * @throws Throwable if an active scenario injects an exception
   */
  public static void beforeMethodEnter(
      final Class<?> declaringType, final String methodName, final String descriptor)
      throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_METHOD_ENTER].invoke(d, declaringType, methodName, descriptor);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called on the normal exit of a method woven for a {@code MethodSelector}.
   *
   * @param declaringType the class declaring the method
   * @param methodName the method name
   * @param descriptor the method's JVM descriptor
   * @param value the returned value, boxed; {@code null} for a void method
   * @return the (possibly corrupted) value to return; {@code value} as the fallback
   * @throws Throwable if an active scenario fails the call
   */
  public static Object afterMethodExit(
      final Class<?> declaringType,
      final String methodName,
      final String descriptor,
      final Object value)
      throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return value;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      return (d == null || h == null)
          ? value
          : h[AFTER_METHOD_EXIT].invoke(d, declaringType, methodName, descriptor, value);
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
      return value;
    } finally {
      depth[0]--;
    }
  }

  /**
   * Called before a call through a client library (Kafka, gRPC, Redis, R2DBC, AWS SDK, Netty).
   *
   * @param operation the {@code OperationType} name
   * @param client the client object
   * @param target the topic(s), method, command or AWS operation, or {@code null}
   * @param detail the AWS service, or {@code null}
   * @param remoteAddress the peer address of a Netty channel, or {@code null}
   * @throws Throwable if an active scenario rejects the call
   */
  public static void beforeClientCall(
      final String operation,
      final Object client,
      final String target,
      final String detail,
      final Object remoteAddress)
      throws Throwable {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[BEFORE_CLIENT_CALL].invoke(d, operation, client, target, detail, remoteAddress);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Records the current session for a fork-join task being submitted or forked.
   *
   * @param carrier the task or thread
   */
  public static void captureSession(final Object carrier) {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[CAPTURE_SESSION].invoke(d, carrier);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Binds the session handed to a task or thread that starts running.
   *
   * @param carrier the task or thread
   */
  public static void enterSession(final Object carrier) {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[ENTER_SESSION].invoke(d, carrier);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  /**
   * Releases the session bound by {@link #enterSession(Object)}.
   *
   * @param carrier the task or thread
   */
  public static void exitSession(final Object carrier) {
    final int[] depth = DEPTH.get();
    if (depth[0] > 0) {
      return;
    }
    depth[0]++;
    try {
      final MethodHandle[] h = handles;
      final Object d = delegate;
      if (d != null && h != null) {
        h[EXIT_SESSION].invoke(d, carrier);
      }
    } catch (final Throwable throwable) {
      sneakyThrow(throwable);
    } finally {
      depth[0]--;
    }
  }

  // ── Internal helpers ───────────────────────────────────────────────────────

  /*
   * Every dispatch method guards itself inline: it returns its fallback when DEPTH[0] > 0 (the
   * thread is already inside a dispatch), otherwise increments DEPTH[0] (++, never = 1, so the
   * counter stays a pure balance), invokes the delegate handle and decrements in finally. The
   * array is kept when the depth returns to zero, because it also holds the call-depth scope
   * counters of enclosing instrumented calls. The guard is inlined rather than shared through a
   * Supplier-taking trampoline: that trampoline's call site saw every dispatch's lambda, so the
   * JIT could not inline it and every intercepted call allocated a capturing lambda.
   */

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
}

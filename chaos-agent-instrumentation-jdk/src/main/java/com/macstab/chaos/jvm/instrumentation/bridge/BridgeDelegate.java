package com.macstab.chaos.jvm.instrumentation.bridge;

import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

/**
 * Interface implemented by the agent-classloader side of the bootstrap-to-agent classloader bridge.
 *
 * <h2>Bridge pattern</h2>
 *
 * <p>{@link BootstrapDispatcher} is loaded into the bootstrap classloader so that ByteBuddy {@link
 * net.bytebuddy.asm.Advice @Advice} classes woven into JDK code can call it directly. However, the
 * full chaos implementation ({@code ChaosRuntime}) lives in the agent classloader and is invisible
 * to bootstrap code by name.
 *
 * <p>The bridge is crossed at runtime via a {@link java.lang.invoke.MethodHandle} array: at agent
 * startup, an implementation of this interface ({@link
 * com.macstab.chaos.jvm.instrumentation.ChaosBridge}) is constructed in the agent classloader and
 * its methods are resolved into unbound virtual handles against this interface. Those handles —
 * plus the delegate instance itself, typed as {@code Object} — are handed to {@link
 * BootstrapDispatcher#install(Object, java.lang.invoke.MethodHandle[])}, and each dispatch call
 * supplies the delegate as the first argument so the bootstrap dispatcher can call through to agent
 * code without any classloader visibility requirement.
 *
 * <h2>Method mapping</h2>
 *
 * <p>Every method on this interface corresponds one-to-one with a public static dispatch method on
 * {@link BootstrapDispatcher} and an integer handle-index constant (e.g. {@link
 * BootstrapDispatcher#DECORATE_EXECUTOR_RUNNABLE}). The method signatures, semantics, and fallback
 * contracts are identical to those documented on {@code BootstrapDispatcher}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Implementations must be fully thread-safe. All methods may be called concurrently from
 * multiple threads, including JDK internal threads that the agent does not control.
 */
public interface BridgeDelegate {

  // ── Phase 1 methods ────────────────────────────────────────────────────────

  /**
   * Gives the active chaos scenario an opportunity to wrap the submitted runnable before it is
   * handed to the executor.
   *
   * @param operation a string tag identifying the submission call site (e.g. {@code "execute"},
   *     {@code "submit"}); never {@code null}
   * @param executor the {@link java.util.concurrent.Executor} that will run the task; may be {@code
   *     null} when the calling context is not an executor subclass
   * @param task the runnable to potentially wrap; never {@code null}
   * @return the (possibly wrapped) runnable; equals {@code task} when no active scenario applies
   * @throws Throwable if the delegate throws
   */
  Runnable decorateExecutorRunnable(String operation, Object executor, Runnable task)
      throws Throwable;

  /**
   * Gives the active chaos scenario an opportunity to wrap the submitted callable before it is
   * handed to the executor.
   *
   * @param <T> the callable's return type
   * @param operation a string tag identifying the submission call site; never {@code null}
   * @param executor the executor that will run the task; may be {@code null}
   * @param task the callable to potentially wrap; never {@code null}
   * @return the (possibly wrapped) callable; equals {@code task} when no active scenario applies
   * @throws Throwable if the delegate throws
   */
  <T> Callable<T> decorateExecutorCallable(String operation, Object executor, Callable<T> task)
      throws Throwable;

  /**
   * Called immediately before {@link Thread#start()} transfers the thread to the OS scheduler.
   *
   * <p>An active scenario may inject a delay, throw to block the start, or record the event for
   * observability.
   *
   * @param thread the thread about to be started; never {@code null}
   * @throws Throwable if an active scenario injects an exception to suppress the start
   */
  void beforeThreadStart(Thread thread) throws Throwable;

  /**
   * Called at the top of a thread-pool worker's run loop, before the next task is dequeued.
   *
   * @param executor the thread pool owning this worker; may be {@code null}
   * @param worker the worker thread currently executing; never {@code null}
   * @param task the task about to run, when available from the framework; may be {@code null}
   * @throws Throwable if an active scenario injects an exception to stall or disrupt the worker
   */
  void beforeWorkerRun(Object executor, Thread worker, Runnable task) throws Throwable;

  /**
   * Called before a {@link java.util.concurrent.ForkJoinTask} begins execution on a {@link
   * java.util.concurrent.ForkJoinPool} worker.
   *
   * @param task the fork-join task about to execute; never {@code null}
   * @throws Throwable if an active scenario injects an exception
   */
  void beforeForkJoinTaskRun(ForkJoinTask<?> task) throws Throwable;

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
   * @return the (possibly modified) delay in milliseconds
   * @throws Throwable if the delegate throws
   */
  long adjustScheduleDelay(
      String operation, Object executor, Object task, long delay, boolean periodic)
      throws Throwable;

  /**
   * Called before each execution of a scheduled task (both one-shot and periodic).
   *
   * @param executor the scheduled executor owning the task; may be {@code null}
   * @param task the task about to fire; may be {@code null}
   * @param periodic {@code true} if the task repeats
   * @return {@code true} if the task should execute normally; {@code false} if an active SUPPRESS
   *     scenario has vetoed this tick
   * @throws Throwable if the delegate throws
   */
  boolean beforeScheduledTick(Object executor, Object task, boolean periodic) throws Throwable;

  /**
   * Called before a blocking-queue operation such as {@code put}, {@code take}, or {@code offer}.
   *
   * @param operation a string tag identifying the queue method (e.g. {@code "put"}, {@code
   *     "take"}); never {@code null}
   * @param queue the queue instance; may be {@code null} if not available from the advice context
   * @throws Throwable if an active scenario injects an exception or decides to throw
   */
  void beforeQueueOperation(String operation, Object queue) throws Throwable;

  /**
   * Called before a boolean-returning queue operation such as {@code offer(e, timeout, unit)}.
   *
   * @param operation a string tag identifying the queue method; never {@code null}
   * @param queue the queue instance; may be {@code null}
   * @return {@code Boolean.TRUE} to force the method to return {@code true} (SUPPRESS), {@code
   *     Boolean.FALSE} to force {@code false}, or {@code null} to let the original call proceed
   * @throws Throwable if the delegate throws
   */
  Boolean beforeBooleanQueueOperation(String operation, Object queue) throws Throwable;

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
   *     the real completion, or {@code null} to let the original call proceed
   * @throws Throwable if the delegate throws
   */
  Boolean beforeCompletableFutureComplete(
      String operation, CompletableFuture<?> future, Object payload) throws Throwable;

  /**
   * Called inside {@link ClassLoader#loadClass(String)} before class resolution begins.
   *
   * @param loader the classloader being asked to load the class; never {@code null}
   * @param className the binary name of the class (e.g. {@code "com.example.Foo"}); never {@code
   *     null}
   * @throws Throwable if an active scenario wants to simulate a {@link ClassNotFoundException}
   */
  void beforeClassLoad(ClassLoader loader, String className) throws Throwable;

  /**
   * Called after {@link ClassLoader#getResource(String)} returns, allowing a scenario to substitute
   * or nullify the resolved URL.
   *
   * @param loader the classloader that performed the lookup; never {@code null}
   * @param name the resource name as passed to {@code getResource}; never {@code null}
   * @param currentValue the URL returned by the real lookup, or {@code null} if the resource was
   *     not found
   * @return the URL that should be returned to the caller; may differ from {@code currentValue}
   *     when an active scenario is replacing or suppressing the resource
   * @throws Throwable if the delegate throws
   */
  URL afterResourceLookup(ClassLoader loader, String name, URL currentValue) throws Throwable;

  /**
   * Wraps a shutdown hook thread before it is registered with the JVM, allowing a scenario to track
   * or intercept JVM shutdown.
   *
   * @param hook the shutdown-hook thread submitted by the application; never {@code null}
   * @return the (possibly wrapped) thread to register
   * @throws Throwable if the delegate throws
   */
  Thread decorateShutdownHook(Thread hook) throws Throwable;

  /**
   * Resolves the original application hook from a previously wrapped hook thread.
   *
   * <p>Called when the JVM removes a shutdown hook (e.g. via {@code Runtime.removeShutdownHook}),
   * so that the lookup key matches the wrapper registered by {@link #decorateShutdownHook}.
   * Non-throwing: {@code RemoveShutdownHookAdvice} does not declare {@code throws Throwable}.
   *
   * @param original the thread passed to {@code removeShutdownHook}; never {@code null}
   * @return the registered wrapper thread, or {@code original} if no wrapping occurred
   */
  // Non-throwing: resolveShutdownHook is called from RemoveShutdownHookAdvice which does not
  // declare throws Throwable.
  Thread resolveShutdownHook(Thread original);

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
  void beforeExecutorShutdown(String operation, Object executor, long timeoutMillis)
      throws Throwable;

  // ── Phase 2 methods ────────────────────────────────────────────────────────

  /**
   * Returns the chaos-adjusted wall-clock time in milliseconds.
   *
   * @param realMillis the value returned by the real {@code System.currentTimeMillis()} call
   * @return the (possibly skewed) millisecond timestamp
   * @throws Throwable if the delegate throws
   */
  long adjustClockMillis(long realMillis) throws Throwable;

  /**
   * Returns the chaos-adjusted monotonic time in nanoseconds.
   *
   * @param realNanos the value returned by the real {@code System.nanoTime()} call
   * @return the (possibly skewed) nanosecond timestamp
   * @throws Throwable if the delegate throws
   */
  long adjustClockNanos(long realNanos) throws Throwable;

  /**
   * Returns the chaos-adjusted {@link java.time.Instant} for {@code Instant.now()} interception.
   *
   * @param realInstant the real value returned by {@code Instant.now()}; never {@code null}
   * @return the (possibly skewed) instant
   * @throws Throwable if the delegate throws
   */
  java.time.Instant adjustInstantNow(java.time.Instant realInstant) throws Throwable;

  /**
   * Returns the chaos-adjusted {@link java.time.LocalDateTime} for {@code LocalDateTime.now()}
   * interception.
   *
   * @param realValue the real value returned by {@code LocalDateTime.now()}; never {@code null}
   * @return the (possibly skewed) local date-time
   * @throws Throwable if the delegate throws
   */
  java.time.LocalDateTime adjustLocalDateTimeNow(java.time.LocalDateTime realValue)
      throws Throwable;

  /**
   * Returns the chaos-adjusted {@link java.time.ZonedDateTime} for {@code ZonedDateTime.now()}
   * interception.
   *
   * @param realValue the real value returned by {@code ZonedDateTime.now()}; never {@code null}
   * @return the (possibly skewed) zoned date-time
   * @throws Throwable if the delegate throws
   */
  java.time.ZonedDateTime adjustZonedDateTimeNow(java.time.ZonedDateTime realValue)
      throws Throwable;

  /**
   * Returns the chaos-adjusted epoch-millisecond value for the embedded time of a freshly
   * constructed {@link java.util.Date}.
   *
   * @param realMillis the value captured by the {@code Date()} constructor via {@code
   *     System.currentTimeMillis()}
   * @return the (possibly skewed) millisecond timestamp
   * @throws Throwable if the delegate throws
   */
  long adjustDateNew(long realMillis) throws Throwable;

  /**
   * Called before {@code System.gc()} or {@code Runtime.gc()}.
   *
   * @return {@code true} if an active SUPPRESS scenario wants to block the garbage-collection
   *     request; {@code false} to allow it
   * @throws Throwable if the delegate throws
   */
  boolean beforeGcRequest() throws Throwable;

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
  void beforeExitRequest(int status) throws Throwable;

  /**
   * Called before {@link java.lang.reflect.Method#invoke(Object, Object...)}.
   *
   * @param method the {@link java.lang.reflect.Method} about to be invoked; never {@code null}
   * @param target the object on which the method is being invoked; {@code null} for static methods
   * @throws Throwable if an active scenario injects an exception to abort the reflective call
   */
  void beforeReflectionInvoke(Object method, Object target) throws Throwable;

  /**
   * Called before {@link java.nio.ByteBuffer#allocateDirect(int)}.
   *
   * @param capacity the number of bytes requested for the direct buffer; non-negative
   * @throws Throwable if an active scenario wants to simulate an {@link OutOfMemoryError} or
   *     another allocation failure
   */
  void beforeDirectBufferAllocate(int capacity) throws Throwable;

  /**
   * Called before {@link java.io.ObjectInputStream#readObject()}.
   *
   * @param stream the {@link java.io.ObjectInputStream} about to deserialize an object; never
   *     {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate corrupt or hostile
   *     serialized data
   */
  void beforeObjectDeserialize(Object stream) throws Throwable;

  /**
   * Called before {@code ClassLoader.defineClass(...)}.
   *
   * @param loader the classloader defining the class; never {@code null}
   * @param className the binary name of the class being defined, or {@code null} when the caller
   *     did not supply a name
   * @throws Throwable if an active scenario wants to simulate a class-definition failure
   */
  void beforeClassDefine(Object loader, String className) throws Throwable;

  /**
   * Called before {@code AbstractQueuedSynchronizer.acquire(int)} as a proxy for monitor-enter
   * contention.
   *
   * <p>An active scenario may inject a delay to simulate lock contention.
   *
   * @param lock the monitor/synchronizer instance being acquired, or {@code null} when not
   *     available (e.g. instrumentation could not bind {@code @Advice.This})
   * @throws Throwable if an active scenario injects an exception
   */
  void beforeMonitorEnter(Object lock) throws Throwable;

  /**
   * Called before {@code LockSupport.park(Object)}, {@code parkNanos}, or {@code parkUntil}.
   *
   * <p>An active scenario may inject a delay before the park call to simulate scheduler pressure.
   *
   * @throws Throwable if an active scenario injects an exception
   */
  void beforeThreadPark() throws Throwable;

  /**
   * Called before {@link java.nio.channels.Selector#select()} or {@link
   * java.nio.channels.Selector#select(long)}.
   *
   * @param selector the {@link java.nio.channels.Selector} about to block; never {@code null}
   * @param timeoutMillis the timeout parameter as passed to the overload; {@code 0} for the
   *     no-argument variant
   * @return {@code true} to force a spurious wakeup (advice returns {@code 0}); {@code false} for
   *     normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeNioSelect(Object selector, long timeoutMillis) throws Throwable;

  /**
   * Called before a NIO channel read, write, connect, or accept operation.
   *
   * @param operation one of {@code "NIO_CHANNEL_READ"}, {@code "NIO_CHANNEL_WRITE"}, {@code
   *     "NIO_CHANNEL_CONNECT"}, or {@code "NIO_CHANNEL_ACCEPT"}; never {@code null}
   * @param channel the NIO channel instance; never {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate an I/O error
   */
  void beforeNioChannelOp(String operation, Object channel) throws Throwable;

  /**
   * Called before {@link java.net.Socket#connect(java.net.SocketAddress, int)}.
   *
   * @param socket the socket initiating the connection; never {@code null}
   * @param socketAddress the remote endpoint; never {@code null}
   * @param timeoutMillis the connection timeout; {@code 0} for infinite timeout
   * @throws Throwable if an active scenario injects a failure to simulate a connection refusal or
   *     timeout
   */
  void beforeSocketConnect(Object socket, Object socketAddress, int timeoutMillis) throws Throwable;

  /**
   * Called before {@link java.net.ServerSocket#accept()}.
   *
   * @param serverSocket the server socket about to block waiting for a connection; never {@code
   *     null}
   * @throws Throwable if an active scenario injects a failure to simulate an accept error
   */
  void beforeSocketAccept(Object serverSocket) throws Throwable;

  /**
   * Called before a socket input-stream read operation.
   *
   * @param stream the socket {@link java.io.InputStream}; never {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate a read error or timeout
   */
  void beforeSocketRead(Object stream) throws Throwable;

  /**
   * Called before a socket output-stream write operation.
   *
   * @param stream the socket {@link java.io.OutputStream}; never {@code null}
   * @param len the number of bytes about to be written; {@code 0} when the exact count is
   *     unavailable from the advice context
   * @throws Throwable if an active scenario injects a failure to simulate a write error
   */
  void beforeSocketWrite(Object stream, int len) throws Throwable;

  /**
   * Called before {@link java.net.Socket#close()}.
   *
   * @param socket the socket being closed; never {@code null}
   * @throws Throwable if an active scenario injects a failure to simulate a close error
   */
  void beforeSocketClose(Object socket) throws Throwable;

  /**
   * Called before {@link javax.naming.InitialContext#lookup(String)}.
   *
   * @param context the {@code InitialContext} performing the lookup; never {@code null}
   * @param name the JNDI name being looked up; never {@code null}
   * @throws Throwable if an active scenario injects a {@link javax.naming.NamingException} to
   *     simulate a JNDI failure
   */
  void beforeJndiLookup(Object context, String name) throws Throwable;

  /**
   * Called before {@link java.io.ObjectOutputStream#writeObject(Object)}.
   *
   * @param stream the {@link java.io.ObjectOutputStream} performing the serialization; never {@code
   *     null}
   * @param obj the object about to be serialized; may be {@code null} (serialization of null is
   *     valid)
   * @throws Throwable if an active scenario injects a failure to simulate a serialization error
   */
  void beforeObjectSerialize(Object stream, Object obj) throws Throwable;

  /**
   * Called before {@code System.loadLibrary(name)} or {@code System.load(path)}.
   *
   * @param libraryName the library name (for {@code loadLibrary}) or the absolute path (for {@code
   *     load}); never {@code null}
   * @throws Throwable if an active scenario wants to simulate an {@link UnsatisfiedLinkError} or
   *     block native library loading
   */
  void beforeNativeLibraryLoad(String libraryName) throws Throwable;

  /**
   * Called before {@link java.util.concurrent.CompletableFuture#cancel(boolean)}.
   *
   * <p>Returning {@code true} causes the advice to skip the real cancel call and return {@code
   * true} to the caller, simulating a successful cancel that never actually cancelled.
   *
   * @param future the future whose cancellation is being intercepted; never {@code null}
   * @param mayInterruptIfRunning the flag passed by the application to {@code cancel}
   * @return {@code true} if the cancel should be suppressed (advice returns {@code true} without
   *     cancelling); {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeAsyncCancel(Object future, boolean mayInterruptIfRunning) throws Throwable;

  /**
   * Called before {@code Inflater.inflate(...)}.
   *
   * <p>An active scenario may inject a delay to simulate slow decompression under load.
   *
   * @throws Throwable if an active scenario injects an exception
   */
  void beforeZipInflate() throws Throwable;

  /**
   * Called before {@code Deflater.deflate(...)}.
   *
   * <p>An active scenario may inject a delay to simulate slow compression under load.
   *
   * @throws Throwable if an active scenario injects an exception
   */
  void beforeZipDeflate() throws Throwable;

  /**
   * Called before {@link ThreadLocal#get()}.
   *
   * <p>Returning {@code true} causes the advice to skip the real get and return {@code null} to the
   * caller, simulating an absent thread-local value.
   *
   * @param threadLocal the {@link ThreadLocal} being read; never {@code null}
   * @return {@code true} to suppress the get and return {@code null}; {@code false} for normal
   *     execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeThreadLocalGet(Object threadLocal) throws Throwable;

  /**
   * Called before {@link ThreadLocal#set(Object)}.
   *
   * <p>Returning {@code true} causes the advice to skip the real set, silently discarding the
   * value.
   *
   * @param threadLocal the {@link ThreadLocal} being written; never {@code null}
   * @param value the value the application is attempting to store; may be {@code null}
   * @return {@code true} to suppress the set; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeThreadLocalSet(Object threadLocal, Object value) throws Throwable;

  /**
   * Called before {@code MBeanServer.invoke(ObjectName, String, Object[], String[])}.
   *
   * @param server the {@code MBeanServer} performing the operation; never {@code null}
   * @param objectName the {@code ObjectName} of the target MBean; never {@code null}
   * @param operationName the name of the MBean operation being invoked; never {@code null}
   * @throws Throwable if an active scenario injects a {@link javax.management.MBeanException} or
   *     other JMX failure
   */
  void beforeJmxInvoke(Object server, Object objectName, String operationName) throws Throwable;

  /**
   * Called before {@code MBeanServer.getAttribute(ObjectName, String)}.
   *
   * @param server the {@code MBeanServer} performing the attribute read; never {@code null}
   * @param objectName the {@code ObjectName} of the target MBean; never {@code null}
   * @param attribute the name of the attribute being read; never {@code null}
   * @throws Throwable if an active scenario injects a {@link javax.management.MBeanException} or
   *     other JMX failure
   */
  void beforeJmxGetAttr(Object server, Object objectName, String attribute) throws Throwable;

  /**
   * Called before a synchronous HTTP client send (Java {@code HttpClient.send}, OkHttp {@code
   * RealCall.execute}, Apache HttpComponents {@code CloseableHttpClient.execute}).
   *
   * @param url the request URL in {@code scheme://host/path} form; may be {@code null} when
   *     extraction fails
   * @return {@code true} to suppress the send (advice throws {@link
   *     com.macstab.chaos.jvm.api.ChaosHttpSuppressException}); {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeHttpSend(String url) throws Throwable;

  /**
   * Called before an asynchronous HTTP client send (Java {@code HttpClient.sendAsync}, OkHttp
   * {@code RealCall.enqueue}, Spring WebClient {@code HttpClientConnect.connect}).
   *
   * @param url the request URL in {@code scheme://host/path} form; may be {@code null}
   * @return {@code true} to suppress the send; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeHttpSendAsync(String url) throws Throwable;

  /**
   * Called before a JDBC connection is acquired from a pool (HikariCP, c3p0).
   *
   * @param poolName the pool identifier; may be {@code null}
   * @return {@code true} to suppress the acquisition; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeJdbcConnectionAcquire(String poolName) throws Throwable;

  /**
   * Called before a {@link java.sql.Statement} execute call.
   *
   * @param sql the SQL statement; may be {@code null}
   * @return {@code true} to suppress the call; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeJdbcStatementExecute(String sql) throws Throwable;

  /**
   * Called before a {@link java.sql.Connection#prepareStatement(String)} call.
   *
   * @param sql the SQL statement being prepared; may be {@code null}
   * @return {@code true} to suppress the call; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeJdbcPreparedStatement(String sql) throws Throwable;

  /**
   * Called before a {@link java.sql.Connection#commit()} call.
   *
   * @return {@code true} to suppress the commit; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeJdbcTransactionCommit() throws Throwable;

  /**
   * Called before a {@link java.sql.Connection#rollback()} call.
   *
   * @return {@code true} to suppress the rollback; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeJdbcTransactionRollback() throws Throwable;

  /**
   * Called before {@link Thread#sleep(long)}.
   *
   * @param millis the sleep duration in milliseconds
   * @return {@code true} to suppress the sleep; {@code false} for normal execution
   * @throws Throwable if the delegate throws
   */
  boolean beforeThreadSleep(long millis) throws Throwable;

  /**
   * Called before {@link java.net.InetAddress#getByName(String)}, {@link
   * java.net.InetAddress#getAllByName(String)}, or {@link java.net.InetAddress#getLocalHost()}.
   *
   * @param hostname the hostname being resolved; {@code null} for {@code getLocalHost()}
   * @throws Throwable if the delegate throws
   */
  void beforeDnsResolve(String hostname) throws Throwable;

  /**
   * Called before {@link javax.net.ssl.SSLSocket#startHandshake()} or {@link
   * javax.net.ssl.SSLEngine#beginHandshake()}.
   *
   * @param socket the {@code SSLSocket} or {@code SSLEngine} instance
   * @throws Throwable if the delegate throws
   */
  void beforeSslHandshake(Object socket) throws Throwable;

  /**
   * Called before a {@link java.io.FileInputStream#read} or {@link java.io.FileOutputStream#write}
   * call.
   *
   * @param operation {@code "FILE_IO_READ"} or {@code "FILE_IO_WRITE"}
   * @param stream the stream instance
   * @throws Throwable if the delegate throws
   */
  void beforeFileIo(String operation, Object stream) throws Throwable;
}

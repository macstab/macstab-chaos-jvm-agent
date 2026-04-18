package com.macstab.chaos.instrumentation;

import com.macstab.chaos.core.ChaosRuntime;
import com.macstab.chaos.instrumentation.bridge.BridgeDelegate;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

/**
 * Agent-classloader implementation of {@link
 * com.macstab.chaos.instrumentation.bridge.BridgeDelegate} that delegates every call to the
 * corresponding method on {@link com.macstab.chaos.core.ChaosRuntime}.
 *
 * <p>An instance of this class is constructed by {@link
 * com.macstab.chaos.instrumentation.JdkInstrumentationInstaller} and passed to {@link
 * com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher#install} as the bridge delegate.
 * From that point on, {@code BootstrapDispatcher}'s dispatch methods route intercepted JDK
 * operations to {@code ChaosRuntime} via the {@link java.lang.invoke.MethodHandle} array that was
 * pre-built from this instance's methods.
 *
 * <p>This class contains no logic of its own; every method is a one-line delegation. It exists
 * solely to provide the agent classloader half of the bootstrap-to-agent bridge.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Thread safety is provided entirely by the {@link com.macstab.chaos.core.ChaosRuntime}
 * implementation. This class itself is stateless beyond its {@code runtime} field, which is
 * assigned once at construction and never modified.
 */
final class ChaosBridge implements BridgeDelegate {
  private final ChaosRuntime runtime;

  /**
   * Creates a bridge backed by the given runtime.
   *
   * @param runtime the chaos runtime to which all intercepted JDK operations are forwarded
   */
  ChaosBridge(final ChaosRuntime runtime) {
    this.runtime = runtime;
  }

  // ── Phase 1 delegations ────────────────────────────────────────────────────

  /**
   * Decorates the given {@link Runnable} task before it is submitted to an executor.
   *
   * @param operation a label identifying the submit operation (e.g. {@code "execute"})
   * @param executor the executor instance receiving the task
   * @param task the original task
   * @return the decorated task, potentially wrapped to inject chaos behaviour
   */
  @Override
  public Runnable decorateExecutorRunnable(
      final String operation, final Object executor, final Runnable task) {
    return runtime.decorateExecutorRunnable(operation, executor, task);
  }

  /**
   * Decorates the given {@link Callable} task before it is submitted to an executor.
   *
   * @param <T> the task return type
   * @param operation a label identifying the submit operation
   * @param executor the executor instance receiving the task
   * @param task the original task
   * @return the decorated task, potentially wrapped to inject chaos behaviour
   */
  @Override
  public <T> Callable<T> decorateExecutorCallable(
      final String operation, final Object executor, final Callable<T> task) {
    return runtime.decorateExecutorCallable(operation, executor, task);
  }

  /**
   * Invoked immediately before {@link Thread#start()} is called on the given thread.
   *
   * @param thread the thread about to be started
   * @throws Throwable if the runtime decides to abort the start
   */
  @Override
  public void beforeThreadStart(final Thread thread) throws Throwable {
    runtime.beforeThreadStart(thread);
  }

  /**
   * Invoked before a worker thread in a thread pool begins executing a task.
   *
   * @param executor the executor that owns the worker
   * @param worker the worker thread
   * @param task the task the worker is about to run
   * @throws Throwable if the runtime decides to abort or delay execution
   */
  @Override
  public void beforeWorkerRun(final Object executor, final Thread worker, final Runnable task)
      throws Throwable {
    runtime.beforeWorkerRun(executor, worker, task);
  }

  /**
   * Invoked before a {@link ForkJoinTask} begins executing in a fork-join pool.
   *
   * @param task the fork-join task about to run
   * @throws Throwable if the runtime decides to abort or delay execution
   */
  @Override
  public void beforeForkJoinTaskRun(final ForkJoinTask<?> task) throws Throwable {
    runtime.beforeForkJoinTaskRun(task);
  }

  /**
   * Optionally adjusts the delay before a scheduled task fires.
   *
   * @param operation a label identifying the schedule operation
   * @param executor the scheduled executor
   * @param task the task being scheduled
   * @param delay the originally requested delay in milliseconds
   * @param periodic {@code true} if the task repeats on a fixed rate or fixed delay
   * @return the (possibly modified) delay in milliseconds that should be applied
   * @throws Throwable if the runtime decides to abort scheduling
   */
  @Override
  public long adjustScheduleDelay(
      final String operation,
      final Object executor,
      final Object task,
      final long delay,
      final boolean periodic)
      throws Throwable {
    return runtime.adjustScheduleDelay(operation, executor, task, delay, periodic);
  }

  /**
   * Invoked before a scheduled task's tick fires, giving the runtime a chance to suppress it.
   *
   * @param executor the scheduled executor
   * @param task the task whose tick is about to fire
   * @param periodic {@code true} if this is a recurring tick
   * @return {@code true} to allow the tick to proceed; {@code false} to suppress it
   * @throws Throwable if the runtime decides to abort execution
   */
  @Override
  public boolean beforeScheduledTick(
      final Object executor, final Object task, final boolean periodic) throws Throwable {
    return runtime.beforeScheduledTick(executor, task, periodic);
  }

  /**
   * Invoked before a blocking queue operation (e.g. {@code put}, {@code take}) is executed.
   *
   * @param operation a label identifying the queue operation
   * @param queue the queue on which the operation is about to be performed
   * @throws Throwable if the runtime decides to abort or delay the operation
   */
  @Override
  public void beforeQueueOperation(final String operation, final Object queue) throws Throwable {
    runtime.beforeQueueOperation(operation, queue);
  }

  /**
   * Invoked before a boolean-returning queue operation (e.g. {@code offer}) is executed.
   *
   * @param operation a label identifying the queue operation
   * @param queue the queue on which the operation is about to be performed
   * @return a forced boolean result to short-circuit the real operation, or {@code null} to let the
   *     operation proceed normally
   * @throws Throwable if the runtime decides to abort the operation
   */
  @Override
  public Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    return runtime.beforeBooleanQueueOperation(operation, queue);
  }

  /**
   * Invoked before a {@link CompletableFuture} completion operation (e.g. {@code complete}, {@code
   * completeExceptionally}) is applied.
   *
   * @param operation a label identifying the completion operation
   * @param future the future being completed
   * @param payload the value or exception being applied (may be {@code null})
   * @return a forced boolean result to short-circuit the real completion, or {@code null} to let
   *     the operation proceed normally
   * @throws Throwable if the runtime decides to abort the completion
   */
  @Override
  public Boolean beforeCompletableFutureComplete(
      final String operation, final CompletableFuture<?> future, final Object payload)
      throws Throwable {
    return runtime.beforeCompletableFutureComplete(operation, future, payload);
  }

  /**
   * Invoked before a class is loaded by the given class loader.
   *
   * @param loader the class loader performing the load
   * @param className the binary name of the class being loaded
   * @throws Throwable if the runtime decides to abort or delay class loading
   */
  @Override
  public void beforeClassLoad(final ClassLoader loader, final String className) throws Throwable {
    runtime.beforeClassLoad(loader, className);
  }

  /**
   * Invoked after a class loader resource lookup, allowing the result to be replaced.
   *
   * @param loader the class loader that performed the lookup
   * @param name the resource name that was looked up
   * @param currentValue the URL resolved by the real lookup, or {@code null} if not found
   * @return the URL to return to the caller (may differ from {@code currentValue})
   * @throws Throwable if the runtime decides to abort or simulate a lookup failure
   */
  @Override
  public URL afterResourceLookup(
      final ClassLoader loader, final String name, final URL currentValue) throws Throwable {
    return runtime.afterResourceLookup(loader, name, currentValue);
  }

  /**
   * Decorates a shutdown hook thread before it is registered with the JVM.
   *
   * @param hook the shutdown hook thread about to be registered
   * @return the thread to actually register (may be the same instance or a wrapper)
   * @throws Throwable if the runtime decides to abort hook registration
   */
  @Override
  public Thread decorateShutdownHook(final Thread hook) throws Throwable {
    return runtime.decorateShutdownHook(hook);
  }

  /**
   * Resolves which thread should be removed when a shutdown hook is deregistered.
   *
   * <p>Because {@link #decorateShutdownHook} may have substituted the original hook thread with a
   * wrapper, this method provides the reverse mapping so that the JVM removes the correct thread.
   *
   * @param original the thread passed to the deregistration call
   * @return the thread that was actually registered (may equal {@code original})
   */
  @Override
  public Thread resolveShutdownHook(final Thread original) {
    return runtime.resolveShutdownHook(original);
  }

  /**
   * Invoked before an executor shutdown or await-termination call.
   *
   * @param operation a label identifying the shutdown operation (e.g. {@code "shutdown"}, {@code
   *     "awaitTermination"})
   * @param executor the executor being shut down
   * @param timeoutMillis the timeout in milliseconds for {@code awaitTermination}-style calls, or
   *     {@code 0} for non-blocking shutdown calls
   * @throws Throwable if the runtime decides to abort or delay the shutdown
   */
  @Override
  public void beforeExecutorShutdown(
      final String operation, final Object executor, final long timeoutMillis) throws Throwable {
    runtime.beforeExecutorShutdown(operation, executor, timeoutMillis);
  }

  // ── Phase 2 delegations ────────────────────────────────────────────────────

  /**
   * Optionally skews the wall-clock millisecond value returned by {@link
   * System#currentTimeMillis()}.
   *
   * @param realMillis the actual value of {@link System#currentTimeMillis()}
   * @return the (possibly skewed) millisecond value that should be returned to the caller
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public long adjustClockMillis(final long realMillis) throws Throwable {
    return runtime.adjustClockMillis(realMillis);
  }

  /**
   * Optionally skews the high-resolution nanosecond value returned by {@link System#nanoTime()}.
   *
   * @param realNanos the actual value of {@link System#nanoTime()}
   * @return the (possibly skewed) nanosecond value that should be returned to the caller
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public long adjustClockNanos(final long realNanos) throws Throwable {
    return runtime.adjustClockNanos(realNanos);
  }

  /**
   * Optionally skews the {@link java.time.Instant} returned by {@link java.time.Instant#now()}.
   *
   * @param realInstant the value returned by the real {@link java.time.Instant#now()}
   * @return the (possibly skewed) instant
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public java.time.Instant adjustInstantNow(final java.time.Instant realInstant) throws Throwable {
    return runtime.adjustInstantNow(realInstant);
  }

  /**
   * Optionally skews the {@link java.time.LocalDateTime} returned by {@link
   * java.time.LocalDateTime#now()}.
   *
   * @param realValue the value returned by the real {@link java.time.LocalDateTime#now()}
   * @return the (possibly skewed) local date-time
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public java.time.LocalDateTime adjustLocalDateTimeNow(final java.time.LocalDateTime realValue)
      throws Throwable {
    return runtime.adjustLocalDateTimeNow(realValue);
  }

  /**
   * Optionally skews the {@link java.time.ZonedDateTime} returned by {@link
   * java.time.ZonedDateTime#now()}.
   *
   * @param realValue the value returned by the real {@link java.time.ZonedDateTime#now()}
   * @return the (possibly skewed) zoned date-time
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public java.time.ZonedDateTime adjustZonedDateTimeNow(final java.time.ZonedDateTime realValue)
      throws Throwable {
    return runtime.adjustZonedDateTimeNow(realValue);
  }

  /**
   * Optionally skews the epoch-millisecond value embedded in a freshly constructed {@link
   * java.util.Date}.
   *
   * @param realMillis the value captured by the {@link java.util.Date#Date()} constructor
   * @return the (possibly skewed) millisecond timestamp
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public long adjustDateNew(final long realMillis) throws Throwable {
    return runtime.adjustDateNew(realMillis);
  }

  /**
   * Invoked before an explicit {@link System#gc()} request is executed.
   *
   * @return {@code true} to allow the GC request to proceed; {@code false} to suppress it
   * @throws Throwable if the runtime decides to abort the request with an exception
   */
  @Override
  public boolean beforeGcRequest() throws Throwable {
    return runtime.beforeGcRequest();
  }

  /**
   * Invoked before {@link System#exit(int)} or {@link Runtime#halt(int)} is called.
   *
   * @param status the exit status code
   * @throws Throwable if the runtime decides to abort or delay the exit
   */
  @Override
  public void beforeExitRequest(final int status) throws Throwable {
    runtime.beforeExitRequest(status);
  }

  /**
   * Invoked before a reflective {@link java.lang.reflect.Method#invoke} call.
   *
   * @param method the {@link java.lang.reflect.Method} about to be invoked
   * @param target the object on which the method is invoked, or {@code null} for static methods
   * @throws Throwable if the runtime decides to abort or delay the invocation
   */
  @Override
  public void beforeReflectionInvoke(final Object method, final Object target) throws Throwable {
    runtime.beforeReflectionInvoke(method, target);
  }

  /**
   * Invoked before a direct {@link java.nio.ByteBuffer} allocation.
   *
   * @param capacity the requested buffer capacity in bytes
   * @throws Throwable if the runtime decides to abort or delay the allocation
   */
  @Override
  public void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    runtime.beforeDirectBufferAllocate(capacity);
  }

  /**
   * Invoked before an object is read from an {@link java.io.ObjectInputStream}.
   *
   * @param stream the {@link java.io.ObjectInputStream} from which deserialization is about to
   *     occur
   * @throws Throwable if the runtime decides to abort or delay deserialization
   */
  @Override
  public void beforeObjectDeserialize(final Object stream) throws Throwable {
    runtime.beforeObjectDeserialize(stream);
  }

  /**
   * Invoked before a class definition (bytecode installation) is submitted to the JVM.
   *
   * @param loader the class loader performing the definition
   * @param className the binary name of the class being defined
   * @throws Throwable if the runtime decides to abort or delay class definition
   */
  @Override
  public void beforeClassDefine(final Object loader, final String className) throws Throwable {
    runtime.beforeClassDefine(loader, className);
  }

  /**
   * Invoked before the current thread attempts to acquire an object monitor ({@code synchronized}
   * block entry).
   *
   * @throws Throwable if the runtime decides to abort or delay monitor acquisition
   */
  @Override
  public void beforeMonitorEnter() throws Throwable {
    runtime.beforeMonitorEnter();
  }

  /**
   * Invoked before the current thread parks via {@link
   * java.util.concurrent.locks.LockSupport#park}.
   *
   * @throws Throwable if the runtime decides to abort or delay the park
   */
  @Override
  public void beforeThreadPark() throws Throwable {
    runtime.beforeThreadPark();
  }

  /**
   * Invoked before a {@link java.nio.channels.Selector#select(long)} call.
   *
   * @param selector the selector about to block
   * @param timeoutMillis the requested select timeout in milliseconds ({@code 0} means {@code
   *     selectNow})
   * @return {@code true} to allow the select to proceed; {@code false} to skip it
   * @throws Throwable if the runtime decides to abort the select
   */
  @Override
  public boolean beforeNioSelect(final Object selector, final long timeoutMillis) throws Throwable {
    return runtime.beforeNioSelect(selector, timeoutMillis);
  }

  /**
   * Invoked before a NIO channel operation (e.g. {@code read}, {@code write}).
   *
   * @param operation a label identifying the channel operation
   * @param channel the NIO channel on which the operation is about to be performed
   * @throws Throwable if the runtime decides to abort or delay the operation
   */
  @Override
  public void beforeNioChannelOp(final String operation, final Object channel) throws Throwable {
    runtime.beforeNioChannelOp(operation, channel);
  }

  /**
   * Invoked before a socket {@code connect} call.
   *
   * @param socket the socket being connected
   * @param socketAddress the remote address to connect to
   * @param timeoutMillis the connect timeout in milliseconds ({@code 0} means no timeout)
   * @throws Throwable if the runtime decides to abort or delay the connection
   */
  @Override
  public void beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    runtime.beforeSocketConnect(socket, socketAddress, timeoutMillis);
  }

  /**
   * Invoked before a server socket {@code accept} call.
   *
   * @param serverSocket the server socket about to accept a connection
   * @throws Throwable if the runtime decides to abort or delay the accept
   */
  @Override
  public void beforeSocketAccept(final Object serverSocket) throws Throwable {
    runtime.beforeSocketAccept(serverSocket);
  }

  /**
   * Invoked before a socket read operation.
   *
   * @param stream the socket input stream from which data is about to be read
   * @throws Throwable if the runtime decides to abort or delay the read
   */
  @Override
  public void beforeSocketRead(final Object stream) throws Throwable {
    runtime.beforeSocketRead(stream);
  }

  /**
   * Invoked before a socket write operation.
   *
   * @param stream the socket output stream to which data is about to be written
   * @param len the number of bytes about to be written
   * @throws Throwable if the runtime decides to abort or delay the write
   */
  @Override
  public void beforeSocketWrite(final Object stream, final int len) throws Throwable {
    runtime.beforeSocketWrite(stream, len);
  }

  /**
   * Invoked before a socket {@code close} call.
   *
   * @param socket the socket about to be closed
   * @throws Throwable if the runtime decides to abort or delay the close
   */
  @Override
  public void beforeSocketClose(final Object socket) throws Throwable {
    runtime.beforeSocketClose(socket);
  }

  /**
   * Invoked before a JNDI {@code lookup} call.
   *
   * @param context the JNDI context performing the lookup
   * @param name the JNDI name being looked up
   * @throws Throwable if the runtime decides to abort or delay the lookup
   */
  @Override
  public void beforeJndiLookup(final Object context, final String name) throws Throwable {
    runtime.beforeJndiLookup(context, name);
  }

  /**
   * Invoked before an object is written to an {@link java.io.ObjectOutputStream}.
   *
   * @param stream the {@link java.io.ObjectOutputStream} to which the object is about to be written
   * @param obj the object about to be serialized
   * @throws Throwable if the runtime decides to abort or delay serialization
   */
  @Override
  public void beforeObjectSerialize(final Object stream, final Object obj) throws Throwable {
    runtime.beforeObjectSerialize(stream, obj);
  }

  /**
   * Invoked before a native library is loaded via {@link System#loadLibrary(String)} or {@link
   * System#load(String)}.
   *
   * @param libraryName the name or absolute path of the library about to be loaded
   * @throws Throwable if the runtime decides to abort or delay the load
   */
  @Override
  public void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    runtime.beforeNativeLibraryLoad(libraryName);
  }

  /**
   * Invoked before a {@link java.util.concurrent.Future#cancel(boolean)} call.
   *
   * @param future the future about to be cancelled
   * @param mayInterruptIfRunning {@code true} if the thread executing the task should be
   *     interrupted; corresponds to the argument of {@code cancel}
   * @return {@code true} to allow the cancellation to proceed; {@code false} to suppress it
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public boolean beforeAsyncCancel(final Object future, final boolean mayInterruptIfRunning)
      throws Throwable {
    return runtime.beforeAsyncCancel(future, mayInterruptIfRunning);
  }

  /**
   * Invoked before a ZIP/GZIP inflate (decompression) operation begins.
   *
   * @throws Throwable if the runtime decides to abort or delay decompression
   */
  @Override
  public void beforeZipInflate() throws Throwable {
    runtime.beforeZipInflate();
  }

  /**
   * Invoked before a ZIP/GZIP deflate (compression) operation begins.
   *
   * @throws Throwable if the runtime decides to abort or delay compression
   */
  @Override
  public void beforeZipDeflate() throws Throwable {
    runtime.beforeZipDeflate();
  }

  /**
   * Invoked before a {@link ThreadLocal#get()} call, giving the runtime a chance to suppress it.
   *
   * @param threadLocal the {@link ThreadLocal} instance being read
   * @return {@code true} to allow the get to proceed normally; {@code false} to suppress it
   * @throws Throwable if the runtime decides to abort or delay the read
   */
  @Override
  public boolean beforeThreadLocalGet(final Object threadLocal) throws Throwable {
    return runtime.beforeThreadLocalGet(threadLocal);
  }

  /**
   * Invoked before a {@link ThreadLocal#set(Object)} call, giving the runtime a chance to suppress
   * it.
   *
   * @param threadLocal the {@link ThreadLocal} instance being written
   * @param value the value about to be stored
   * @return {@code true} to allow the set to proceed normally; {@code false} to suppress it
   * @throws Throwable if the runtime decides to abort or delay the write
   */
  @Override
  public boolean beforeThreadLocalSet(final Object threadLocal, final Object value)
      throws Throwable {
    return runtime.beforeThreadLocalSet(threadLocal, value);
  }

  /**
   * Invoked before a JMX {@code invoke} call on an {@code MBeanServer}.
   *
   * @param server the {@code MBeanServer} on which the operation is invoked
   * @param objectName the {@code ObjectName} identifying the target MBean
   * @param operationName the name of the MBean operation to invoke
   * @throws Throwable if the runtime decides to abort or delay the invocation
   */
  @Override
  public void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    runtime.beforeJmxInvoke(server, objectName, operationName);
  }

  /**
   * Invoked before a JMX {@code getAttribute} call on an {@code MBeanServer}.
   *
   * @param server the {@code MBeanServer} performing the attribute read
   * @param objectName the {@code ObjectName} identifying the target MBean
   * @param attribute the name of the MBean attribute to read
   * @throws Throwable if the runtime decides to abort or delay the attribute read
   */
  @Override
  public void beforeJmxGetAttr(final Object server, final Object objectName, final String attribute)
      throws Throwable {
    runtime.beforeJmxGetAttr(server, objectName, attribute);
  }

  /**
   * Invoked before a synchronous HTTP client send; returns {@code true} when the runtime decides to
   * suppress the call.
   *
   * @param url the request URL
   * @return {@code true} to suppress; {@code false} for normal execution
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public boolean beforeHttpSend(final String url) throws Throwable {
    return runtime.beforeHttpSend(url, com.macstab.chaos.api.OperationType.HTTP_CLIENT_SEND);
  }

  /**
   * Invoked before an asynchronous HTTP client send; returns {@code true} when the runtime decides
   * to suppress the call.
   *
   * @param url the request URL
   * @return {@code true} to suppress; {@code false} for normal execution
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public boolean beforeHttpSendAsync(final String url) throws Throwable {
    return runtime.beforeHttpSend(url, com.macstab.chaos.api.OperationType.HTTP_CLIENT_SEND_ASYNC);
  }

  /**
   * Invoked before a JDBC connection is acquired from a pool; returns {@code true} when the runtime
   * decides to suppress the call.
   *
   * @param poolName the pool identifier
   * @return {@code true} to suppress; {@code false} for normal execution
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    return runtime.beforeJdbcConnectionAcquire(poolName);
  }

  /**
   * Invoked before a {@link java.sql.Statement} execute call; returns {@code true} when the runtime
   * decides to suppress the call.
   *
   * @param sql the SQL statement
   * @return {@code true} to suppress; {@code false} for normal execution
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public boolean beforeJdbcStatementExecute(final String sql) throws Throwable {
    return runtime.beforeJdbcStatementExecute(sql);
  }

  /**
   * Invoked before a {@link java.sql.Connection#prepareStatement(String)} call; returns {@code
   * true} when the runtime decides to suppress the call.
   *
   * @param sql the SQL statement being prepared
   * @return {@code true} to suppress; {@code false} for normal execution
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public boolean beforeJdbcPreparedStatement(final String sql) throws Throwable {
    return runtime.beforeJdbcPreparedStatement(sql);
  }

  /**
   * Invoked before a {@link java.sql.Connection#commit()} call; returns {@code true} when the
   * runtime decides to suppress the commit.
   *
   * @return {@code true} to suppress; {@code false} for normal execution
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public boolean beforeJdbcTransactionCommit() throws Throwable {
    return runtime.beforeJdbcTransactionCommit();
  }

  /**
   * Invoked before a {@link java.sql.Connection#rollback()} call; returns {@code true} when the
   * runtime decides to suppress the rollback.
   *
   * @return {@code true} to suppress; {@code false} for normal execution
   * @throws Throwable if the runtime decides to abort the call
   */
  @Override
  public boolean beforeJdbcTransactionRollback() throws Throwable {
    return runtime.beforeJdbcTransactionRollback();
  }
}

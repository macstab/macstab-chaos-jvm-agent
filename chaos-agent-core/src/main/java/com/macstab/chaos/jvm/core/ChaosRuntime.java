package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Thin facade that composes {@link ChaosControlPlaneImpl} and {@link ChaosDispatcher}.
 *
 * <p>Retained as a single entry point so that existing callers ({@code ChaosBridge}, bootstrap,
 * tests) do not need to be updated when the control-plane and dispatch halves are profiled or
 * exercised in isolation. Delegates control-plane methods to {@link ChaosControlPlaneImpl} and
 * hot-path methods to {@link ChaosDispatcher}.
 *
 * <p><strong>Not part of the stable API.</strong> This class is {@code public} only because it is
 * referenced across the chaos-agent internal multi-module split (bootstrap, instrumentation,
 * benchmarks). The frozen public surface is {@link ChaosControlPlane} in the api module; bind
 * against that interface instead. The {@code ChaosRuntime}-class surface may change without notice
 * in any release. The {@link Internal @Internal} marker makes that contract explicit to API
 * linters, IDE inspections, and {@code japicmp}-style bytecode tools.
 */
@Internal
public final class ChaosRuntime implements ChaosControlPlane {
  private final ChaosControlPlaneImpl controlPlane;
  private final ChaosDispatcher dispatcher;

  /** Creates a runtime using the system UTC clock and a no-op metrics sink. */
  public ChaosRuntime() {
    this(Clock.systemUTC(), ChaosMetricsSink.NOOP);
  }

  /** Creates a runtime backed by the supplied clock and metrics sink. */
  public ChaosRuntime(final Clock clock, final ChaosMetricsSink metricsSink) {
    this.controlPlane = new ChaosControlPlaneImpl(clock, metricsSink);
    this.dispatcher = new ChaosDispatcher(controlPlane);
  }

  /** Returns the hot-path {@link ChaosDispatcher} composed by this runtime. */
  public ChaosDispatcher dispatcher() {
    return dispatcher;
  }

  /** Returns the control-plane implementation composed by this runtime. */
  public ChaosControlPlaneImpl controlPlane() {
    return controlPlane;
  }

  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    return controlPlane.activate(scenario);
  }

  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    return controlPlane.activate(plan);
  }

  @Override
  public ChaosSession openSession(final String displayName) {
    return controlPlane.openSession(displayName, this);
  }

  @Override
  public ChaosDiagnostics diagnostics() {
    return controlPlane.diagnostics();
  }

  @Override
  public void addEventListener(final ChaosEventListener listener) {
    controlPlane.addEventListener(listener);
  }

  @Override
  public void close() {
    controlPlane.close();
  }

  /** Returns the session id bound to the current thread, or {@code null} if none is active. */
  public String currentSessionId() {
    return dispatcher.currentSessionId();
  }

  /** Delegates to {@link ChaosDispatcher#decorateExecutorRunnable}. */
  public Runnable decorateExecutorRunnable(
      final String operation, final Object executor, final Runnable task) {
    return dispatcher.decorateExecutorRunnable(operation, executor, task);
  }

  /** Delegates to {@link ChaosDispatcher#decorateExecutorCallable}. */
  public <T> Callable<T> decorateExecutorCallable(
      final String operation, final Object executor, final Callable<T> task) {
    return dispatcher.decorateExecutorCallable(operation, executor, task);
  }

  /** Delegates to {@link ChaosDispatcher#beforeThreadStart}. */
  public void beforeThreadStart(final Thread thread) throws Throwable {
    dispatcher.beforeThreadStart(thread);
  }

  /** Delegates to {@link ChaosDispatcher#beforeWorkerRun}. */
  public void beforeWorkerRun(final Object executor, final Thread worker, final Runnable task)
      throws Throwable {
    dispatcher.beforeWorkerRun(executor, worker, task);
  }

  /** Delegates to {@link ChaosDispatcher#beforeForkJoinTaskRun}. */
  public void beforeForkJoinTaskRun(final java.util.concurrent.ForkJoinTask<?> task)
      throws Throwable {
    dispatcher.beforeForkJoinTaskRun(task);
  }

  /** Delegates to {@link ChaosDispatcher#adjustScheduleDelay}. */
  public long adjustScheduleDelay(
      final String operation,
      final Object executor,
      final Object task,
      final long delay,
      final boolean periodic)
      throws Throwable {
    return dispatcher.adjustScheduleDelay(operation, executor, task, delay, periodic);
  }

  /** Delegates to {@link ChaosDispatcher#beforeQueueOperation}. */
  public void beforeQueueOperation(final String operation, final Object queue) throws Throwable {
    dispatcher.beforeQueueOperation(operation, queue);
  }

  /** Delegates to {@link ChaosDispatcher#beforeBooleanQueueOperation}. */
  public Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    return dispatcher.beforeBooleanQueueOperation(operation, queue);
  }

  /** Delegates to {@link ChaosDispatcher#beforeCompletableFutureComplete}. */
  public Boolean beforeCompletableFutureComplete(
      final String operation, final CompletableFuture<?> future, final Object payload)
      throws Throwable {
    return dispatcher.beforeCompletableFutureComplete(operation, future, payload);
  }

  /** Delegates to {@link ChaosDispatcher#beforeClassLoad}. */
  public void beforeClassLoad(final ClassLoader loader, final String className) throws Throwable {
    dispatcher.beforeClassLoad(loader, className);
  }

  /** Delegates to {@link ChaosDispatcher#afterResourceLookup}. */
  public URL afterResourceLookup(
      final ClassLoader loader, final String name, final URL currentValue) throws Throwable {
    return dispatcher.afterResourceLookup(loader, name, currentValue);
  }

  /** Delegates to {@link ChaosDispatcher#decorateShutdownHook}. */
  public Thread decorateShutdownHook(final Thread hook) throws Throwable {
    return dispatcher.decorateShutdownHook(hook);
  }

  /** Delegates to {@link ChaosDispatcher#resolveShutdownHook}. */
  public Thread resolveShutdownHook(final Thread original) {
    return dispatcher.resolveShutdownHook(original);
  }

  /** Delegates to {@link ChaosDispatcher#beforeExecutorShutdown}. */
  public void beforeExecutorShutdown(
      final String operation, final Object executor, final long timeoutMillis) throws Throwable {
    dispatcher.beforeExecutorShutdown(operation, executor, timeoutMillis);
  }

  /** Delegates to {@link ChaosDispatcher#beforeScheduledTick}. */
  public boolean beforeScheduledTick(
      final Object executor, final Object task, final boolean periodic) throws Throwable {
    return dispatcher.beforeScheduledTick(executor, task, periodic);
  }

  /** Delegates to {@link ChaosDispatcher#beforeMethodEnter}. */
  public void beforeMethodEnter(final String className, final String methodName) throws Throwable {
    dispatcher.beforeMethodEnter(className, methodName);
  }

  /** Delegates to {@link ChaosDispatcher#afterMethodExit}. */
  public Object afterMethodExit(
      final String className,
      final String methodName,
      final Class<?> returnType,
      final Object actualValue)
      throws Throwable {
    return dispatcher.afterMethodExit(className, methodName, returnType, actualValue);
  }

  /** Delegates to {@link ChaosDispatcher#applyClockSkew}. */
  public long applyClockSkew(final long realValue, final OperationType clockType) {
    return dispatcher.applyClockSkew(realValue, clockType);
  }

  /** Delegates to {@link ChaosControlPlaneImpl#setInstrumentation(Instrumentation)}. */
  public void setInstrumentation(final Instrumentation inst) {
    controlPlane.setInstrumentation(inst);
  }

  /** Delegates to {@link ChaosDispatcher#adjustClockMillis}. */
  public long adjustClockMillis(final long realMillis) {
    return dispatcher.adjustClockMillis(realMillis);
  }

  /** Delegates to {@link ChaosDispatcher#adjustClockNanos}. */
  public long adjustClockNanos(final long realNanos) {
    return dispatcher.adjustClockNanos(realNanos);
  }

  /** Delegates to {@link ChaosDispatcher#adjustInstantNow}. */
  public java.time.Instant adjustInstantNow(final java.time.Instant realInstant) {
    return dispatcher.adjustInstantNow(realInstant);
  }

  /** Delegates to {@link ChaosDispatcher#adjustLocalDateTimeNow}. */
  public java.time.LocalDateTime adjustLocalDateTimeNow(final java.time.LocalDateTime realValue) {
    return dispatcher.adjustLocalDateTimeNow(realValue);
  }

  /** Delegates to {@link ChaosDispatcher#adjustZonedDateTimeNow}. */
  public java.time.ZonedDateTime adjustZonedDateTimeNow(final java.time.ZonedDateTime realValue) {
    return dispatcher.adjustZonedDateTimeNow(realValue);
  }

  /** Delegates to {@link ChaosDispatcher#adjustDateNew}. */
  public long adjustDateNew(final long realMillis) {
    return dispatcher.adjustDateNew(realMillis);
  }

  /** Delegates to {@link ChaosDispatcher#beforeGcRequest}. */
  public boolean beforeGcRequest() throws Throwable {
    return dispatcher.beforeGcRequest();
  }

  /** Delegates to {@link ChaosDispatcher#beforeExitRequest}. */
  public void beforeExitRequest(final int status) throws Throwable {
    dispatcher.beforeExitRequest(status);
  }

  /** Delegates to {@link ChaosDispatcher#beforeReflectionInvoke}. */
  public void beforeReflectionInvoke(final Object method, final Object target) throws Throwable {
    dispatcher.beforeReflectionInvoke(method, target);
  }

  /** Delegates to {@link ChaosDispatcher#beforeDirectBufferAllocate}. */
  public void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    dispatcher.beforeDirectBufferAllocate(capacity);
  }

  /** Delegates to {@link ChaosDispatcher#beforeObjectDeserialize}. */
  public void beforeObjectDeserialize(final Object stream) throws Throwable {
    dispatcher.beforeObjectDeserialize(stream);
  }

  /** Delegates to {@link ChaosDispatcher#beforeClassDefine}. */
  public void beforeClassDefine(final Object loader, final String className) throws Throwable {
    dispatcher.beforeClassDefine(loader, className);
  }

  /** Delegates to {@link ChaosDispatcher#beforeMonitorEnter}. */
  public void beforeMonitorEnter(final Object lock) throws Throwable {
    dispatcher.beforeMonitorEnter(lock);
  }

  /** Delegates to {@link ChaosDispatcher#beforeThreadPark}. */
  public void beforeThreadPark() throws Throwable {
    dispatcher.beforeThreadPark();
  }

  /** Delegates to {@link ChaosDispatcher#beforeNioSelect}. */
  public boolean beforeNioSelect(final Object selector, final long timeoutMillis) throws Throwable {
    return dispatcher.beforeNioSelect(selector, timeoutMillis);
  }

  /** Delegates to {@link ChaosDispatcher#beforeNioChannelOp}. */
  public void beforeNioChannelOp(final String operation, final Object channel) throws Throwable {
    dispatcher.beforeNioChannelOp(operation, channel);
  }

  /** Delegates to {@link ChaosDispatcher#beforeSocketConnect}. */
  public void beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    dispatcher.beforeSocketConnect(socket, socketAddress, timeoutMillis);
  }

  /** Delegates to {@link ChaosDispatcher#beforeSocketAccept}. */
  public void beforeSocketAccept(final Object serverSocket) throws Throwable {
    dispatcher.beforeSocketAccept(serverSocket);
  }

  /** Delegates to {@link ChaosDispatcher#beforeSocketRead}. */
  public void beforeSocketRead(final Object stream) throws Throwable {
    dispatcher.beforeSocketRead(stream);
  }

  /** Delegates to {@link ChaosDispatcher#beforeSocketWrite}. */
  public void beforeSocketWrite(final Object stream, final int len) throws Throwable {
    dispatcher.beforeSocketWrite(stream, len);
  }

  /** Delegates to {@link ChaosDispatcher#beforeSocketClose}. */
  public void beforeSocketClose(final Object socket) throws Throwable {
    dispatcher.beforeSocketClose(socket);
  }

  /** Delegates to {@link ChaosDispatcher#beforeJndiLookup}. */
  public void beforeJndiLookup(final Object context, final String name) throws Throwable {
    dispatcher.beforeJndiLookup(context, name);
  }

  /** Delegates to {@link ChaosDispatcher#beforeObjectSerialize}. */
  public void beforeObjectSerialize(final Object stream, final Object obj) throws Throwable {
    dispatcher.beforeObjectSerialize(stream, obj);
  }

  /** Delegates to {@link ChaosDispatcher#beforeNativeLibraryLoad}. */
  public void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    dispatcher.beforeNativeLibraryLoad(libraryName);
  }

  /** Delegates to {@link ChaosDispatcher#beforeAsyncCancel}. */
  public boolean beforeAsyncCancel(final Object future, final boolean mayInterruptIfRunning)
      throws Throwable {
    return dispatcher.beforeAsyncCancel(future, mayInterruptIfRunning);
  }

  /** Delegates to {@link ChaosDispatcher#beforeZipInflate}. */
  public void beforeZipInflate() throws Throwable {
    dispatcher.beforeZipInflate();
  }

  /** Delegates to {@link ChaosDispatcher#beforeZipDeflate}. */
  public void beforeZipDeflate() throws Throwable {
    dispatcher.beforeZipDeflate();
  }

  /** Delegates to {@link ChaosDispatcher#beforeThreadLocalGet}. */
  public boolean beforeThreadLocalGet(final Object threadLocal) throws Throwable {
    return dispatcher.beforeThreadLocalGet(threadLocal);
  }

  /** Delegates to {@link ChaosDispatcher#beforeThreadLocalSet}. */
  public boolean beforeThreadLocalSet(final Object threadLocal, final Object value)
      throws Throwable {
    return dispatcher.beforeThreadLocalSet(threadLocal, value);
  }

  /** Delegates to {@link ChaosDispatcher#beforeJmxInvoke}. */
  public void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    dispatcher.beforeJmxInvoke(server, objectName, operationName);
  }

  /** Delegates to {@link ChaosDispatcher#beforeJmxGetAttr}. */
  public void beforeJmxGetAttr(final Object server, final Object objectName, final String attribute)
      throws Throwable {
    dispatcher.beforeJmxGetAttr(server, objectName, attribute);
  }

  /** Delegates to {@link ChaosDispatcher#beforeHttpSend}. */
  public boolean beforeHttpSend(final String url, final OperationType opType) throws Throwable {
    return dispatcher.beforeHttpSend(url, opType);
  }

  /** Delegates to {@link ChaosDispatcher#beforeJdbcConnectionAcquire}. */
  public boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    return dispatcher.beforeJdbcConnectionAcquire(poolName);
  }

  /** Delegates to {@link ChaosDispatcher#beforeJdbcStatementExecute}. */
  public boolean beforeJdbcStatementExecute(final String sql) throws Throwable {
    return dispatcher.beforeJdbcStatementExecute(sql);
  }

  /** Delegates to {@link ChaosDispatcher#beforeJdbcPreparedStatement}. */
  public boolean beforeJdbcPreparedStatement(final String sql) throws Throwable {
    return dispatcher.beforeJdbcPreparedStatement(sql);
  }

  /** Delegates to {@link ChaosDispatcher#beforeJdbcTransactionCommit}. */
  public boolean beforeJdbcTransactionCommit() throws Throwable {
    return dispatcher.beforeJdbcTransactionCommit();
  }

  /** Delegates to {@link ChaosDispatcher#beforeJdbcTransactionRollback}. */
  public boolean beforeJdbcTransactionRollback() throws Throwable {
    return dispatcher.beforeJdbcTransactionRollback();
  }

  /** Delegates to {@link ChaosDispatcher#beforeThreadSleep}. */
  public boolean beforeThreadSleep(final long millis) throws Throwable {
    return dispatcher.beforeThreadSleep(millis);
  }

  /** Delegates to {@link ChaosDispatcher#beforeDnsResolve}. */
  public void beforeDnsResolve(final String hostname) throws Throwable {
    dispatcher.beforeDnsResolve(hostname);
  }

  /** Delegates to {@link ChaosDispatcher#beforeSslHandshake}. */
  public void beforeSslHandshake(final Object socket) throws Throwable {
    dispatcher.beforeSslHandshake(socket);
  }

  /** Delegates to {@link ChaosDispatcher#beforeFileIo}. */
  public void beforeFileIo(final String operation, final Object stream) throws Throwable {
    dispatcher.beforeFileIo(operation, stream);
  }

  Optional<Instrumentation> instrumentation() {
    return controlPlane.instrumentation();
  }

  DefaultChaosActivationHandle activateInSession(
      final DefaultChaosSession session, final ChaosScenario scenario) {
    return controlPlane.activateInSession(session, scenario);
  }

  ScenarioRegistry registry() {
    return controlPlane.registry();
  }
}

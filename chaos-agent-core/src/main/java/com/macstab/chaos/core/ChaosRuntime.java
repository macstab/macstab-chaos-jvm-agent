package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosEventListener;
import com.macstab.chaos.api.ChaosMetricsSink;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.api.OperationType;
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
 */
public final class ChaosRuntime implements ChaosControlPlane {
  private final ChaosControlPlaneImpl controlPlane;
  private final ChaosDispatcher dispatcher;

  public ChaosRuntime() {
    this(Clock.systemUTC(), ChaosMetricsSink.NOOP);
  }

  public ChaosRuntime(final Clock clock, final ChaosMetricsSink metricsSink) {
    this.controlPlane = new ChaosControlPlaneImpl(clock, metricsSink);
    this.dispatcher = new ChaosDispatcher(controlPlane);
  }

  public ChaosDispatcher dispatcher() {
    return dispatcher;
  }

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

  public String currentSessionId() {
    return dispatcher.currentSessionId();
  }

  public Runnable decorateExecutorRunnable(
      final String operation, final Object executor, final Runnable task) {
    return dispatcher.decorateExecutorRunnable(operation, executor, task);
  }

  public <T> Callable<T> decorateExecutorCallable(
      final String operation, final Object executor, final Callable<T> task) {
    return dispatcher.decorateExecutorCallable(operation, executor, task);
  }

  public void beforeThreadStart(final Thread thread) throws Throwable {
    dispatcher.beforeThreadStart(thread);
  }

  public void beforeWorkerRun(final Object executor, final Thread worker, final Runnable task)
      throws Throwable {
    dispatcher.beforeWorkerRun(executor, worker, task);
  }

  public void beforeForkJoinTaskRun(final java.util.concurrent.ForkJoinTask<?> task)
      throws Throwable {
    dispatcher.beforeForkJoinTaskRun(task);
  }

  public long adjustScheduleDelay(
      final String operation,
      final Object executor,
      final Object task,
      final long delay,
      final boolean periodic)
      throws Throwable {
    return dispatcher.adjustScheduleDelay(operation, executor, task, delay, periodic);
  }

  public void beforeQueueOperation(final String operation, final Object queue) throws Throwable {
    dispatcher.beforeQueueOperation(operation, queue);
  }

  public Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    return dispatcher.beforeBooleanQueueOperation(operation, queue);
  }

  public Boolean beforeCompletableFutureComplete(
      final String operation, final CompletableFuture<?> future, final Object payload)
      throws Throwable {
    return dispatcher.beforeCompletableFutureComplete(operation, future, payload);
  }

  public void beforeClassLoad(final ClassLoader loader, final String className) throws Throwable {
    dispatcher.beforeClassLoad(loader, className);
  }

  public URL afterResourceLookup(
      final ClassLoader loader, final String name, final URL currentValue) throws Throwable {
    return dispatcher.afterResourceLookup(loader, name, currentValue);
  }

  public Thread decorateShutdownHook(final Thread hook) throws Throwable {
    return dispatcher.decorateShutdownHook(hook);
  }

  public Thread resolveShutdownHook(final Thread original) {
    return dispatcher.resolveShutdownHook(original);
  }

  public void beforeExecutorShutdown(
      final String operation, final Object executor, final long timeoutMillis) throws Throwable {
    dispatcher.beforeExecutorShutdown(operation, executor, timeoutMillis);
  }

  public boolean beforeScheduledTick(
      final Object executor, final Object task, final boolean periodic) throws Throwable {
    return dispatcher.beforeScheduledTick(executor, task, periodic);
  }

  public void beforeMethodEnter(final String className, final String methodName) throws Throwable {
    dispatcher.beforeMethodEnter(className, methodName);
  }

  public Object afterMethodExit(
      final String className,
      final String methodName,
      final Class<?> returnType,
      final Object actualValue)
      throws Throwable {
    return dispatcher.afterMethodExit(className, methodName, returnType, actualValue);
  }

  public long applyClockSkew(final long realValue, final OperationType clockType) {
    return dispatcher.applyClockSkew(realValue, clockType);
  }

  public void setInstrumentation(final Instrumentation inst) {
    controlPlane.setInstrumentation(inst);
  }

  public long adjustClockMillis(final long realMillis) {
    return dispatcher.adjustClockMillis(realMillis);
  }

  public long adjustClockNanos(final long realNanos) {
    return dispatcher.adjustClockNanos(realNanos);
  }

  public java.time.Instant adjustInstantNow(final java.time.Instant realInstant) {
    return dispatcher.adjustInstantNow(realInstant);
  }

  public java.time.LocalDateTime adjustLocalDateTimeNow(final java.time.LocalDateTime realValue) {
    return dispatcher.adjustLocalDateTimeNow(realValue);
  }

  public java.time.ZonedDateTime adjustZonedDateTimeNow(final java.time.ZonedDateTime realValue) {
    return dispatcher.adjustZonedDateTimeNow(realValue);
  }

  public long adjustDateNew(final long realMillis) {
    return dispatcher.adjustDateNew(realMillis);
  }

  public boolean beforeGcRequest() throws Throwable {
    return dispatcher.beforeGcRequest();
  }

  public void beforeExitRequest(final int status) throws Throwable {
    dispatcher.beforeExitRequest(status);
  }

  public void beforeReflectionInvoke(final Object method, final Object target) throws Throwable {
    dispatcher.beforeReflectionInvoke(method, target);
  }

  public void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    dispatcher.beforeDirectBufferAllocate(capacity);
  }

  public void beforeObjectDeserialize(final Object stream) throws Throwable {
    dispatcher.beforeObjectDeserialize(stream);
  }

  public void beforeClassDefine(final Object loader, final String className) throws Throwable {
    dispatcher.beforeClassDefine(loader, className);
  }

  public void beforeMonitorEnter() throws Throwable {
    dispatcher.beforeMonitorEnter();
  }

  public void beforeThreadPark() throws Throwable {
    dispatcher.beforeThreadPark();
  }

  public boolean beforeNioSelect(final Object selector, final long timeoutMillis) throws Throwable {
    return dispatcher.beforeNioSelect(selector, timeoutMillis);
  }

  public void beforeNioChannelOp(final String operation, final Object channel) throws Throwable {
    dispatcher.beforeNioChannelOp(operation, channel);
  }

  public void beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    dispatcher.beforeSocketConnect(socket, socketAddress, timeoutMillis);
  }

  public void beforeSocketAccept(final Object serverSocket) throws Throwable {
    dispatcher.beforeSocketAccept(serverSocket);
  }

  public void beforeSocketRead(final Object stream) throws Throwable {
    dispatcher.beforeSocketRead(stream);
  }

  public void beforeSocketWrite(final Object stream, final int len) throws Throwable {
    dispatcher.beforeSocketWrite(stream, len);
  }

  public void beforeSocketClose(final Object socket) throws Throwable {
    dispatcher.beforeSocketClose(socket);
  }

  public void beforeJndiLookup(final Object context, final String name) throws Throwable {
    dispatcher.beforeJndiLookup(context, name);
  }

  public void beforeObjectSerialize(final Object stream, final Object obj) throws Throwable {
    dispatcher.beforeObjectSerialize(stream, obj);
  }

  public void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    dispatcher.beforeNativeLibraryLoad(libraryName);
  }

  public boolean beforeAsyncCancel(final Object future, final boolean mayInterruptIfRunning)
      throws Throwable {
    return dispatcher.beforeAsyncCancel(future, mayInterruptIfRunning);
  }

  public void beforeZipInflate() throws Throwable {
    dispatcher.beforeZipInflate();
  }

  public void beforeZipDeflate() throws Throwable {
    dispatcher.beforeZipDeflate();
  }

  public boolean beforeThreadLocalGet(final Object threadLocal) throws Throwable {
    return dispatcher.beforeThreadLocalGet(threadLocal);
  }

  public boolean beforeThreadLocalSet(final Object threadLocal, final Object value)
      throws Throwable {
    return dispatcher.beforeThreadLocalSet(threadLocal, value);
  }

  public void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    dispatcher.beforeJmxInvoke(server, objectName, operationName);
  }

  public void beforeJmxGetAttr(final Object server, final Object objectName, final String attribute)
      throws Throwable {
    dispatcher.beforeJmxGetAttr(server, objectName, attribute);
  }

  public boolean beforeHttpSend(final String url, final OperationType opType) throws Throwable {
    return dispatcher.beforeHttpSend(url, opType);
  }

  public boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    return dispatcher.beforeJdbcConnectionAcquire(poolName);
  }

  public boolean beforeJdbcStatementExecute(final String sql) throws Throwable {
    return dispatcher.beforeJdbcStatementExecute(sql);
  }

  public boolean beforeJdbcPreparedStatement(final String sql) throws Throwable {
    return dispatcher.beforeJdbcPreparedStatement(sql);
  }

  public boolean beforeJdbcTransactionCommit() throws Throwable {
    return dispatcher.beforeJdbcTransactionCommit();
  }

  public boolean beforeJdbcTransactionRollback() throws Throwable {
    return dispatcher.beforeJdbcTransactionRollback();
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

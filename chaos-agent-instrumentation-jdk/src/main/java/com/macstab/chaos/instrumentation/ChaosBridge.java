package com.macstab.chaos.instrumentation;

import com.macstab.chaos.core.ChaosRuntime;
import com.macstab.chaos.instrumentation.bridge.BridgeDelegate;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

final class ChaosBridge implements BridgeDelegate {
  private final ChaosRuntime runtime;

  ChaosBridge(final ChaosRuntime runtime) {
    this.runtime = runtime;
  }

  // ── Phase 1 delegations ────────────────────────────────────────────────────

  @Override
  public Runnable decorateExecutorRunnable(
      final String operation, final Object executor, final Runnable task) {
    return runtime.decorateExecutorRunnable(operation, executor, task);
  }

  @Override
  public <T> Callable<T> decorateExecutorCallable(
      final String operation, final Object executor, final Callable<T> task) {
    return runtime.decorateExecutorCallable(operation, executor, task);
  }

  @Override
  public void beforeThreadStart(final Thread thread) throws Throwable {
    runtime.beforeThreadStart(thread);
  }

  @Override
  public void beforeWorkerRun(final Object executor, final Thread worker, final Runnable task)
      throws Throwable {
    runtime.beforeWorkerRun(executor, worker, task);
  }

  @Override
  public void beforeForkJoinTaskRun(final ForkJoinTask<?> task) throws Throwable {
    runtime.beforeForkJoinTaskRun(task);
  }

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

  @Override
  public boolean beforeScheduledTick(
      final Object executor, final Object task, final boolean periodic) throws Throwable {
    return runtime.beforeScheduledTick(executor, task, periodic);
  }

  @Override
  public void beforeQueueOperation(final String operation, final Object queue) throws Throwable {
    runtime.beforeQueueOperation(operation, queue);
  }

  @Override
  public Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    return runtime.beforeBooleanQueueOperation(operation, queue);
  }

  @Override
  public Boolean beforeCompletableFutureComplete(
      final String operation, final CompletableFuture<?> future, final Object payload)
      throws Throwable {
    return runtime.beforeCompletableFutureComplete(operation, future, payload);
  }

  @Override
  public void beforeClassLoad(final ClassLoader loader, final String className) throws Throwable {
    runtime.beforeClassLoad(loader, className);
  }

  @Override
  public URL afterResourceLookup(
      final ClassLoader loader, final String name, final URL currentValue) throws Throwable {
    return runtime.afterResourceLookup(loader, name, currentValue);
  }

  @Override
  public Thread decorateShutdownHook(final Thread hook) throws Throwable {
    return runtime.decorateShutdownHook(hook);
  }

  @Override
  public Thread resolveShutdownHook(final Thread original) {
    return runtime.resolveShutdownHook(original);
  }

  @Override
  public void beforeExecutorShutdown(
      final String operation, final Object executor, final long timeoutMillis) throws Throwable {
    runtime.beforeExecutorShutdown(operation, executor, timeoutMillis);
  }

  // ── Phase 2 delegations ────────────────────────────────────────────────────

  @Override
  public long adjustClockMillis(final long realMillis) throws Throwable {
    return runtime.adjustClockMillis(realMillis);
  }

  @Override
  public long adjustClockNanos(final long realNanos) throws Throwable {
    return runtime.adjustClockNanos(realNanos);
  }

  @Override
  public boolean beforeGcRequest() throws Throwable {
    return runtime.beforeGcRequest();
  }

  @Override
  public void beforeExitRequest(final int status) throws Throwable {
    runtime.beforeExitRequest(status);
  }

  @Override
  public void beforeReflectionInvoke(final Object method, final Object target) throws Throwable {
    runtime.beforeReflectionInvoke(method, target);
  }

  @Override
  public void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    runtime.beforeDirectBufferAllocate(capacity);
  }

  @Override
  public void beforeObjectDeserialize(final Object stream) throws Throwable {
    runtime.beforeObjectDeserialize(stream);
  }

  @Override
  public void beforeClassDefine(final Object loader, final String className) throws Throwable {
    runtime.beforeClassDefine(loader, className);
  }

  @Override
  public void beforeMonitorEnter() throws Throwable {
    runtime.beforeMonitorEnter();
  }

  @Override
  public void beforeThreadPark() throws Throwable {
    runtime.beforeThreadPark();
  }

  @Override
  public boolean beforeNioSelect(final Object selector, final long timeoutMillis) throws Throwable {
    return runtime.beforeNioSelect(selector, timeoutMillis);
  }

  @Override
  public void beforeNioChannelOp(final String operation, final Object channel) throws Throwable {
    runtime.beforeNioChannelOp(operation, channel);
  }

  @Override
  public void beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    runtime.beforeSocketConnect(socket, socketAddress, timeoutMillis);
  }

  @Override
  public void beforeSocketAccept(final Object serverSocket) throws Throwable {
    runtime.beforeSocketAccept(serverSocket);
  }

  @Override
  public void beforeSocketRead(final Object stream) throws Throwable {
    runtime.beforeSocketRead(stream);
  }

  @Override
  public void beforeSocketWrite(final Object stream, final int len) throws Throwable {
    runtime.beforeSocketWrite(stream, len);
  }

  @Override
  public void beforeSocketClose(final Object socket) throws Throwable {
    runtime.beforeSocketClose(socket);
  }

  @Override
  public void beforeJndiLookup(final Object context, final String name) throws Throwable {
    runtime.beforeJndiLookup(context, name);
  }

  @Override
  public void beforeObjectSerialize(final Object stream, final Object obj) throws Throwable {
    runtime.beforeObjectSerialize(stream, obj);
  }

  @Override
  public void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    runtime.beforeNativeLibraryLoad(libraryName);
  }

  @Override
  public boolean beforeAsyncCancel(final Object future, final boolean mayInterruptIfRunning)
      throws Throwable {
    return runtime.beforeAsyncCancel(future, mayInterruptIfRunning);
  }

  @Override
  public void beforeZipInflate() throws Throwable {
    runtime.beforeZipInflate();
  }

  @Override
  public void beforeZipDeflate() throws Throwable {
    runtime.beforeZipDeflate();
  }

  @Override
  public boolean beforeThreadLocalGet(final Object threadLocal) throws Throwable {
    return runtime.beforeThreadLocalGet(threadLocal);
  }

  @Override
  public boolean beforeThreadLocalSet(final Object threadLocal, final Object value)
      throws Throwable {
    return runtime.beforeThreadLocalSet(threadLocal, value);
  }

  @Override
  public void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    runtime.beforeJmxInvoke(server, objectName, operationName);
  }

  @Override
  public void beforeJmxGetAttr(final Object server, final Object objectName, final String attribute)
      throws Throwable {
    runtime.beforeJmxGetAttr(server, objectName, attribute);
  }
}

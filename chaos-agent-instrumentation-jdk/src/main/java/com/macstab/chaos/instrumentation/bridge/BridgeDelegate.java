package com.macstab.chaos.instrumentation.bridge;

import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

public interface BridgeDelegate {

  // ── Phase 1 methods ────────────────────────────────────────────────────────

  Runnable decorateExecutorRunnable(String operation, Object executor, Runnable task)
      throws Throwable;

  <T> Callable<T> decorateExecutorCallable(String operation, Object executor, Callable<T> task)
      throws Throwable;

  void beforeThreadStart(Thread thread) throws Throwable;

  void beforeWorkerRun(Object executor, Thread worker, Runnable task) throws Throwable;

  void beforeForkJoinTaskRun(ForkJoinTask<?> task) throws Throwable;

  long adjustScheduleDelay(
      String operation, Object executor, Object task, long delay, boolean periodic)
      throws Throwable;

  boolean beforeScheduledTick(Object executor, Object task, boolean periodic) throws Throwable;

  void beforeQueueOperation(String operation, Object queue) throws Throwable;

  Boolean beforeBooleanQueueOperation(String operation, Object queue) throws Throwable;

  Boolean beforeCompletableFutureComplete(
      String operation, CompletableFuture<?> future, Object payload) throws Throwable;

  void beforeClassLoad(ClassLoader loader, String className) throws Throwable;

  URL afterResourceLookup(ClassLoader loader, String name, URL currentValue) throws Throwable;

  Thread decorateShutdownHook(Thread hook) throws Throwable;

  // Non-throwing: resolveShutdownHook is called from RemoveShutdownHookAdvice which does not
  // declare throws Throwable.
  Thread resolveShutdownHook(Thread original);

  void beforeExecutorShutdown(String operation, Object executor, long timeoutMillis)
      throws Throwable;

  // ── Phase 2 methods ────────────────────────────────────────────────────────

  long adjustClockMillis(long realMillis) throws Throwable;

  long adjustClockNanos(long realNanos) throws Throwable;

  /** Returns {@code true} if GC should be suppressed. */
  boolean beforeGcRequest() throws Throwable;

  void beforeExitRequest(int status) throws Throwable;

  void beforeReflectionInvoke(Object method, Object target) throws Throwable;

  void beforeDirectBufferAllocate(int capacity) throws Throwable;

  void beforeObjectDeserialize(Object stream) throws Throwable;

  void beforeClassDefine(Object loader, String className) throws Throwable;

  void beforeMonitorEnter() throws Throwable;

  void beforeThreadPark() throws Throwable;

  /** Returns {@code true} if a spurious wakeup should be injected. */
  boolean beforeNioSelect(Object selector, long timeoutMillis) throws Throwable;

  void beforeNioChannelOp(String operation, Object channel) throws Throwable;

  void beforeSocketConnect(Object socket, Object socketAddress, int timeoutMillis) throws Throwable;

  void beforeSocketAccept(Object serverSocket) throws Throwable;

  void beforeSocketRead(Object stream) throws Throwable;

  void beforeSocketWrite(Object stream, int len) throws Throwable;

  void beforeSocketClose(Object socket) throws Throwable;

  void beforeJndiLookup(Object context, String name) throws Throwable;

  void beforeObjectSerialize(Object stream, Object obj) throws Throwable;

  void beforeNativeLibraryLoad(String libraryName) throws Throwable;

  /** Returns {@code true} if cancel should be suppressed. */
  boolean beforeAsyncCancel(Object future, boolean mayInterruptIfRunning) throws Throwable;

  void beforeZipInflate() throws Throwable;

  void beforeZipDeflate() throws Throwable;

  /** Returns {@code true} if get should return {@code null}. */
  boolean beforeThreadLocalGet(Object threadLocal) throws Throwable;

  /** Returns {@code true} if the set should be suppressed. */
  boolean beforeThreadLocalSet(Object threadLocal, Object value) throws Throwable;

  void beforeJmxInvoke(Object server, Object objectName, String operationName) throws Throwable;

  void beforeJmxGetAttr(Object server, Object objectName, String attribute) throws Throwable;
}

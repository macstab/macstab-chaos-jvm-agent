package io.macstab.chaos.instrumentation.bridge;

import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

public interface BridgeDelegate {
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

  Thread resolveShutdownHook(Thread original);

  void beforeExecutorShutdown(String operation, Object executor, long timeoutMillis)
      throws Throwable;
}

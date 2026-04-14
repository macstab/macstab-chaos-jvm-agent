package io.macstab.chaos.instrumentation;

import io.macstab.chaos.core.ChaosRuntime;
import io.macstab.chaos.instrumentation.bridge.BridgeDelegate;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

final class ChaosBridge implements BridgeDelegate {
  private final ChaosRuntime runtime;

  ChaosBridge(ChaosRuntime runtime) {
    this.runtime = runtime;
  }

  @Override
  public Runnable decorateExecutorRunnable(String operation, Object executor, Runnable task) {
    return runtime.decorateExecutorRunnable(operation, executor, task);
  }

  @Override
  public <T> Callable<T> decorateExecutorCallable(
      String operation, Object executor, Callable<T> task) {
    return runtime.decorateExecutorCallable(operation, executor, task);
  }

  @Override
  public void beforeThreadStart(Thread thread) throws Throwable {
    runtime.beforeThreadStart(thread);
  }

  @Override
  public void beforeWorkerRun(Object executor, Thread worker, Runnable task) throws Throwable {
    runtime.beforeWorkerRun(executor, worker, task);
  }

  @Override
  public void beforeForkJoinTaskRun(ForkJoinTask<?> task) throws Throwable {
    runtime.beforeForkJoinTaskRun(task);
  }

  @Override
  public long adjustScheduleDelay(
      String operation, Object executor, Object task, long delay, boolean periodic)
      throws Throwable {
    return runtime.adjustScheduleDelay(operation, executor, task, delay, periodic);
  }

  @Override
  public boolean beforeScheduledTick(Object executor, Object task, boolean periodic)
      throws Throwable {
    return runtime.beforeScheduledTick(executor, task, periodic);
  }

  @Override
  public void beforeQueueOperation(String operation, Object queue) throws Throwable {
    runtime.beforeQueueOperation(operation, queue);
  }

  @Override
  public Boolean beforeBooleanQueueOperation(String operation, Object queue) throws Throwable {
    return runtime.beforeBooleanQueueOperation(operation, queue);
  }

  @Override
  public Boolean beforeCompletableFutureComplete(
      String operation, CompletableFuture<?> future, Object payload) throws Throwable {
    return runtime.beforeCompletableFutureComplete(operation, future, payload);
  }

  @Override
  public void beforeClassLoad(ClassLoader loader, String className) throws Throwable {
    runtime.beforeClassLoad(loader, className);
  }

  @Override
  public URL afterResourceLookup(ClassLoader loader, String name, URL currentValue)
      throws Throwable {
    return runtime.afterResourceLookup(loader, name, currentValue);
  }

  @Override
  public Thread decorateShutdownHook(Thread hook) throws Throwable {
    return runtime.decorateShutdownHook(hook);
  }

  @Override
  public Thread resolveShutdownHook(Thread original) {
    return runtime.resolveShutdownHook(original);
  }

  @Override
  public void beforeExecutorShutdown(String operation, Object executor, long timeoutMillis)
      throws Throwable {
    runtime.beforeExecutorShutdown(operation, executor, timeoutMillis);
  }
}

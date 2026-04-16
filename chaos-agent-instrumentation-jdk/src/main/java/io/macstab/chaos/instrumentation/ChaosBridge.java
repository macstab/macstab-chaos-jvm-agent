package io.macstab.chaos.instrumentation;

import io.macstab.chaos.core.ChaosRuntime;
import io.macstab.chaos.instrumentation.bridge.BridgeDelegate;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

final class ChaosBridge implements BridgeDelegate {
  private final ChaosRuntime runtime;

  ChaosBridge(final ChaosRuntime runtime) {
    this.runtime = runtime;
  }

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
}

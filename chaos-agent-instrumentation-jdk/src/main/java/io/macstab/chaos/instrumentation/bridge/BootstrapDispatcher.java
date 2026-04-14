package io.macstab.chaos.instrumentation.bridge;

import java.lang.invoke.MethodHandle;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

public final class BootstrapDispatcher {
  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  private static volatile Object delegate;
  private static volatile MethodHandle[] handles;

  public static final int DECORATE_EXECUTOR_RUNNABLE = 0;
  public static final int DECORATE_EXECUTOR_CALLABLE = 1;
  public static final int BEFORE_THREAD_START = 2;
  public static final int BEFORE_WORKER_RUN = 3;
  public static final int BEFORE_FORK_JOIN_TASK_RUN = 4;
  public static final int ADJUST_SCHEDULE_DELAY = 5;
  public static final int BEFORE_SCHEDULED_TICK = 6;
  public static final int BEFORE_QUEUE_OPERATION = 7;
  public static final int BEFORE_BOOLEAN_QUEUE_OPERATION = 8;
  public static final int BEFORE_COMPLETABLE_FUTURE_COMPLETE = 9;
  public static final int BEFORE_CLASS_LOAD = 10;
  public static final int AFTER_RESOURCE_LOOKUP = 11;
  public static final int DECORATE_SHUTDOWN_HOOK = 12;
  public static final int RESOLVE_SHUTDOWN_HOOK = 13;
  public static final int BEFORE_EXECUTOR_SHUTDOWN = 14;
  public static final int HANDLE_COUNT = 15;

  private BootstrapDispatcher() {}

  public static void install(Object bridgeDelegate, MethodHandle[] methodHandles) {
    delegate = bridgeDelegate;
    handles = methodHandles;
  }

  public static Runnable decorateExecutorRunnable(String operation, Object executor, Runnable task)
      throws Throwable {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          return (d == null || h == null)
              ? task
              : (Runnable) h[DECORATE_EXECUTOR_RUNNABLE].invoke(d, operation, executor, task);
        },
        task);
  }

  public static <T> Callable<T> decorateExecutorCallable(
      String operation, Object executor, Callable<T> task) throws Throwable {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          if (d == null || h == null) return task;
          @SuppressWarnings("unchecked")
          Callable<T> result =
              (Callable<T>) h[DECORATE_EXECUTOR_CALLABLE].invoke(d, operation, executor, task);
          return result;
        },
        task);
  }

  public static void beforeThreadStart(Thread thread) throws Throwable {
    invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_THREAD_START].invoke(d, thread);
          }
          return null;
        },
        null);
  }

  public static void beforeWorkerRun(Object executor, Thread worker, Runnable task)
      throws Throwable {
    invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_WORKER_RUN].invoke(d, executor, worker, task);
          }
          return null;
        },
        null);
  }

  public static void beforeForkJoinTaskRun(ForkJoinTask<?> task) throws Throwable {
    invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_FORK_JOIN_TASK_RUN].invoke(d, task);
          }
          return null;
        },
        null);
  }

  public static long adjustScheduleDelay(
      String operation, Object executor, Object task, long delay, boolean periodic)
      throws Throwable {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          return (d == null || h == null)
              ? delay
              : (long) h[ADJUST_SCHEDULE_DELAY].invoke(d, operation, executor, task, delay, periodic);
        },
        delay);
  }

  public static boolean beforeScheduledTick(Object executor, Object task, boolean periodic)
      throws Throwable {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          return (d == null || h == null)
              || (boolean) h[BEFORE_SCHEDULED_TICK].invoke(d, executor, task, periodic);
        },
        true);
  }

  public static void beforeQueueOperation(String operation, Object queue) throws Throwable {
    invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_QUEUE_OPERATION].invoke(d, operation, queue);
          }
          return null;
        },
        null);
  }

  public static Boolean beforeBooleanQueueOperation(String operation, Object queue)
      throws Throwable {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          return (d == null || h == null)
              ? null
              : (Boolean) h[BEFORE_BOOLEAN_QUEUE_OPERATION].invoke(d, operation, queue);
        },
        null);
  }

  public static Boolean beforeCompletableFutureComplete(
      String operation, CompletableFuture<?> future, Object payload) throws Throwable {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          return (d == null || h == null)
              ? null
              : (Boolean)
                  h[BEFORE_COMPLETABLE_FUTURE_COMPLETE].invoke(d, operation, future, payload);
        },
        null);
  }

  public static void beforeClassLoad(ClassLoader loader, String className) throws Throwable {
    invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_CLASS_LOAD].invoke(d, loader, className);
          }
          return null;
        },
        null);
  }

  public static URL afterResourceLookup(ClassLoader loader, String name, URL currentValue)
      throws Throwable {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          return (d == null || h == null)
              ? currentValue
              : (URL) h[AFTER_RESOURCE_LOOKUP].invoke(d, loader, name, currentValue);
        },
        currentValue);
  }

  public static Thread decorateShutdownHook(Thread hook) throws Throwable {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          return (d == null || h == null)
              ? hook
              : (Thread) h[DECORATE_SHUTDOWN_HOOK].invoke(d, hook);
        },
        hook);
  }

  public static Thread resolveShutdownHook(Thread original) {
    return invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          return (d == null || h == null)
              ? original
              : (Thread) h[RESOLVE_SHUTDOWN_HOOK].invoke(d, original);
        },
        original);
  }

  public static void beforeExecutorShutdown(String operation, Object executor, long timeoutMillis)
      throws Throwable {
    invoke(
        () -> {
          MethodHandle[] h = handles;
          Object d = delegate;
          if (d != null && h != null) {
            h[BEFORE_EXECUTOR_SHUTDOWN].invoke(d, operation, executor, timeoutMillis);
          }
          return null;
        },
        null);
  }

  private static <T> T invoke(ThrowingSupplier<T> supplier, T fallback) {
    if (DEPTH.get() > 0) {
      return fallback;
    }
    DEPTH.set(DEPTH.get() + 1);
    try {
      return supplier.get();
    } catch (Throwable throwable) {
      sneakyThrow(throwable);
      return fallback;
    } finally {
      int next = DEPTH.get() - 1;
      if (next == 0) {
        DEPTH.remove();
      } else {
        DEPTH.set(next);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
    throw (T) throwable;
  }

  @FunctionalInterface
  interface ThrowingSupplier<T> {
    T get() throws Throwable;
  }
}

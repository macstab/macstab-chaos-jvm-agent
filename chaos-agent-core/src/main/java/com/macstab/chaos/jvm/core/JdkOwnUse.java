package com.macstab.chaos.jvm.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The JDK's own uses of general primitives that are not a target (L3a in {@code
 * docs/semantics.md}), as an enumerated list: the predicates the domain dispatchers and {@link
 * SelectorMatcher} apply after their idle check. It is not a general rule: a JDK use that is not
 * listed here stays reachable. The scope-only brackets of the list (class loading, the temporary
 * direct buffers, the direct memory reservation, file mapping and, on JDK 21, the {@code
 * CompletableFuture} timer submission) live in the instrumentation ({@code SCOPE_JDK_INTERNAL}).
 */
final class JdkOwnUse {

  /** The {@code CompletableFuture} timers of {@code orTimeout} / {@code completeOnTimeout}. */
  private static final String TIMEOUT = "java.util.concurrent.CompletableFuture$Timeout";

  /** The JDK 21 timer of {@code completeOnTimeout}. */
  private static final String DELAYED_COMPLETER =
      "java.util.concurrent.CompletableFuture$DelayedCompleter";

  /**
   * The JDK-private {@code ThreadLocal} subclasses (JDK 21 and 25, {@code java.base}). Never {@code
   * ThreadLocal}, {@code InheritableThreadLocal} or {@code ThreadLocal$SuppliedThreadLocal}: the
   * application creates those.
   */
  private static final Set<String> JDK_THREAD_LOCALS =
      Set.of(
          "java.util.concurrent.Exchanger$Participant",
          "java.util.concurrent.locks.ReentrantReadWriteLock$Sync$ThreadLocalHoldCounter",
          "jdk.internal.foreign.BufferStack$1",
          "jdk.internal.jimage.ImageBufferCache$1",
          "jdk.internal.math.FloatingDecimal$1",
          "jdk.internal.misc.CarrierThreadLocal",
          "jdk.internal.misc.TerminatingThreadLocal",
          "jdk.internal.misc.TerminatingThreadLocal$1",
          "sun.net.www.protocol.http.NegotiateAuthentication$1",
          "sun.nio.ch.Invoker$1",
          "sun.nio.ch.IOVecWrapper$1",
          "sun.nio.ch.Util$1",
          "sun.nio.fs.NativeBuffers$1");

  /** The lock every {@code ThreadPoolExecutor} worker takes around each task. */
  private static final String POOL_WORKER = "java.util.concurrent.ThreadPoolExecutor$Worker";

  /** The thread that runs the JDK {@code HttpClient}'s selector loop. */
  private static final String HTTP_CLIENT_SELECTOR =
      "jdk.internal.net.http.HttpClientImpl$SelectorManager";

  /**
   * Walks to the caller of a {@code Method.invoke}: reflection frames are shown, so the walk finds
   * {@code Method.invoke} itself, and classes are retained, so their loader can be checked.
   */
  private static final StackWalker REFLECTION_CALLERS =
      StackWalker.getInstance(
          Set.of(
              StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_REFLECT_FRAMES));

  /**
   * Walks the frames between {@code Thread.interrupt()} and a channel's close: the JDK's
   * interruptor ({@code postInterrupt} → {@code close} on JDK 25, {@code implCloseChannel} on JDK
   * 21), the advice, the bridge and the dispatch.
   */
  private static final StackWalker INTERRUPT_CALLERS =
      StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

  /** How many frames {@link #INTERRUPT_CALLERS} looks at for {@code Thread.interrupt()}. */
  private static final long INTERRUPT_CLOSE_FRAMES = 24L;

  /** The agent's own MBean domain (L3). */
  private static final String AGENT_MBEAN_DOMAIN = "com.macstab.chaos.jvm";

  private JdkOwnUse() {}

  /**
   * Whether {@code task} is a JDK {@code CompletableFuture} timer ({@code $Timeout}, and on JDK 21
   * {@code $DelayedCompleter}), whose tick is the JDK's own scheduling.
   *
   * @param task the unwrapped task, or {@code null}
   * @return {@code true} for a JDK timer
   */
  static boolean isCompletableFutureTimer(final @Nullable Object task) {
    if (task == null) {
      return false;
    }
    final String name = task.getClass().getName();
    return TIMEOUT.equals(name) || DELAYED_COMPLETER.equals(name);
  }

  /**
   * Whether {@code task} is JDK 25's {@code DelayScheduler$ScheduledForkJoinTask} running a {@code
   * CompletableFuture$Timeout}. A {@code delayedExecutor} task ({@code $TaskSubmitter}) is not. The
   * field is read reflectively ({@code java.util.concurrent} is opened to the agent); when it
   * cannot be read the task is not skipped.
   *
   * @param task the fork-join task, or {@code null}
   * @return {@code true} for the JDK's own timer task
   */
  static boolean isForkJoinTimerTask(final @Nullable Object task) {
    final Field runnable = ScheduledTaskRunnable.FIELD;
    if (task == null || runnable == null || runnable.getDeclaringClass() != task.getClass()) {
      return false;
    }
    try {
      final Object command = runnable.get(task);
      return command != null && TIMEOUT.equals(command.getClass().getName());
    } catch (final IllegalAccessException | RuntimeException unreadable) {
      return false;
    }
  }

  /**
   * Whether {@code className} is one of the enumerated JDK-private {@code ThreadLocal} classes.
   *
   * @param className the ThreadLocal's class name, or {@code null}
   * @return {@code true} for a listed class
   */
  static boolean isJdkThreadLocal(final @Nullable String className) {
    return className != null && JDK_THREAD_LOCALS.contains(className);
  }

  /**
   * Whether {@code className} is the lock of a {@code ThreadPoolExecutor} worker.
   *
   * @param className the lock's class name, or {@code null}
   * @return {@code true} for {@code ThreadPoolExecutor$Worker}
   */
  static boolean isPoolWorkerLock(final @Nullable String className) {
    return POOL_WORKER.equals(className);
  }

  /**
   * Whether {@code path} is a random device the JDK's {@code SecureRandom} reads.
   *
   * @param path the file path, or {@code null}
   * @return {@code true} for {@code /dev/random} and {@code /dev/urandom}
   */
  static boolean isRandomDevice(final @Nullable String path) {
    return "/dev/random".equals(path) || "/dev/urandom".equals(path);
  }

  /**
   * Whether the current thread runs the JDK {@code HttpClient}'s selector loop.
   *
   * @return {@code true} on an {@code HttpClientImpl$SelectorManager} thread
   */
  static boolean onHttpClientSelectorThread() {
    return HTTP_CLIENT_SELECTOR.equals(Thread.currentThread().getClass().getName());
  }

  /**
   * Whether {@code future} is a JDK-internal future ({@code jdk.internal.*}, such as the {@code
   * HttpClient}'s). Such futures cannot be named by a selector, so they are skipped.
   *
   * @param futureClassName the future's class name
   * @return {@code true} for a {@code jdk.internal.*} class
   */
  static boolean isJdkInternalFuture(final String futureClassName) {
    return futureClassName.startsWith("jdk.internal.");
  }

  /**
   * Whether {@code method} is a serialization hook the object streams call reflectively: {@code
   * writeObject(ObjectOutputStream)}, {@code readObject(ObjectInputStream)}, {@code
   * readObjectNoData()} (all {@code void}), {@code writeReplace()} or {@code readResolve()} (both
   * returning {@code Object}), not static.
   *
   * @param method the reflected method, or {@code null}
   * @return {@code true} for a serialization hook
   */
  static boolean isSerializationHook(final @Nullable Object method) {
    if (!(method instanceof Method reflected) || Modifier.isStatic(reflected.getModifiers())) {
      return false;
    }
    final Class<?>[] parameters = reflected.getParameterTypes();
    final Class<?> returned = reflected.getReturnType();
    return switch (reflected.getName()) {
      case "writeObject" ->
          returned == void.class
              && parameters.length == 1
              && parameters[0] == java.io.ObjectOutputStream.class;
      case "readObject" ->
          returned == void.class
              && parameters.length == 1
              && parameters[0] == java.io.ObjectInputStream.class;
      case "readObjectNoData" -> returned == void.class && parameters.length == 0;
      case "writeReplace", "readResolve" -> returned == Object.class && parameters.length == 0;
      default -> false;
    };
  }

  /**
   * Whether the innermost {@code Method.invoke} on the current thread's stack was called by a JDK
   * class, one of a named module of the bootstrap or platform class loader: {@code EnumSet} reading
   * an enum's constants, the TLS provider's set-up behind {@code SSLContext}. Walks the stack, so
   * it runs only for a call a scenario selects.
   *
   * @return {@code true} when that caller is a JDK class; {@code false} when it is not, or when no
   *     {@code Method.invoke} is on the stack
   */
  static boolean isJdkReflectionCaller() {
    return REFLECTION_CALLERS.walk(
        frames ->
            frames
                .dropWhile(
                    frame ->
                        frame.getDeclaringClass() != Method.class
                            || !"invoke".equals(frame.getMethodName()))
                .skip(1)
                .findFirst()
                .map(caller -> isJdkClass(caller.getDeclaringClass()))
                .orElse(false));
  }

  /**
   * Whether {@code type} is a JDK class: one of a named module of the bootstrap or platform class
   * loader. A class appended to the boot class path ({@code -Xbootclasspath/a}, {@code
   * appendToBootstrapClassLoaderSearch}) is in the boot loader's unnamed module: an application's
   * or the agent's, not the JDK's (the agent's own calls are excluded by the dispatch depth).
   *
   * @param type the class
   * @return {@code true} for a class of a named module of the bootstrap or platform class loader
   */
  @SuppressWarnings("ReferenceEquality") // a class loader's identity
  static boolean isJdkClass(final Class<?> type) {
    final ClassLoader loader = type.getClassLoader();
    return (loader == null || loader == ClassLoader.getPlatformClassLoader())
        && type.getModule().isNamed();
  }

  /**
   * Whether the current thread closes a channel from inside {@code Thread.interrupt()}: the JDK
   * closes the interruptible channel a thread is blocked on from the interrupting thread, under the
   * channel's {@code closeLock} (and on JDK 21 the target's {@code interruptLock}), and swallows
   * any failure. A delay or gate there would block {@code interrupt()} and the interrupted thread.
   * Walks the stack, so it runs only for a close a scenario may select.
   *
   * @return {@code true} when {@code Thread.interrupt()} is among the closest callers
   */
  static boolean isInterruptClose() {
    return INTERRUPT_CALLERS.walk(
        frames ->
            frames
                .limit(INTERRUPT_CLOSE_FRAMES)
                .anyMatch(
                    frame ->
                        frame.getDeclaringClass() == Thread.class
                            && "interrupt".equals(frame.getMethodName())));
  }

  /**
   * Whether {@code objectName} names an MBean of the agent's own domain (L3).
   *
   * @param objectName the MBean's {@code ObjectName}, or {@code null}
   * @return {@code true} for the {@code com.macstab.chaos.jvm} domain
   */
  static boolean isAgentMBean(final @Nullable Object objectName) {
    return objectName instanceof javax.management.ObjectName name
        && AGENT_MBEAN_DOMAIN.equals(name.getDomain());
  }

  /**
   * Resolves {@code DelayScheduler$ScheduledForkJoinTask.runnable} on first use, after the agent
   * opened {@code java.util.concurrent}; {@code null} on JDKs without the class or without access.
   */
  private static final class ScheduledTaskRunnable {
    static final @Nullable Field FIELD = resolve();

    private ScheduledTaskRunnable() {}

    private static @Nullable Field resolve() {
      try {
        final Field field =
            Class.forName("java.util.concurrent.DelayScheduler$ScheduledForkJoinTask", false, null)
                .getDeclaredField("runnable");
        field.setAccessible(true);
        return field;
      } catch (final ReflectiveOperationException | RuntimeException | LinkageError absent) {
        return null;
      }
    }
  }
}

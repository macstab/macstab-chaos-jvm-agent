package com.macstab.chaos.jvm.instrumentation;

import static com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.advise;

import java.util.concurrent.Callable;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The hook groups for threads, executors, queues, futures, locks and parking, {@code ThreadLocal}
 * and shutdown hooks: which methods of which types receive which advice. {@link
 * JdkInstrumentationInstaller.HookGroup} weaves them on demand.
 */
final class ConcurrencyHooks {
  private ConcurrencyHooks() {}

  /**
   * Thread.start instrumentation.
   *
   * <p>Targets {@code java.lang.Thread.start()} for platform threads <i>and</i> {@code
   * java.lang.VirtualThread.start()} for virtual threads. Virtual threads override {@code
   * Thread.start()} (it is not {@code final}) and dispatch directly to {@code
   * VirtualThread.start(DEFAULT_SCHEDULER)} without calling {@code Thread.start()}, so a
   * Thread-only weave silently misses every {@code VIRTUAL_THREAD_START} event. The shared advice
   * captures {@code @Advice.This Thread} and the runtime dispatcher emits {@code THREAD_START} vs
   * {@code VIRTUAL_THREAD_START} based on {@code Thread.isVirtual()}.
   */
  static AgentBuilder applyThreadStartTransformations(final AgentBuilder builder) {
    return builder
        // Worker creation: a thread started inside addWorker is a pool worker (see
        // BootstrapDispatcher.SCOPE_POOL_WORKER). Tomcat ships its own copy of the class.
        .type(
            ElementMatchers.namedOneOf(
                "java.util.concurrent.ThreadPoolExecutor",
                "org.apache.tomcat.util.threads.ThreadPoolExecutor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ThreadAdvice.PoolWorkerScopeAdvice.class,
                        ElementMatchers.named("addWorker").and(ElementMatchers.takesArguments(2)))))
        .type(ElementMatchers.named("java.lang.Thread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ThreadAdvice.StartAdvice.class,
                        ElementMatchers.named("start")
                            .and(
                                ElementMatchers.takesArguments(0)
                                    .or(
                                        ElementMatchers.takesArguments(1)
                                            .and(
                                                ElementMatchers.takesArgument(
                                                    0,
                                                    ElementMatchers.named(
                                                        "jdk.internal.vm.ThreadContainer"))))))))
        .type(ElementMatchers.named("java.lang.Thread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ThreadAdvice.RunSessionAdvice.class,
                        ElementMatchers.named("run").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.lang.VirtualThread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ThreadAdvice.RunSessionAdvice.class,
                            ElementMatchers.named("run")
                                .and(ElementMatchers.takesArguments(Runnable.class))))
                    .visit(
                        advise(
                            ThreadAdvice.MountSessionAdvice.class,
                            ElementMatchers.named("mount").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.lang.VirtualThread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ThreadAdvice.StartAdvice.class,
                        ElementMatchers.named("start")
                            .and(
                                ElementMatchers.takesArguments(0)
                                    .or(
                                        ElementMatchers.takesArguments(1)
                                            .and(
                                                ElementMatchers.takesArgument(
                                                    0,
                                                    ElementMatchers.named(
                                                        "jdk.internal.vm.ThreadContainer"))))))));
  }

  /** ThreadPoolExecutor execute/beforeExecute/shutdown/shutdownNow/awaitTermination. */
  static AgentBuilder applyThreadPoolExecutorTransformations(final AgentBuilder builder) {
    final net.bytebuddy.matcher.ElementMatcher.Junction<
            net.bytebuddy.description.method.MethodDescription>
        beforeExecute =
            ElementMatchers.named("beforeExecute")
                .and(ElementMatchers.takesArguments(Thread.class, Runnable.class));
    return builder
        // A subclass that overrides beforeExecute (Spring's executors, without calling super):
        // the JDK method never runs for its tasks. Matched by the method it declares first, so
        // the superclass walk runs only for those few classes.
        .type(
            ElementMatchers.declaresMethod(beforeExecute)
                .and(
                    TypeMatchers.superClass(
                        ElementMatchers.named("java.util.concurrent.ThreadPoolExecutor"))))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ExecutorAdvice.BeforeExecuteAdvice.class,
                        beforeExecute.and(ElementMatchers.not(ElementMatchers.isAbstract())))))
        .type(ElementMatchers.named("java.util.concurrent.ThreadPoolExecutor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ExecutorAdvice.ExecuteAdvice.class,
                            ElementMatchers.named("execute")
                                .and(ElementMatchers.takesArguments(Runnable.class))))
                    .visit(
                        advise(
                            ExecutorAdvice.BeforeExecuteAdvice.class,
                            ElementMatchers.named("beforeExecute")
                                .and(ElementMatchers.takesArguments(Thread.class, Runnable.class))))
                    .visit(
                        advise(
                            ExecutorAdvice.ShutdownAdvice.class,
                            ElementMatchers.named("shutdown")
                                .and(ElementMatchers.takesArguments(0))))
                    // shutdownNow() intercepts the forced-shutdown variant that interrupts
                    // in-flight tasks and returns the undrained queue. Omitting it left any
                    // selector targeting EXECUTOR_SHUTDOWN silently missing shutdown paths
                    // that went through shutdownNow instead of shutdown.
                    .visit(
                        advise(
                            ExecutorAdvice.ShutdownNowAdvice.class,
                            ElementMatchers.named("shutdownNow")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            ExecutorAdvice.AwaitTerminationAdvice.class,
                            ElementMatchers.named("awaitTermination")
                                .and(
                                    ElementMatchers.takesArguments(
                                        long.class, java.util.concurrent.TimeUnit.class)))));
  }

  /**
   * {@code execute} / {@code submit} of a ScheduledThreadPoolExecutor and of the
   * virtual-thread-per- task executor ({@code java.util.concurrent.ThreadPerTaskExecutor}):
   * EXECUTOR_SUBMIT, as a ThreadPoolExecutor's {@code execute}. ForkJoinPool (and its JDK 25 {@code
   * schedule*}) is not hooked.
   */
  static AgentBuilder applyExecutorSubmitTransformations(final AgentBuilder builder) {
    return builder
        .type(
            ElementMatchers.namedOneOf(
                "java.util.concurrent.ScheduledThreadPoolExecutor",
                "java.util.concurrent.ThreadPerTaskExecutor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ExecutorAdvice.SubmitRunnableAdvice.class,
                            ElementMatchers.namedOneOf("execute", "submit")
                                .and(ElementMatchers.takesArgument(0, Runnable.class))))
                    .visit(
                        advise(
                            ExecutorAdvice.SubmitCallableAdvice.class,
                            ElementMatchers.named("submit")
                                .and(ElementMatchers.takesArguments(Callable.class)))));
  }

  /** ScheduledThreadPoolExecutor schedule / scheduleAtFixedRate / scheduleWithFixedDelay. */
  static AgentBuilder applyScheduledExecutorTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.ScheduledThreadPoolExecutor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ScheduledExecutorAdvice.ScheduleRunnableAdvice.class,
                            ElementMatchers.named("schedule")
                                .and(
                                    ElementMatchers.takesArguments(
                                        Runnable.class,
                                        long.class,
                                        java.util.concurrent.TimeUnit.class))))
                    .visit(
                        advise(
                            ScheduledExecutorAdvice.ScheduleCallableAdvice.class,
                            ElementMatchers.named("schedule")
                                .and(
                                    ElementMatchers.takesArguments(
                                        Callable.class,
                                        long.class,
                                        java.util.concurrent.TimeUnit.class))))
                    .visit(
                        advise(
                            ScheduledExecutorAdvice.PeriodicAdvice.class,
                            ElementMatchers.named("scheduleAtFixedRate")
                                .and(
                                    ElementMatchers.takesArguments(
                                        Runnable.class,
                                        long.class,
                                        long.class,
                                        java.util.concurrent.TimeUnit.class))))
                    .visit(
                        advise(
                            ScheduledExecutorAdvice.PeriodicAdvice.class,
                            ElementMatchers.named("scheduleWithFixedDelay")
                                .and(
                                    ElementMatchers.takesArguments(
                                        Runnable.class,
                                        long.class,
                                        long.class,
                                        java.util.concurrent.TimeUnit.class)))))
        // L3a, JDK 21 only (absent on 25): the CompletableFuture timers' own submission.
        .type(ElementMatchers.named("java.util.concurrent.CompletableFuture$Delayer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ScheduledExecutorAdvice.JdkTimerDelayAdvice.class,
                        ElementMatchers.named("delay")
                            .and(
                                ElementMatchers.takesArguments(
                                    Runnable.class,
                                    long.class,
                                    java.util.concurrent.TimeUnit.class)))));
  }

  /** ForkJoinTask.doExec instrumentation. */
  static AgentBuilder applyForkJoinTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.ForkJoinTask"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ForkJoinAdvice.DoExecAdvice.class,
                            ElementMatchers.named("doExec").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            ForkJoinAdvice.ForkAdvice.class,
                            ElementMatchers.named("fork").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.util.concurrent.ForkJoinPool"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ForkJoinAdvice.PoolSubmitAdvice.class,
                        ElementMatchers.named("poolSubmit")
                            .and(ElementMatchers.takesArguments(2)))));
  }

  /** BlockingQueue put/take/poll/offer instrumentation. */
  static AgentBuilder applyBlockingQueueTransformations(final AgentBuilder builder) {
    return builder
        .type(
            TypeMatchers.subTypeOf(java.util.concurrent.BlockingQueue.class)
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(advise(QueueAdvice.PutAdvice.class, ElementMatchers.named("put")))
                    .visit(advise(QueueAdvice.TakeAdvice.class, ElementMatchers.named("take")))
                    .visit(
                        advise(
                            QueueAdvice.PollAdvice.class,
                            ElementMatchers.named("poll").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            QueueAdvice.TimedPollAdvice.class,
                            ElementMatchers.named("poll")
                                .and(
                                    ElementMatchers.takesArguments(
                                        long.class, java.util.concurrent.TimeUnit.class))))
                    .visit(
                        advise(
                            QueueAdvice.OfferAdvice.class,
                            ElementMatchers.named("offer")
                                .and(ElementMatchers.takesArguments(1)))));
  }

  /** CompletableFuture complete / completeExceptionally instrumentation. */
  static AgentBuilder applyCompletableFutureTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.CompletableFuture"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            CompletableFutureAdvice.CompleteAdvice.class,
                            ElementMatchers.named("complete")
                                .and(ElementMatchers.takesArguments(1))))
                    .visit(
                        advise(
                            CompletableFutureAdvice.CompleteExceptionallyAdvice.class,
                            ElementMatchers.named("completeExceptionally")
                                .and(ElementMatchers.takesArguments(Throwable.class)))))
        // L3a: the completion a JDK timer makes ($DelayedCompleter exists on JDK 21 only).
        .type(
            ElementMatchers.namedOneOf(
                "java.util.concurrent.CompletableFuture$Timeout",
                "java.util.concurrent.CompletableFuture$DelayedCompleter"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        CompletableFutureAdvice.JdkTimerRunAdvice.class,
                        ElementMatchers.named("run").and(ElementMatchers.takesArguments(0)))));
  }

  /** CompletableFuture.cancel instrumentation. */
  static AgentBuilder applyCompletableFutureCancelTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.CompletableFuture"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.AsyncCancelAdvice.class,
                        ElementMatchers.named("cancel")
                            .and(ElementMatchers.takesArguments(boolean.class)))));
  }

  /**
   * LockSupport park / parkNanos / parkUntil (THREAD_PARK). Safe to instrument globally: the chaos
   * runtime never calls LockSupport.park() directly. Internal locks (ManualGate via ReentrantLock)
   * do call park(), but by the time park() fires, BootstrapDispatcher.DEPTH is already > 0 (set
   * before delegating to ChaosRuntime), so the DEPTH guard in invoke() returns the fallback
   * immediately without entering chaos evaluation.
   */
  static AgentBuilder applyLockSupportTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.locks.LockSupport"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    // park(Object blocker) — used by j.u.c. locks that set the blocker
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("park")
                                .and(ElementMatchers.takesArguments(Object.class))))
                    // park() — 0-arg variant used by direct LockSupport callers
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("park").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("parkNanos")
                                .and(ElementMatchers.takesArguments(Object.class, long.class))))
                    // parkNanos(long nanos) — 1-arg variant without blocker
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("parkNanos")
                                .and(ElementMatchers.takesArguments(long.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("parkUntil")
                                .and(ElementMatchers.takesArguments(Object.class, long.class))))
                    // parkUntil(long deadline) — 1-arg variant without blocker
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("parkUntil")
                                .and(ElementMatchers.takesArguments(long.class)))));
  }

  /**
   * AQS.acquire (MONITOR_ENTER proxy). Safe: ChaosRuntime uses ConcurrentHashMap (lock-free) for
   * registry.match(). The one internal AQS user is ManualGate (ReentrantLock), but ManualGate is
   * only entered from applyGate() which runs inside DEPTH > 0 context — the DEPTH guard
   * short-circuits before chaos evaluation begins, preventing recursion.
   */
  static AgentBuilder applyAqsMonitorTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.locks.AbstractQueuedSynchronizer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.MonitorEnterAdvice.class,
                        ElementMatchers.named("acquire")
                            .and(ElementMatchers.takesArguments(int.class)))));
  }

  /**
   * ThreadLocal.get() / set() (THREAD_LOCAL_GET / THREAD_LOCAL_SET). BootstrapDispatcher.DEPTH is
   * itself a ThreadLocal, so these hooks are reentrant by nature. ThreadLocalGetAdvice and
   * ThreadLocalSetAdvice include an identity check (threadLocal == BootstrapDispatcher.DEPTH) that
   * exits before any delegation, breaking the recursion at the only point where it could occur. See
   * ThreadLocalGetAdvice for the full reentrancy analysis.
   */
  static AgentBuilder applyThreadLocalTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.ThreadLocal"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadLocalGetAdvice.class,
                            ElementMatchers.named("get").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadLocalSetAdvice.class,
                            ElementMatchers.named("set").and(ElementMatchers.takesArguments(1)))));
  }

  /** Runtime addShutdownHook instrumentation. */
  static AgentBuilder applyShutdownHookTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.Runtime"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ShutdownAdvice.AddShutdownHookAdvice.class,
                        ElementMatchers.named("addShutdownHook")
                            .and(ElementMatchers.takesArguments(Thread.class)))));
  }
}

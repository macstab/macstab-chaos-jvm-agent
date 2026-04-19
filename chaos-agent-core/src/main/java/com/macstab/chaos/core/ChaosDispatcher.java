package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.Internal;
import com.macstab.chaos.api.OperationType;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Hot-path dispatch component of the chaos runtime.
 *
 * <p>Holds all {@code before*} / {@code after*} / {@code adjust*} / {@code decorate*} entry points
 * that the instrumentation layer calls through the classloader bridge (see {@code
 * BootstrapDispatcher}). Constructed by {@link ChaosControlPlaneImpl} and passed to {@code
 * ChaosBridge}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are fully thread-safe.
 *
 * <h2>API stability</h2>
 *
 * <p><strong>Not part of the stable API.</strong> This class is {@code public} because it is called
 * from the instrumentation classloader bridge and from benchmarks, not because its method surface
 * is frozen. The stable public surface of the chaos-agent is {@code
 * com.macstab.chaos.api.ChaosControlPlane}; bind to that interface instead. The {@link
 * Internal @Internal} marker signals to API linters and bytecode-compat tools that this class can
 * change without notice in any release.
 */
@Internal
public final class ChaosDispatcher {
  private static final Runnable NO_OP_RUNNABLE = () -> {};
  private static final Callable<?> NO_OP_CALLABLE = () -> null;

  // Safe upper bound for an "indefinite" schedule delay in milliseconds.
  // ScheduledThreadPoolExecutor
  // internally converts the delay to nanoseconds and adds it to System.nanoTime() in
  // ScheduledFutureTask.triggerTime; returning Long.MAX_VALUE here overflows that multiplication
  // and
  // produces a negative trigger time that fires immediately — the opposite of the intent. ~100
  // years of millis multiplied by 1_000_000 still fits in a long, so the task effectively never
  // fires but the bookkeeping stays well-defined.
  private static final long INDEFINITE_SCHEDULE_DELAY_MILLIS = TimeUnit.DAYS.toMillis(365L * 100L);

  private final FeatureSet featureSet;
  private final ScopeContext scopeContext;
  private final ScenarioRegistry registry;
  private final Map<Thread, Thread> shutdownHooks;

  ChaosDispatcher(final ChaosControlPlaneImpl controlPlane) {
    this.featureSet = controlPlane.featureSet();
    this.scopeContext = controlPlane.scopeContext();
    this.registry = controlPlane.registry();
    this.shutdownHooks = controlPlane.shutdownHooks();
  }

  public String currentSessionId() {
    return scopeContext.currentSessionId();
  }

  public Runnable decorateExecutorRunnable(
      final String operation, final Object executor, final Runnable task) {
    Objects.requireNonNull(task, "task");
    final String sessionId = scopeContext.currentSessionId();
    final Runnable scoped = sessionId == null ? task : scopeContext.wrap(sessionId, task);
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            task.getClass().getName(),
            null,
            false,
            null,
            null,
            sessionId);
    final RuntimeDecision decision = evaluate(context);
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
      return NO_OP_RUNNABLE;
    }
    try {
      applyPreDecision(decision);
    } catch (final Throwable throwable) {
      throw propagate(throwable);
    }
    return scoped;
  }

  public <T> Callable<T> decorateExecutorCallable(
      final String operation, final Object executor, final Callable<T> task) {
    Objects.requireNonNull(task, "task");
    final String sessionId = scopeContext.currentSessionId();
    final Callable<T> scoped = sessionId == null ? task : scopeContext.wrap(sessionId, task);
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            task.getClass().getName(),
            null,
            false,
            null,
            null,
            sessionId);
    final RuntimeDecision decision = evaluate(context);
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
      @SuppressWarnings("unchecked")
      final Callable<T> noOp = (Callable<T>) NO_OP_CALLABLE;
      return noOp;
    }
    try {
      applyPreDecision(decision);
    } catch (final Throwable throwable) {
      throw propagate(throwable);
    }
    return scoped;
  }

  public void beforeThreadStart(final Thread thread) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            featureSet.isVirtualThread(thread)
                ? OperationType.VIRTUAL_THREAD_START
                : OperationType.THREAD_START,
            Thread.class.getName(),
            null,
            thread == null ? null : thread.getName(),
            false,
            thread == null ? null : thread.isDaemon(),
            thread == null ? null : featureSet.isVirtualThread(thread),
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeWorkerRun(final Object executor, final Thread worker, final Runnable task)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_WORKER_RUN,
            executor.getClass().getName(),
            task == null ? null : task.getClass().getName(),
            worker == null ? null : worker.getName(),
            false,
            worker == null ? null : worker.isDaemon(),
            worker == null ? null : featureSet.isVirtualThread(worker),
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeForkJoinTaskRun(final java.util.concurrent.ForkJoinTask<?> task)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.FORK_JOIN_TASK_RUN,
            "java.util.concurrent.ForkJoinPool",
            task == null ? null : task.getClass().getName(),
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public long adjustScheduleDelay(
      final String operation,
      final Object executor,
      final Object task,
      final long delay,
      final boolean periodic)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            task == null ? null : task.getClass().getName(),
            null,
            periodic,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.RETURN
          || terminalAction.kind() == TerminalKind.SUPPRESS) {
        return INDEFINITE_SCHEDULE_DELAY_MILLIS;
      }
    }
    // Saturating add: a plain `delay + decision.delayMillis()` wraps to a negative long for
    // large inputs, and ScheduledThreadPoolExecutor.schedule with a negative delay fires the
    // task immediately — the exact opposite of the intended chaos. Mirrors the saturating add
    // already present in the sibling adjustExecuteDelay path (see applyPreDecision).
    final long chaosDelay = decision.delayMillis();
    if (chaosDelay > 0L && delay > Long.MAX_VALUE - chaosDelay) {
      return Long.MAX_VALUE;
    }
    if (chaosDelay < 0L && delay < Long.MIN_VALUE - chaosDelay) {
      return Long.MIN_VALUE;
    }
    return delay + chaosDelay;
  }

  public void beforeQueueOperation(final String operation, final Object queue) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            queue == null ? "unknown" : queue.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            queue == null ? "unknown" : queue.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      if (decision.terminalAction().kind() == TerminalKind.THROW) {
        throw decision.terminalAction().throwable();
      }
      if (decision.terminalAction().kind() == TerminalKind.RETURN
          || decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
        return (Boolean) decision.terminalAction().returnValue();
      }
    }
    sleep(decision.delayMillis());
    return null;
  }

  public Boolean beforeCompletableFutureComplete(
      final String operation, final CompletableFuture<?> future, final Object payload)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            CompletableFuture.class.getName(),
            payload == null ? null : payload.getClass().getName(),
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.RETURN) {
        return (Boolean) terminalAction.returnValue();
      }
      if (terminalAction.kind() == TerminalKind.COMPLETE_EXCEPTIONALLY) {
        return future.completeExceptionally(terminalAction.throwable());
      }
    }
    sleep(decision.delayMillis());
    return null;
  }

  public void beforeClassLoad(final ClassLoader loader, final String className) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.CLASS_LOAD,
            loader == null ? "bootstrap" : loader.getClass().getName(),
            null,
            className,
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
  }

  public URL afterResourceLookup(
      final ClassLoader loader, final String name, final URL currentValue) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.RESOURCE_LOAD,
            loader == null ? "bootstrap" : loader.getClass().getName(),
            null,
            name,
            false,
            null,
            null,
            null);
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      if (decision.terminalAction().kind() == TerminalKind.THROW) {
        throw decision.terminalAction().throwable();
      }
      return (URL) decision.terminalAction().returnValue();
    }
    sleep(decision.delayMillis());
    return currentValue;
  }

  public Thread decorateShutdownHook(final Thread hook) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SHUTDOWN_HOOK_REGISTER,
            hook == null ? Thread.class.getName() : hook.getClass().getName(),
            null,
            hook == null ? null : hook.getName(),
            false,
            hook == null ? null : hook.isDaemon(),
            hook == null ? null : featureSet.isVirtualThread(hook),
            null);
    final RuntimeDecision decision = evaluate(context);
    applyPreDecision(decision);
    final Runnable delegate = hook::run;
    final Thread decorated = new Thread(delegate, hook.getName() + "-macstab-chaos-wrapper");
    decorated.setDaemon(hook.isDaemon());
    shutdownHooks.put(hook, decorated);
    return decorated;
  }

  public Thread resolveShutdownHook(final Thread original) {
    // Destructive lookup: when Runtime.removeShutdownHook is called the JVM is about to drop its
    // own reference to the decorated thread, so we must release ours too. Leaving the mapping in
    // place would retain both the user hook and our wrapper for the life of the process — a slow
    // leak for long-running JVMs that churn shutdown-hook registrations (e.g. integration-test
    // suites that stop and restart embedded servers).
    final Thread decorated = shutdownHooks.remove(original);
    return decorated == null ? original : decorated;
  }

  public void beforeExecutorShutdown(
      final String operation, final Object executor, final long timeoutMillis) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            null,
            Long.toString(timeoutMillis),
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
  }

  public boolean beforeScheduledTick(
      final Object executor, final Object task, final boolean periodic) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SCHEDULE_TICK,
            executor == null ? "unknown" : executor.getClass().getName(),
            task == null ? null : task.getClass().getName(),
            null,
            periodic,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      if (decision.terminalAction().kind() == TerminalKind.THROW) {
        throw decision.terminalAction().throwable();
      }
      if (decision.terminalAction().kind() == TerminalKind.RETURN
          || decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
        return false;
      }
    }
    sleep(decision.delayMillis());
    return true;
  }

  public void beforeMethodEnter(final String className, final String methodName) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.METHOD_ENTER,
            className,
            null,
            methodName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public Object afterMethodExit(
      final String className,
      final String methodName,
      final Class<?> returnType,
      final Object actualValue)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.METHOD_EXIT,
            className,
            null,
            methodName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.CORRUPT_RETURN) {
      final ChaosEffect.ReturnValueCorruptionEffect corruptEffect =
          (ChaosEffect.ReturnValueCorruptionEffect) decision.terminalAction().returnValue();
      return ReturnValueCorruptor.corrupt(
          corruptEffect.strategy(), returnType, actualValue, "method-exit");
    }
    sleep(decision.delayMillis());
    return actualValue;
  }

  public long applyClockSkew(final long realValue, final OperationType clockType) {
    final InvocationContext context =
        new InvocationContext(clockType, "java.lang.System", null, null, false, null, null, null);
    final List<ScenarioContribution> contributions = registry.match(context);
    final boolean isNanos = clockType == OperationType.SYSTEM_CLOCK_NANOS;
    long result = realValue;
    for (final ScenarioContribution contribution : contributions) {
      if (contribution.effect() instanceof ChaosEffect.ClockSkewEffect skewEffect) {
        final ClockSkewState state = contribution.controller().clockSkewState();
        if (state != null) {
          result =
              isNanos
                  ? state.applyNanos(skewEffect.mode(), result)
                  : state.applyMillis(skewEffect.mode(), result);
        }
      }
    }
    return result;
  }

  public long adjustClockMillis(final long realMillis) {
    return applyClockSkew(realMillis, OperationType.SYSTEM_CLOCK_MILLIS);
  }

  public long adjustClockNanos(final long realNanos) {
    return applyClockSkew(realNanos, OperationType.SYSTEM_CLOCK_NANOS);
  }

  public java.time.Instant adjustInstantNow(final java.time.Instant realInstant) {
    final long realMillis = realInstant.toEpochMilli();
    final long skewed = applyClockSkew(realMillis, OperationType.INSTANT_NOW);
    final long delta = skewed - realMillis;
    return delta == 0L ? realInstant : realInstant.plusMillis(delta);
  }

  public java.time.LocalDateTime adjustLocalDateTimeNow(final java.time.LocalDateTime realValue) {
    final java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    final long realMillis = realValue.atZone(zone).toInstant().toEpochMilli();
    final long skewed = applyClockSkew(realMillis, OperationType.LOCAL_DATE_TIME_NOW);
    if (skewed == realMillis) {
      return realValue;
    }
    return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(skewed), zone);
  }

  public java.time.ZonedDateTime adjustZonedDateTimeNow(final java.time.ZonedDateTime realValue) {
    final long realMillis = realValue.toInstant().toEpochMilli();
    final long skewed = applyClockSkew(realMillis, OperationType.ZONED_DATE_TIME_NOW);
    if (skewed == realMillis) {
      return realValue;
    }
    return java.time.Instant.ofEpochMilli(skewed).atZone(realValue.getZone());
  }

  public long adjustDateNew(final long realMillis) {
    return applyClockSkew(realMillis, OperationType.DATE_NEW);
  }

  public boolean beforeGcRequest() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SYSTEM_GC_REQUEST,
            "java.lang.System",
            null,
            null,
            false,
            null,
            null,
            null);
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  public void beforeExitRequest(final int status) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SYSTEM_EXIT_REQUEST,
            "java.lang.System",
            null,
            Integer.toString(status),
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
  }

  public void beforeReflectionInvoke(final Object method, final Object target) throws Throwable {
    final String methodName;
    final String declaringClass;
    if (method instanceof java.lang.reflect.Method reflectMethod) {
      methodName = reflectMethod.getName();
      declaringClass = reflectMethod.getDeclaringClass().getName();
    } else {
      methodName = null;
      declaringClass = "java.lang.reflect.Method";
    }
    final InvocationContext context =
        new InvocationContext(
            OperationType.REFLECTION_INVOKE,
            declaringClass,
            target == null ? null : target.getClass().getName(),
            methodName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.DIRECT_BUFFER_ALLOCATE,
            "java.nio.ByteBuffer",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeObjectDeserialize(final Object stream) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.OBJECT_DESERIALIZE,
            stream == null ? "java.io.ObjectInputStream" : stream.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeClassDefine(final Object loader, final String className) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.CLASS_DEFINE,
            loader == null ? "bootstrap" : loader.getClass().getName(),
            null,
            className,
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
  }

  public void beforeMonitorEnter(final Object lock) throws Throwable {
    // When Byte Buddy cannot bind `@Advice.This` (e.g. static synchronized blocks advised via a
    // proxy site, or interception points where the receiver is not a stack slot), `lock` is null
    // and we fall back to the synchronizer hierarchy as a coarse default so MonitorSelector still
    // has *something* to filter on.
    final String targetClassName =
        lock == null
            ? "java.util.concurrent.locks.AbstractQueuedSynchronizer"
            : lock.getClass().getName();
    final InvocationContext context =
        new InvocationContext(
            OperationType.MONITOR_ENTER,
            targetClassName,
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeThreadPark() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_PARK,
            "java.util.concurrent.locks.LockSupport",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public boolean beforeNioSelect(final Object selector, final long timeoutMillis) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.NIO_SELECTOR_SELECT,
            selector == null ? "java.nio.channels.Selector" : selector.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  public void beforeNioChannelOp(final String operation, final Object channel) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            channel == null ? "java.nio.channels.Channel" : channel.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    final String remoteHost = extractRemoteHost(socketAddress);
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_CONNECT,
            socket == null ? "java.net.Socket" : socket.getClass().getName(),
            null,
            remoteHost,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeSocketAccept(final Object serverSocket) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_ACCEPT,
            serverSocket == null ? "java.net.ServerSocket" : serverSocket.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeSocketRead(final Object stream) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_READ,
            stream == null ? "java.net.SocketInputStream" : stream.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeSocketWrite(final Object stream, final int len) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_WRITE,
            stream == null ? "java.net.SocketOutputStream" : stream.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeSocketClose(final Object socket) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_CLOSE,
            socket == null ? "java.net.Socket" : socket.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeJndiLookup(final Object context, final String name) throws Throwable {
    final InvocationContext invocationContext =
        new InvocationContext(
            OperationType.JNDI_LOOKUP,
            context == null ? "javax.naming.InitialContext" : context.getClass().getName(),
            null,
            name,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(invocationContext));
  }

  public void beforeObjectSerialize(final Object stream, final Object obj) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.OBJECT_SERIALIZE,
            stream == null ? "java.io.ObjectOutputStream" : stream.getClass().getName(),
            obj == null ? null : obj.getClass().getName(),
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.NATIVE_LIBRARY_LOAD,
            "java.lang.System",
            null,
            libraryName,
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
  }

  public boolean beforeAsyncCancel(final Object future, final boolean mayInterruptIfRunning)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.ASYNC_CANCEL,
            CompletableFuture.class.getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  public void beforeZipInflate() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.ZIP_INFLATE,
            "java.util.zip.Inflater",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeZipDeflate() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.ZIP_DEFLATE,
            "java.util.zip.Deflater",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public boolean beforeThreadLocalGet(final Object threadLocal) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_LOCAL_GET,
            threadLocal == null ? "java.lang.ThreadLocal" : threadLocal.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  public boolean beforeThreadLocalSet(final Object threadLocal, final Object value)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_LOCAL_SET,
            threadLocal == null ? "java.lang.ThreadLocal" : threadLocal.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  public void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.JMX_INVOKE,
            server == null ? "javax.management.MBeanServer" : server.getClass().getName(),
            null,
            operationName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public void beforeJmxGetAttr(final Object server, final Object objectName, final String attribute)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.JMX_GET_ATTR,
            server == null ? "javax.management.MBeanServer" : server.getClass().getName(),
            null,
            attribute,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  public boolean beforeHttpSend(final String url, final OperationType opType) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            opType, "http.client", null, url, false, null, null, scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        sleep(decision.delayMillis());
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  public boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_CONNECTION_ACQUIRE, "jdbc.pool", poolName);
  }

  public boolean beforeJdbcStatementExecute(final String sql) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_STATEMENT_EXECUTE, "java.sql.Statement", snippet(sql));
  }

  public boolean beforeJdbcPreparedStatement(final String sql) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_PREPARED_STATEMENT, "java.sql.Connection", snippet(sql));
  }

  public boolean beforeJdbcTransactionCommit() throws Throwable {
    return evaluateJdbc(OperationType.JDBC_TRANSACTION_COMMIT, "java.sql.Connection", null);
  }

  public boolean beforeJdbcTransactionRollback() throws Throwable {
    return evaluateJdbc(OperationType.JDBC_TRANSACTION_ROLLBACK, "java.sql.Connection", null);
  }

  /**
   * Called before {@link Thread#sleep(long)}.
   *
   * @param millis the requested sleep duration
   * @return {@code true} if the sleep should be suppressed (caller returns immediately); {@code
   *     false} for normal execution
   * @throws Throwable if an active scenario throws to simulate an {@link InterruptedException}
   */
  public boolean beforeThreadSleep(final long millis) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_SLEEP,
            "java.lang.Thread",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  /**
   * Called before {@link java.net.InetAddress#getByName(String)}, {@link
   * java.net.InetAddress#getAllByName(String)}, or {@link java.net.InetAddress#getLocalHost()}.
   *
   * @param hostname the hostname being resolved; {@code null} for {@code getLocalHost()}
   * @throws Throwable if an active scenario throws to simulate a DNS failure
   */
  public void beforeDnsResolve(final String hostname) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.DNS_RESOLVE,
            "java.net.InetAddress",
            null,
            hostname,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link javax.net.ssl.SSLSocket#startHandshake()} or {@link
   * javax.net.ssl.SSLEngine#beginHandshake()}.
   *
   * @param socket the {@code SSLSocket} or {@code SSLEngine} instance
   * @throws Throwable if an active scenario throws to simulate a TLS handshake failure
   */
  public void beforeSslHandshake(final Object socket) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SSL_HANDSHAKE,
            socket == null ? "javax.net.ssl.SSLSocket" : socket.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before a {@link java.io.FileInputStream#read} or {@link java.io.FileOutputStream#write}
   * call.
   *
   * @param operation {@code "FILE_IO_READ"} or {@code "FILE_IO_WRITE"}
   * @param stream the stream instance
   * @throws Throwable if an active scenario throws to simulate an I/O failure
   */
  public void beforeFileIo(final String operation, final Object stream) throws Throwable {
    final OperationType opType;
    if ("FILE_IO_READ".equals(operation)) {
      opType = OperationType.FILE_IO_READ;
    } else if ("FILE_IO_WRITE".equals(operation)) {
      opType = OperationType.FILE_IO_WRITE;
    } else {
      throw new IllegalArgumentException(
          "Unknown file I/O operation tag '"
              + operation
              + "'; expected FILE_IO_READ or FILE_IO_WRITE");
    }
    final InvocationContext context =
        new InvocationContext(
            opType,
            stream == null ? "java.io.FileInputStream" : stream.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  private boolean evaluateJdbc(
      final OperationType opType, final String targetClassName, final String targetName)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            opType,
            targetClassName,
            null,
            targetName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        sleep(decision.delayMillis());
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  private static String snippet(final String sql) {
    if (sql == null) {
      return null;
    }
    return sql.length() <= 200 ? sql : sql.substring(0, 200);
  }

  private RuntimeDecision evaluate(final InvocationContext context) {
    final List<ScenarioContribution> contributions = registry.match(context);
    if (contributions.isEmpty()) {
      return RuntimeDecision.none();
    }
    long delayMillis = 0L;
    GateAction gateAction = null;
    int gatePrecedence = Integer.MIN_VALUE;
    TerminalAction terminalAction = null;
    int terminalPrecedence = Integer.MIN_VALUE;
    for (final ScenarioContribution contribution : contributions) {
      // Saturating add: naive += with two large per-scenario delays would wrap to a negative
      // value, and the sleep() helper treats negative as "skip sleep" — silently turning an
      // intended multi-minute delay into no delay at all is exactly the kind of quiet bug we
      // should not ship. Cap at Long.MAX_VALUE instead so the intent ("a very long delay") is
      // preserved; downstream Thread.sleep still accepts it.
      final long contributionDelay = contribution.delayMillis();
      if (contributionDelay > 0L && delayMillis > Long.MAX_VALUE - contributionDelay) {
        delayMillis = Long.MAX_VALUE;
      } else {
        delayMillis += contributionDelay;
      }
      if (contribution.effect() instanceof ChaosEffect.GateEffect
          && contribution.scenario().precedence() >= gatePrecedence) {
        gateAction = new GateAction(contribution.controller().gate(), contribution.gateTimeout());
        gatePrecedence = contribution.scenario().precedence();
      }
      final TerminalAction candidate =
          terminalActionFor(
              context.operationType(), contribution.effect(), contribution.scenario());
      if (candidate != null && contribution.scenario().precedence() >= terminalPrecedence) {
        terminalAction = candidate;
        terminalPrecedence = contribution.scenario().precedence();
      }
    }
    return new RuntimeDecision(delayMillis, gateAction, terminalAction);
  }

  private TerminalAction terminalActionFor(
      final OperationType operationType, final ChaosEffect effect, final ChaosScenario scenario) {
    if (effect instanceof ChaosEffect.RejectEffect rejectEffect) {
      return rejectTerminal(operationType, rejectEffect.message());
    }
    if (effect instanceof ChaosEffect.SuppressEffect) {
      return suppressTerminal(operationType);
    }
    if (effect instanceof ChaosEffect.ExceptionalCompletionEffect exceptionalCompletionEffect) {
      return new TerminalAction(
          TerminalKind.COMPLETE_EXCEPTIONALLY,
          null,
          FailureFactory.completionFailure(
              exceptionalCompletionEffect.failureKind(), exceptionalCompletionEffect.message()));
    }
    if (effect instanceof ChaosEffect.ExceptionInjectionEffect injectionEffect) {
      return buildInjectedExceptionTerminal(injectionEffect);
    }
    if (effect instanceof ChaosEffect.ReturnValueCorruptionEffect corruptEffect) {
      return new TerminalAction(TerminalKind.CORRUPT_RETURN, corruptEffect, null);
    }
    if (effect instanceof ChaosEffect.SpuriousWakeupEffect) {
      // A spurious selector wakeup semantically means: return from select() immediately with
      // zero ready keys instead of blocking. beforeNioSelect already implements that exact
      // shape as its SUPPRESS terminal path (return true → advice substitutes `return 0`), so
      // route SpuriousWakeupEffect through SUPPRESS rather than introducing a redundant kind.
      // CompatibilityValidator restricts this effect to NioSelector + NIO_SELECTOR_SELECT, so
      // no other dispatch path can observe this terminal here.
      return suppressTerminal(operationType);
    }
    return null;
  }

  private TerminalAction buildInjectedExceptionTerminal(
      final ChaosEffect.ExceptionInjectionEffect effect) {
    try {
      // Class.forName(name, initialize=false, loader): deliberately skip static initialisation.
      // Running <clinit> of an attacker-chosen class during exception construction would turn
      // the injection path into a class-loading gadget. The package allow-list in
      // ExceptionInjectionEffect is the primary gate; this is defence in depth.
      final Class<?> exClass =
          Class.forName(effect.exceptionClassName(), false, ChaosDispatcher.class.getClassLoader());
      if (!Throwable.class.isAssignableFrom(exClass)) {
        return new TerminalAction(
            TerminalKind.THROW,
            null,
            new IllegalArgumentException(
                "chaos-agent: configured exception class does not extend Throwable: "
                    + effect.exceptionClassName()));
      }
      Throwable instance;
      try {
        instance = (Throwable) exClass.getConstructor(String.class).newInstance(effect.message());
      } catch (final NoSuchMethodException noMsg) {
        instance = (Throwable) exClass.getDeclaredConstructor().newInstance();
      }
      if (!effect.withStackTrace()) {
        instance.setStackTrace(new StackTraceElement[0]);
      }
      return new TerminalAction(TerminalKind.THROW, null, instance);
    } catch (final ReflectiveOperationException reflective) {
      // Preserve the cause chain so operators can see *why* instantiation failed
      // (missing class, inaccessible ctor, ctor threw, etc.) without having to reproduce.
      final RuntimeException wrapped =
          new RuntimeException(
              "chaos-agent: failed to instantiate " + effect.exceptionClassName(), reflective);
      return new TerminalAction(TerminalKind.THROW, null, wrapped);
    }
  }

  private TerminalAction rejectTerminal(final OperationType operationType, final String message) {
    return switch (operationType) {
      case QUEUE_OFFER, ASYNC_COMPLETE, ASYNC_COMPLETE_EXCEPTIONALLY ->
          new TerminalAction(TerminalKind.RETURN, Boolean.FALSE, null);
      case RESOURCE_LOAD -> new TerminalAction(TerminalKind.RETURN, null, null);
      default ->
          new TerminalAction(
              TerminalKind.THROW, null, FailureFactory.reject(operationType, message));
    };
  }

  private TerminalAction suppressTerminal(final OperationType operationType) {
    return switch (operationType) {
      case THREAD_START, VIRTUAL_THREAD_START ->
          new TerminalAction(
              TerminalKind.THROW,
              null,
              new RejectedExecutionException("thread start suppressed by chaos agent"));
      case SYSTEM_EXIT_REQUEST ->
          new TerminalAction(
              TerminalKind.THROW, null, new SecurityException("exit suppressed by chaos agent"));
      case QUEUE_OFFER, ASYNC_COMPLETE, ASYNC_COMPLETE_EXCEPTIONALLY ->
          new TerminalAction(TerminalKind.RETURN, Boolean.FALSE, null);
      case RESOURCE_LOAD -> new TerminalAction(TerminalKind.RETURN, null, null);
      default -> new TerminalAction(TerminalKind.SUPPRESS, null, null);
    };
  }

  private void applyPreDecision(final RuntimeDecision decision) throws Throwable {
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.RETURN) {
        if (Boolean.FALSE.equals(terminalAction.returnValue())) {
          throw new RejectedExecutionException("operation suppressed by chaos agent");
        }
        return;
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return;
      }
      if (terminalAction.kind() == TerminalKind.CORRUPT_RETURN) {
        // CORRUPT_RETURN is only meaningful once a return value exists — it is applied by
        // afterMethodExit, never here. Reaching this point means an effect or policy paired
        // a corrupt-return terminal with a pre-invocation operation type; silently swallowing
        // it would hide the misconfiguration and the scenario would appear to be a harmless
        // no-op. Fail loud so the invariant is enforced and the bug gets attributed to the
        // plan rather than the runtime.
        throw new IllegalStateException(
            "CORRUPT_RETURN terminal reached applyPreDecision — only valid from afterMethodExit");
      }
    }
    sleep(decision.delayMillis());
  }

  private void applyGate(final GateAction gateAction) throws InterruptedException {
    if (gateAction != null) {
      gateAction.gate().await(gateAction.maxBlock());
    }
  }

  private void sleep(final long delayMillis) {
    if (delayMillis <= 0L) {
      return;
    }
    try {
      Thread.sleep(delayMillis);
    } catch (final InterruptedException interruptedException) {
      // Restore the interrupt flag and return. Wrapping this as IllegalStateException would break
      // the caller's interrupt contract: any subsequent blocking call they make will throw
      // InterruptedException anyway, which is the idiomatic signal that the thread should unwind.
      // A chaos-induced delay being cut short is not itself an error.
      Thread.currentThread().interrupt();
    }
  }

  private RuntimeException propagate(final Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    // Gate blocking inside applyPreDecision() can unwind as InterruptedException — for example
    // an operator scenario that parks the caller until an external signal arrives, then a thread
    // pool shutdown interrupts the worker. The previous implementation wrapped it as
    // IllegalStateException and silently dropped the interrupt bit: the worker then unwound with
    // the "chaos interception failed" exception but flag-clear, so the next blocking call
    // (Thread.sleep, Object.wait, Condition.await, …) would sit forever instead of honouring
    // the shutdown. Restore the interrupt flag before wrapping so subsequent blocking calls see
    // it; we still wrap in a RuntimeException because callers advised by Byte Buddy cannot
    // propagate a checked exception without advice-level `throws` declarations we don't want
    // to require everywhere.
    if (throwable instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
    return new IllegalStateException("chaos interception failed", throwable);
  }

  private static String extractRemoteHost(final Object socketAddress) {
    if (socketAddress instanceof InetSocketAddress inetSocketAddress) {
      final String host = inetSocketAddress.getHostString();
      return host != null ? host : inetSocketAddress.toString();
    }
    return socketAddress == null ? null : socketAddress.toString();
  }
}

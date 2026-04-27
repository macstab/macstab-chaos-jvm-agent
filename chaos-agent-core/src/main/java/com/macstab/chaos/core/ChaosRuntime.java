package com.macstab.chaos.core;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationException;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosEventListener;
import com.macstab.chaos.api.ChaosMetricsSink;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.api.OperationType;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.net.URL;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/**
 * Central orchestration hub that connects the instrumentation layer, the scenario registry, and the
 * observability bus.
 *
 * <h2>Role in the architecture</h2>
 *
 * <p>{@code ChaosRuntime} is the single object that lives in the agent classloader and is
 * accessible to all other agent components. It implements {@link ChaosControlPlane} (the public
 * control API) and exposes the full suite of {@code before*} / {@code after*} / {@code adjust*} /
 * {@code decorate*} dispatch methods that the instrumentation layer calls through the classloader
 * bridge (see {@code BootstrapDispatcher}).
 *
 * <h2>Request flow</h2>
 *
 * <ol>
 *   <li>An instrumented JDK method fires its ByteBuddy advice.
 *   <li>The advice calls a static method on {@code BootstrapDispatcher}.
 *   <li>{@code BootstrapDispatcher} calls through the {@code MethodHandle} bridge to the
 *       corresponding method on this class.
 *   <li>This class builds an {@link InvocationContext} and calls {@link
 *       ScenarioRegistry#match(InvocationContext)} to collect active {@link ScenarioContribution}s.
 *   <li>Contributions are merged into a {@link RuntimeDecision} (delay + gate + terminal action).
 *   <li>The decision is executed: delay is slept, gate is awaited, and the terminal action is
 *       dispatched (throw / return override / suppress / complete-exceptionally / corrupt-return).
 * </ol>
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are fully thread-safe and may be called concurrently from arbitrary threads,
 * including JDK internal threads. Shared mutable state (the scenario registry, the shutdown-hook
 * map, the instrumentation reference) uses appropriate concurrency primitives ({@code volatile},
 * {@link java.util.concurrent.ConcurrentHashMap}).
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Instances are created by {@code ChaosAgentBootstrap} and stored in a static {@code
 * AtomicReference}. The runtime has no explicit shutdown step; scenario controllers are stopped
 * individually via {@link ChaosControlPlane} API calls or session close.
 */
public final class ChaosRuntime implements ChaosControlPlane {
  private static final Runnable NO_OP_RUNNABLE = () -> {};
  private static final Callable<?> NO_OP_CALLABLE = () -> null;

  private final Clock clock;
  private final FeatureSet featureSet;
  private final ScopeContext scopeContext;
  private final ObservabilityBus observabilityBus;
  private final ScenarioRegistry registry;
  private final Map<Thread, Thread> shutdownHooks = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile Optional<Instrumentation> instrumentation = Optional.empty();

  /** Creates a runtime using the system UTC clock and a no-op metrics sink. */
  public ChaosRuntime() {
    this(Clock.systemUTC(), ChaosMetricsSink.NOOP);
  }

  /**
   * Creates a runtime backed by the supplied clock and metrics sink.
   *
   * @param clock the clock used for scheduling and timestamping
   * @param metricsSink the metrics sink to receive observability events
   */
  public ChaosRuntime(final Clock clock, final ChaosMetricsSink metricsSink) {
    this.clock = clock;
    this.featureSet = new FeatureSet();
    this.scopeContext = new ScopeContext();
    this.observabilityBus = new ObservabilityBus(metricsSink);
    this.registry = new ScenarioRegistry(clock, this::runtimeDetails);
  }

  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    return registerScenario(scenario, "jvm", null);
  }

  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    final List<ChaosActivationHandle> handles = new ArrayList<>();
    for (final ChaosScenario scenario : plan.scenarios()) {
      if (scenario.scope() != ChaosScenario.ScenarioScope.JVM) {
        throw new ChaosActivationException(
            "startup/global activation cannot register session-scoped scenario " + scenario.id());
      }
      handles.add(activate(scenario));
    }
    return new CompositeActivationHandle("plan:" + plan.metadata().name(), handles);
  }

  @Override
  public ChaosSession openSession(final String displayName) {
    return new DefaultChaosSession(displayName, scopeContext, this);
  }

  @Override
  public ChaosDiagnostics diagnostics() {
    return registry;
  }

  @Override
  public void addEventListener(final ChaosEventListener listener) {
    Objects.requireNonNull(listener, "listener");
    observabilityBus.addListener(listener);
  }

  @Override
  public void close() {
    registry.controllers().forEach(ScenarioController::destroy);
  }

  /**
   * Returns the session id bound to the current thread, or {@code null} if none is active.
   *
   * @return the current thread-bound session id, or {@code null} if no session is active
   */
  public String currentSessionId() {
    return scopeContext.currentSessionId();
  }

  /**
   * Returns a (possibly session-scoped or suppressed) wrapper for an executor-submitted runnable.
   *
   * @param operation the {@link OperationType} name for the submission
   * @param executor the executor receiving the submission
   * @param task the runnable being submitted
   * @return the decorated runnable (session-scoped, no-op if suppressed, or the original task)
   */
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
    // Task 3: SUPPRESS means discard the task silently — return a no-op so the executor
    // continues operating normally while the submitted work is dropped.
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

  /**
   * Returns a (possibly session-scoped or suppressed) wrapper for an executor-submitted callable.
   *
   * @param <T> the callable's result type
   * @param operation the {@link OperationType} name for the submission
   * @param executor the executor receiving the submission
   * @param task the callable being submitted
   * @return the decorated callable (session-scoped, no-op if suppressed, or the original task)
   */
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
    // Task 3: SUPPRESS — drop the callable silently.
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

  /**
   * Called before {@link Thread#start()} to apply any active chaos scenario matching the thread.
   *
   * @param thread the thread being started
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Called at the top of a pool worker's run loop before the next task is dequeued.
   *
   * @param executor the executor owning the worker
   * @param worker the worker thread about to run the task
   * @param task the task being run, or {@code null} if not yet known
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Called before a {@link java.util.concurrent.ForkJoinTask} begins execution on a pool worker.
   *
   * @param task the fork-join task about to execute
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Allows an active scenario to alter the scheduling delay of a scheduled executor submission.
   *
   * @param operation the {@link OperationType} name for the schedule operation
   * @param executor the scheduling executor
   * @param task the task being scheduled
   * @param delay the originally requested delay
   * @param periodic {@code true} if the submission is periodic
   * @return the possibly-adjusted delay, or {@link Long#MAX_VALUE} to effectively suppress the
   *     schedule
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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
        return Long.MAX_VALUE;
      }
    }
    return delay + decision.delayMillis();
  }

  /**
   * Called before a void-returning blocking queue operation such as {@code put} or {@code take}.
   *
   * @param operation the {@link OperationType} name for the queue operation
   * @param queue the queue being operated on
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Called before a boolean-returning queue operation (e.g. {@code offer}) to optionally override
   * its result.
   *
   * @param operation the {@link OperationType} name for the queue operation
   * @param queue the queue being operated on
   * @return an override result to return from the queue operation, or {@code null} to proceed
   *     normally
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Called before a {@link CompletableFuture} completion method to optionally override its outcome.
   *
   * @param operation the {@link OperationType} name for the completion operation
   * @param future the future being completed
   * @param payload the completion payload (result or exception)
   * @return an override result to return from the completion method, or {@code null} to proceed
   *     normally
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Called before {@link ClassLoader#loadClass(String)} to apply scenarios targeting class loading.
   *
   * @param loader the class loader performing the load (may be {@code null} for bootstrap)
   * @param className the binary class name being loaded
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Called after {@link ClassLoader#getResource(String)} to optionally substitute the returned URL.
   *
   * @param loader the class loader performing the lookup (may be {@code null} for bootstrap)
   * @param name the resource name being looked up
   * @param currentValue the real URL returned by the loader
   * @return the (possibly substituted) URL to return to the caller
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Wraps a shutdown hook thread before it is registered with {@link
   * Runtime#addShutdownHook(Thread)}.
   *
   * @param hook the original shutdown hook being registered
   * @return the wrapper thread that should actually be registered with the runtime
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Resolves the registered wrapper thread for an original shutdown hook.
   *
   * @param original the original shutdown hook that was registered
   * @return the wrapper thread, or {@code original} if no wrapper is registered
   */
  public Thread resolveShutdownHook(final Thread original) {
    return shutdownHooks.getOrDefault(original, original);
  }

  /**
   * Called before an executor's {@code shutdown} or {@code shutdownNow} to apply matching
   * scenarios.
   *
   * @param operation the {@link OperationType} name for the shutdown operation
   * @param executor the executor being shut down
   * @param timeoutMillis the shutdown timeout in milliseconds, or {@code 0} when not specified
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Called before each execution of a scheduled task; returns {@code false} to suppress the tick.
   *
   * @param executor the scheduling executor running the tick
   * @param task the task being ticked
   * @param periodic {@code true} if the tick is for a periodic schedule
   * @return {@code true} if the tick should proceed normally, {@code false} to suppress it
   * @throws Throwable if an active scenario throws to simulate a failure
   */
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

  /**
   * Public hook for {@code OperationType.METHOD_ENTER}. Evaluates active {@link
   * ChaosEffect.ExceptionInjectionEffect} scenarios matching the given class and method name and
   * throws the injected exception if applicable.
   *
   * <p><b>Manual hook required.</b> The agent does not auto-rewrite arbitrary user methods. Call
   * this hook from the application's interception machinery (Spring AOP {@code @Around}, AspectJ,
   * Micronaut / Quarkus interceptors, or your own bytecode advice) at the start of every
   * intercepted method body.
   *
   * @param className the fully-qualified binary class name of the method's declaring class
   * @param methodName the simple method name
   * @throws Throwable the injected exception when a matching scenario fires
   */
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

  /**
   * Public hook for {@code OperationType.METHOD_EXIT}. Applies any active {@link
   * ChaosEffect.ReturnValueCorruptionEffect} to the supplied return value and returns the
   * possibly-corrupted result.
   *
   * <p><b>Manual hook required.</b> Call this from the same interception machinery as {@link
   * #beforeMethodEnter(String, String)} after the method body returns; pass the original return
   * value and hand the result returned by this hook back to the caller.
   *
   * @param className the fully-qualified binary class name of the method's declaring class
   * @param methodName the simple method name
   * @param returnType the declared return type; used to select an appropriate corrupted value
   * @param actualValue the original return value; primitives must be boxed
   * @return the (possibly-corrupted) return value
   * @throws Throwable if gate or other pre-decision actions require it
   */
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

  /**
   * Applies active {@link ChaosEffect.ClockSkewEffect} scenarios to a raw clock value read from
   * {@link System#currentTimeMillis()} or {@link System#nanoTime()}.
   *
   * <p>Called from ByteBuddy advice that intercepts {@link OperationType#SYSTEM_CLOCK_MILLIS} and
   * {@link OperationType#SYSTEM_CLOCK_NANOS}. Returns the real value unchanged if no active
   * clock-skew scenario matches.
   *
   * @param realValue the raw clock value as read from the OS
   * @param clockType {@link OperationType#SYSTEM_CLOCK_MILLIS} or {@link
   *     OperationType#SYSTEM_CLOCK_NANOS}
   * @return the skewed (or unchanged) clock value
   */
  public long applyClockSkew(final long realValue, final OperationType clockType) {
    final InvocationContext context =
        new InvocationContext(clockType, "java.lang.System", null, null, false, null, null, null);
    final List<ScenarioContribution> contributions = registry.match(context);
    long result = realValue;
    for (final ScenarioContribution contribution : contributions) {
      if (contribution.effect() instanceof ChaosEffect.ClockSkewEffect skewEffect) {
        final ClockSkewState state = contribution.controller().clockSkewState();
        if (state != null) {
          result =
              clockType == OperationType.SYSTEM_CLOCK_MILLIS
                  ? state.applyMillis(skewEffect.mode(), result)
                  : state.applyNanos(skewEffect.mode(), result);
        }
      }
    }
    return result;
  }

  /**
   * Provides the {@link Instrumentation} instance for stressors that require class retransformation
   * (e.g., {@link SafepointStormStressor}). Must be called from the agent bootstrap before any
   * scenario is activated. Safe to skip in test environments that install the agent via dynamic
   * attach.
   *
   * @param inst the instrumentation instance; must not be null
   */
  public void setInstrumentation(final Instrumentation inst) {
    this.instrumentation = Optional.of(Objects.requireNonNull(inst, "inst"));
  }

  /**
   * Public hook for {@code OperationType.SYSTEM_CLOCK_MILLIS}. Pass a raw {@link
   * System#currentTimeMillis()} reading; receive the chaos-skewed value to use downstream.
   *
   * <p><b>Manual hook required.</b> The agent cannot auto-instrument {@link
   * System#currentTimeMillis()} (it is {@code native @IntrinsicCandidate} and the JIT replaces the
   * call with a direct hardware-clock instruction). Wire this into a {@code TimeProvider} / {@code
   * Clock} wrapper that the application uses in place of {@code currentTimeMillis()} directly.
   *
   * @param realMillis the raw OS clock value, typically from {@link System#currentTimeMillis()}
   * @return chaos-skewed milliseconds when an active {@code SYSTEM_CLOCK_MILLIS} scenario applies;
   *     {@code realMillis} unchanged otherwise
   */
  public long adjustClockMillis(final long realMillis) {
    return applyClockSkew(realMillis, OperationType.SYSTEM_CLOCK_MILLIS);
  }

  /**
   * Public hook for {@code OperationType.SYSTEM_CLOCK_NANOS}. Pass a raw {@link System#nanoTime()}
   * reading; receive the chaos-skewed value. See {@link #adjustClockMillis(long)} for the broader
   * context — same JVM constraint applies.
   *
   * <p>A backward {@code DRIFT} skew on this hook intentionally violates the {@code nanoTime()}
   * monotonicity contract to expose timing-loop and profiling assumptions.
   *
   * @param realNanos the raw OS monotonic value, typically from {@link System#nanoTime()}
   * @return chaos-skewed nanoseconds when an active {@code SYSTEM_CLOCK_NANOS} scenario applies;
   *     {@code realNanos} unchanged otherwise
   */
  public long adjustClockNanos(final long realNanos) {
    return applyClockSkew(realNanos, OperationType.SYSTEM_CLOCK_NANOS);
  }

  /**
   * Called before {@code System.gc()} or {@code Runtime.gc()}.
   *
   * @return {@code true} if GC should be suppressed
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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
   * Called before {@code System.exit(status)} or {@code Runtime.halt(status)}.
   *
   * <p>A matching {@link ChaosEffect.SuppressEffect} throws {@link SecurityException} to prevent
   * the JVM from exiting, allowing tests to assert on state after code that calls {@code
   * System.exit()} in error paths.
   *
   * @param status the exit status code
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code Method.invoke(Object, Object[])}.
   *
   * @param method the {@link java.lang.reflect.Method} being invoked
   * @param target the receiver object (may be null for static methods)
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code ByteBuffer.allocateDirect(capacity)}.
   *
   * @param capacity the requested capacity in bytes
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code ObjectInputStream.readObject()}.
   *
   * @param stream the {@code ObjectInputStream} instance
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code ClassLoader.defineClass(...)}.
   *
   * @param loader the {@code ClassLoader} defining the class (may be null for bootstrap)
   * @param className the binary class name being defined
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before AQS {@code acquire} — proxy for monitor-contention injection.
   *
   * @throws Throwable if a matching scenario applies a delay or throws
   */
  public void beforeMonitorEnter() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.MONITOR_ENTER,
            "java.util.concurrent.locks.AbstractQueuedSynchronizer",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@code LockSupport.park*}.
   *
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code Selector.select()}. Returns {@code true} if a spurious wakeup should be
   * injected (the advice returns 0 immediately without blocking).
   *
   * @param selector the {@code Selector} instance
   * @param timeoutMillis the timeout passed to {@code select(long)}, or 0 for {@code selectNow()}
   * @return {@code true} if the select call should return 0 immediately
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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
   * Called before a NIO channel read/write/connect/accept.
   *
   * @param operation the {@link OperationType} name (NIO_CHANNEL_READ, etc.)
   * @param channel the channel instance
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code Socket.connect(SocketAddress, int)}.
   *
   * @param socket the {@code Socket} instance
   * @param socketAddress the target {@code SocketAddress}
   * @param timeoutMillis the connect timeout in milliseconds
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code ServerSocket.accept()}.
   *
   * @param serverSocket the {@code ServerSocket} instance
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before a socket read.
   *
   * @param stream the {@code SocketInputStream} instance
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before a socket write.
   *
   * @param stream the {@code SocketOutputStream} instance
   * @param len the number of bytes being written
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code Socket.close()}.
   *
   * @param socket the {@code Socket} instance
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code InitialContext.lookup(name)}.
   *
   * @param context the {@code Context} instance
   * @param name the JNDI lookup name
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code ObjectOutputStream.writeObject(obj)}.
   *
   * @param stream the {@code ObjectOutputStream} instance
   * @param obj the object being serialized
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code System.loadLibrary(name)} or {@code System.load(name)}.
   *
   * @param libraryName the library name or absolute path
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code CompletableFuture.cancel(mayInterruptIfRunning)}.
   *
   * @param future the {@code CompletableFuture} being cancelled
   * @param mayInterruptIfRunning the argument passed to {@code cancel}
   * @return {@code true} if the cancellation should be suppressed (advice returns true without
   *     actually cancelling), {@code false} for normal execution
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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
   * Called before {@code Inflater.inflate(...)}.
   *
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code Deflater.deflate(...)}.
   *
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code ThreadLocal.get()}.
   *
   * @param threadLocal the {@code ThreadLocal} instance
   * @return {@code true} if the get should return {@code null}
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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
   * Called before {@code ThreadLocal.set(value)}.
   *
   * @param threadLocal the {@code ThreadLocal} instance
   * @param value the value being set
   * @return {@code true} if the set should be suppressed
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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
   * Called before {@code MBeanServer.invoke(objectName, operationName, params, signature)}.
   *
   * @param server the {@code MBeanServer} instance
   * @param objectName the target MBean's {@code ObjectName}
   * @param operationName the operation being invoked
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /**
   * Called before {@code MBeanServer.getAttribute(objectName, attribute)}.
   *
   * @param server the {@code MBeanServer} instance
   * @param objectName the target MBean's {@code ObjectName}
   * @param attribute the attribute name
   * @throws Throwable if a matching scenario applies a delay or throws
   */
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

  /** Returns the {@link Instrumentation} instance if one was provided. */
  Optional<Instrumentation> instrumentation() {
    return instrumentation;
  }

  DefaultChaosActivationHandle activateInSession(
      final DefaultChaosSession session, final ChaosScenario scenario) {
    if (scenario.scope() != ChaosScenario.ScenarioScope.SESSION) {
      throw new ChaosActivationException(
          "session activation requires scenario scope SESSION for " + scenario.id());
    }
    return registerScenario(scenario, "session:" + session.id(), session.id());
  }

  ScenarioRegistry registry() {
    return registry;
  }

  private DefaultChaosActivationHandle registerScenario(
      final ChaosScenario scenario, final String scopeKey, final String sessionId) {
    // Task 6: Map exception types to the correct FailureCategory. Previously every
    // RuntimeException was recorded as INVALID_CONFIGURATION, masking unsupported-feature
    // and activation-conflict errors from operators.
    try {
      CompatibilityValidator.validate(scenario, featureSet);
      final ScenarioController controller =
          new ScenarioController(
              scenario, scopeKey, sessionId, clock, observabilityBus, () -> instrumentation);
      registry.register(controller);
      final DefaultChaosActivationHandle handle =
          new DefaultChaosActivationHandle(controller, registry);
      if (scenario.activationPolicy().startMode() == ActivationPolicy.StartMode.AUTOMATIC) {
        handle.start();
      }
      return handle;
    } catch (final ChaosUnsupportedFeatureException unsupported) {
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.UNSUPPORTED_RUNTIME,
          unsupported.getMessage());
      throw unsupported;
    } catch (final IllegalStateException stateException) {
      final ChaosDiagnostics.FailureCategory category =
          stateException.getMessage() != null
                  && stateException.getMessage().contains("already active")
              ? ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT
              : ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION;
      registry.recordFailure(scenario.id(), category, stateException.getMessage());
      throw stateException;
    } catch (final RuntimeException runtimeException) {
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          runtimeException.getMessage());
      throw runtimeException;
    }
  }

  private RuntimeDecision evaluate(final InvocationContext context) {
    final List<ScenarioContribution> contributions = registry.match(context);
    if (contributions.isEmpty()) {
      return RuntimeDecision.none();
    }
    long delayMillis = 0L;
    GateAction gateAction = null;
    TerminalAction terminalAction = null;
    int terminalPrecedence = Integer.MIN_VALUE;
    for (final ScenarioContribution contribution : contributions) {
      delayMillis += contribution.delayMillis();
      if (contribution.effect() instanceof ChaosEffect.GateEffect) {
        gateAction = new GateAction(contribution.controller().gate(), contribution.gateTimeout());
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
    // Task 11: ExceptionInjectionEffect — instantiate the exception via reflection.
    if (effect instanceof ChaosEffect.ExceptionInjectionEffect injectionEffect) {
      return buildInjectedExceptionTerminal(injectionEffect);
    }
    // Task 12: ReturnValueCorruptionEffect — carry the effect as the returnValue payload;
    // the actual value computation happens at afterMethodExit() time when the return type
    // and actual value are available.
    if (effect instanceof ChaosEffect.ReturnValueCorruptionEffect corruptEffect) {
      return new TerminalAction(TerminalKind.CORRUPT_RETURN, corruptEffect, null);
    }
    return null;
  }

  /**
   * Builds a terminal action that throws an exception of the type specified by {@code effect}.
   *
   * <p>Instantiation order:
   *
   * <ol>
   *   <li>Try {@code (String)} single-arg constructor with the effect message.
   *   <li>Fall back to the no-arg constructor.
   *   <li>If instantiation fails, return a {@link RuntimeException} describing the failure instead
   *       of silently swallowing it.
   * </ol>
   */
  private TerminalAction buildInjectedExceptionTerminal(
      final ChaosEffect.ExceptionInjectionEffect effect) {
    try {
      final Class<?> exClass = Class.forName(effect.exceptionClassName());
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
      return new TerminalAction(
          TerminalKind.THROW,
          null,
          new RuntimeException(
              "chaos-agent: failed to instantiate "
                  + effect.exceptionClassName()
                  + ": "
                  + reflective.getMessage()));
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

  /**
   * Produces a terminal action for {@link ChaosEffect.SuppressEffect}.
   *
   * <p>Task 3 semantics:
   *
   * <ul>
   *   <li>Thread start: throw {@link RejectedExecutionException} so the thread does not start.
   *   <li>Executor / fork-join task submission: {@link TerminalKind#SUPPRESS} so the caller can
   *       substitute a no-op task wrapper without throwing.
   *   <li>Queue offer / async-complete: return {@code false} to signal rejection via the return
   *       value contract.
   *   <li>Resource load: return {@code null} to simulate a missing resource.
   *   <li>Default (blocking queue puts, worker-run, etc.): {@link TerminalKind#SUPPRESS}.
   * </ul>
   */
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
        // For pre-decision contexts (thread start already handled via THROW above;
        // executor decoration handled before calling this method). Remaining SUPPRESS
        // cases (blocking queue put, worker-run etc.) are silently ignored.
        return;
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
      Thread.currentThread().interrupt();
      throw new IllegalStateException("chaos delay interrupted", interruptedException);
    }
  }

  private RuntimeException propagate(final Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
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

  private Map<String, String> runtimeDetails() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("jdkFeatureVersion", Integer.toString(featureSet.runtimeFeatureVersion()));
    details.put("virtualThreadsSupported", Boolean.toString(featureSet.supportsVirtualThreads()));
    details.put("jfrSupported", Boolean.toString(featureSet.jfrSupported()));
    details.put("currentSessionId", String.valueOf(scopeContext.currentSessionId()));
    return details;
  }
}

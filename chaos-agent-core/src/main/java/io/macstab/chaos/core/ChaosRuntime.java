package io.macstab.chaos.core;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosActivationException;
import io.macstab.chaos.api.ChaosActivationHandle;
import io.macstab.chaos.api.ChaosControlPlane;
import io.macstab.chaos.api.ChaosDiagnostics;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosEventListener;
import io.macstab.chaos.api.ChaosMetricsSink;
import io.macstab.chaos.api.ChaosPlan;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSession;
import io.macstab.chaos.api.ChaosUnsupportedFeatureException;
import io.macstab.chaos.api.OperationType;
import java.net.URL;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

public final class ChaosRuntime implements ChaosControlPlane {
  private static final Runnable NO_OP_RUNNABLE = () -> {};
  private static final Callable<?> NO_OP_CALLABLE = () -> null;

  private final Clock clock;
  private final FeatureSet featureSet;
  private final ScopeContext scopeContext;
  private final ObservabilityBus observabilityBus;
  private final ScenarioRegistry registry;
  private final Map<Thread, Thread> shutdownHooks = new java.util.concurrent.ConcurrentHashMap<>();

  public ChaosRuntime() {
    this(Clock.systemUTC(), ChaosMetricsSink.NOOP);
  }

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
        return Long.MAX_VALUE;
      }
    }
    return delay + decision.delayMillis();
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
    return shutdownHooks.getOrDefault(original, original);
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

  /**
   * Intercepts a method entry point. Evaluates active {@link
   * io.macstab.chaos.api.ChaosEffect.ExceptionInjectionEffect} scenarios matching the given class
   * and method name and throws the injected exception if applicable.
   *
   * <p>This method is the runtime target called by ByteBuddy {@code @Advice.OnMethodEnter}
   * instrumentation. It may also be called directly in tests that verify injection behaviour
   * without installing a full instrumentation stack.
   *
   * @param className the fully-qualified binary class name of the method's declaring class
   * @param methodName the simple method name
   * @throws Throwable the injected exception when a matching scenario fires; never a checked
   *     exception from this method itself
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
   * Intercepts a method exit point and applies any active {@link
   * io.macstab.chaos.api.ChaosEffect.ReturnValueCorruptionEffect} to the return value.
   *
   * <p>This method is the runtime target called by ByteBuddy {@code @Advice.OnMethodExit}
   * instrumentation. It may also be called directly in tests.
   *
   * @param className the fully-qualified binary class name of the method's declaring class
   * @param methodName the simple method name
   * @param returnType the declared return type; used to select an appropriate corrupted value
   * @param actualValue the original return value; must be boxed for primitives
   * @return the (possibly corrupted) return value
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
   * Applies active {@link io.macstab.chaos.api.ChaosEffect.ClockSkewEffect} scenarios to a raw
   * clock value read from {@link System#currentTimeMillis()} or {@link System#nanoTime()}.
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
          new ScenarioController(scenario, scopeKey, sessionId, clock, observabilityBus);
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
   * Produces a terminal action for {@link io.macstab.chaos.api.ChaosEffect.SuppressEffect}.
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

  private Map<String, String> runtimeDetails() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("jdkFeatureVersion", Integer.toString(featureSet.runtimeFeatureVersion()));
    details.put("virtualThreadsSupported", Boolean.toString(featureSet.supportsVirtualThreads()));
    details.put("jfrSupported", Boolean.toString(featureSet.jfrSupported()));
    details.put("currentSessionId", String.valueOf(scopeContext.currentSessionId()));
    return details;
  }
}

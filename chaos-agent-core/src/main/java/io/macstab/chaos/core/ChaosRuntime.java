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
  private final Clock clock;
  private final FeatureSet featureSet;
  private final ScopeContext scopeContext;
  private final ObservabilityBus observabilityBus;
  private final ScenarioRegistry registry;
  private final Map<Thread, Thread> shutdownHooks = new java.util.concurrent.ConcurrentHashMap<>();

  public ChaosRuntime() {
    this(Clock.systemUTC(), ChaosMetricsSink.NOOP);
  }

  public ChaosRuntime(Clock clock, ChaosMetricsSink metricsSink) {
    this.clock = clock;
    this.featureSet = new FeatureSet();
    this.scopeContext = new ScopeContext();
    this.observabilityBus = new ObservabilityBus(metricsSink);
    this.registry = new ScenarioRegistry(clock, this::runtimeDetails);
  }

  @Override
  public ChaosActivationHandle activate(ChaosScenario scenario) {
    return registerScenario(scenario, "jvm", null);
  }

  @Override
  public ChaosActivationHandle activate(ChaosPlan plan) {
    List<ChaosActivationHandle> handles = new ArrayList<>();
    for (ChaosScenario scenario : plan.scenarios()) {
      if (scenario.scope() != ChaosScenario.ScenarioScope.JVM) {
        throw new ChaosActivationException(
            "startup/global activation cannot register session-scoped scenario " + scenario.id());
      }
      handles.add(activate(scenario));
    }
    return new CompositeActivationHandle("plan:" + plan.metadata().name(), handles);
  }

  @Override
  public ChaosSession openSession(String displayName) {
    return new DefaultChaosSession(displayName, scopeContext, this);
  }

  @Override
  public ChaosDiagnostics diagnostics() {
    return registry;
  }

  @Override
  public void addEventListener(ChaosEventListener listener) {
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

  public Runnable decorateExecutorRunnable(String operation, Object executor, Runnable task) {
    Objects.requireNonNull(task, "task");
    String sessionId = scopeContext.currentSessionId();
    Runnable scoped = sessionId == null ? task : scopeContext.wrap(sessionId, task);
    InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            task.getClass().getName(),
            null,
            false,
            null,
            null,
            sessionId);
    RuntimeDecision decision = evaluate(context);
    try {
      applyPreDecision(decision);
    } catch (Throwable throwable) {
      throw propagate(throwable);
    }
    return scoped;
  }

  public <T> Callable<T> decorateExecutorCallable(
      String operation, Object executor, Callable<T> task) {
    Objects.requireNonNull(task, "task");
    String sessionId = scopeContext.currentSessionId();
    Callable<T> scoped = sessionId == null ? task : scopeContext.wrap(sessionId, task);
    InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            task.getClass().getName(),
            null,
            false,
            null,
            null,
            sessionId);
    RuntimeDecision decision = evaluate(context);
    try {
      applyPreDecision(decision);
    } catch (Throwable throwable) {
      throw propagate(throwable);
    }
    return scoped;
  }

  public void beforeThreadStart(Thread thread) throws Throwable {
    InvocationContext context =
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

  public void beforeWorkerRun(Object executor, Thread worker, Runnable task) throws Throwable {
    InvocationContext context =
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

  public void beforeForkJoinTaskRun(java.util.concurrent.ForkJoinTask<?> task) throws Throwable {
    InvocationContext context =
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
      String operation, Object executor, Object task, long delay, boolean periodic)
      throws Throwable {
    InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            task == null ? null : task.getClass().getName(),
            null,
            periodic,
            null,
            null,
            scopeContext.currentSessionId());
    RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.RETURN) {
        return Long.MAX_VALUE;
      }
    }
    return delay + decision.delayMillis();
  }

  public void beforeQueueOperation(String operation, Object queue) throws Throwable {
    InvocationContext context =
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

  public Boolean beforeBooleanQueueOperation(String operation, Object queue) throws Throwable {
    InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            queue == null ? "unknown" : queue.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      if (decision.terminalAction().kind() == TerminalKind.THROW) {
        throw decision.terminalAction().throwable();
      }
      if (decision.terminalAction().kind() == TerminalKind.RETURN) {
        return (Boolean) decision.terminalAction().returnValue();
      }
    }
    sleep(decision.delayMillis());
    return null;
  }

  public Boolean beforeCompletableFutureComplete(
      String operation, CompletableFuture<?> future, Object payload) throws Throwable {
    InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            CompletableFuture.class.getName(),
            payload == null ? null : payload.getClass().getName(),
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      TerminalAction terminalAction = decision.terminalAction();
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

  public void beforeClassLoad(ClassLoader loader, String className) throws Throwable {
    InvocationContext context =
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

  public URL afterResourceLookup(ClassLoader loader, String name, URL currentValue)
      throws Throwable {
    InvocationContext context =
        new InvocationContext(
            OperationType.RESOURCE_LOAD,
            loader == null ? "bootstrap" : loader.getClass().getName(),
            null,
            name,
            false,
            null,
            null,
            null);
    RuntimeDecision decision = evaluate(context);
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

  public Thread decorateShutdownHook(Thread hook) throws Throwable {
    InvocationContext context =
        new InvocationContext(
            OperationType.SHUTDOWN_HOOK_REGISTER,
            hook == null ? Thread.class.getName() : hook.getClass().getName(),
            null,
            hook == null ? null : hook.getName(),
            false,
            hook == null ? null : hook.isDaemon(),
            hook == null ? null : featureSet.isVirtualThread(hook),
            null);
    RuntimeDecision decision = evaluate(context);
    applyPreDecision(decision);
    Runnable delegate = hook::run;
    Thread decorated = new Thread(delegate, hook.getName() + "-macstab-chaos-wrapper");
    decorated.setDaemon(hook.isDaemon());
    shutdownHooks.put(hook, decorated);
    return decorated;
  }

  public Thread resolveShutdownHook(Thread original) {
    return shutdownHooks.getOrDefault(original, original);
  }

  public void beforeExecutorShutdown(String operation, Object executor, long timeoutMillis)
      throws Throwable {
    InvocationContext context =
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

  public boolean beforeScheduledTick(Object executor, Object task, boolean periodic)
      throws Throwable {
    InvocationContext context =
        new InvocationContext(
            OperationType.SCHEDULE_TICK,
            executor == null ? "unknown" : executor.getClass().getName(),
            task == null ? null : task.getClass().getName(),
            null,
            periodic,
            null,
            null,
            scopeContext.currentSessionId());
    RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      if (decision.terminalAction().kind() == TerminalKind.THROW) {
        throw decision.terminalAction().throwable();
      }
      if (decision.terminalAction().kind() == TerminalKind.RETURN) {
        return false;
      }
    }
    sleep(decision.delayMillis());
    return true;
  }

  DefaultChaosActivationHandle activateInSession(
      DefaultChaosSession session, ChaosScenario scenario) {
    if (scenario.scope() != ChaosScenario.ScenarioScope.SESSION) {
      throw new ChaosActivationException(
          "session activation requires scenario scope SESSION for " + scenario.id());
    }
    return registerScenario(scenario, "session:" + session.id(), session.id());
  }

  private DefaultChaosActivationHandle registerScenario(
      ChaosScenario scenario, String scopeKey, String sessionId) {
    try {
      CompatibilityValidator.validate(scenario, featureSet);
      ScenarioController controller =
          new ScenarioController(scenario, scopeKey, sessionId, clock, observabilityBus);
      registry.register(controller);
      DefaultChaosActivationHandle handle = new DefaultChaosActivationHandle(controller);
      if (scenario.activationPolicy().startMode() == ActivationPolicy.StartMode.AUTOMATIC) {
        handle.start();
      }
      return handle;
    } catch (RuntimeException runtimeException) {
      registry.recordFailure(
          scenario.id(),
          ChaosDiagnostics.FailureCategory.INVALID_CONFIGURATION,
          runtimeException.getMessage());
      throw runtimeException;
    }
  }

  private RuntimeDecision evaluate(InvocationContext context) {
    List<ScenarioContribution> contributions = registry.match(context);
    if (contributions.isEmpty()) {
      return RuntimeDecision.none();
    }
    long delayMillis = 0L;
    GateAction gateAction = null;
    TerminalAction terminalAction = null;
    int terminalPrecedence = Integer.MIN_VALUE;
    for (ScenarioContribution contribution : contributions) {
      delayMillis += contribution.delayMillis();
      if (contribution.effect() instanceof ChaosEffect.GateEffect) {
        gateAction = new GateAction(contribution.controller().gate(), contribution.gateTimeout());
      }
      TerminalAction candidate = terminalActionFor(context.operationType(), contribution.effect());
      if (candidate != null && contribution.scenario().precedence() >= terminalPrecedence) {
        terminalAction = candidate;
        terminalPrecedence = contribution.scenario().precedence();
      }
    }
    return new RuntimeDecision(delayMillis, gateAction, terminalAction);
  }

  private TerminalAction terminalActionFor(OperationType operationType, ChaosEffect effect) {
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
    return null;
  }

  private TerminalAction rejectTerminal(OperationType operationType, String message) {
    return switch (operationType) {
      case QUEUE_OFFER, ASYNC_COMPLETE, ASYNC_COMPLETE_EXCEPTIONALLY ->
          new TerminalAction(TerminalKind.RETURN, Boolean.FALSE, null);
      case RESOURCE_LOAD -> new TerminalAction(TerminalKind.RETURN, null, null);
      default ->
          new TerminalAction(
              TerminalKind.THROW, null, FailureFactory.reject(operationType, message));
    };
  }

  private TerminalAction suppressTerminal(OperationType operationType) {
    return switch (operationType) {
      case QUEUE_OFFER, ASYNC_COMPLETE, ASYNC_COMPLETE_EXCEPTIONALLY ->
          new TerminalAction(TerminalKind.RETURN, Boolean.FALSE, null);
      case RESOURCE_LOAD -> new TerminalAction(TerminalKind.RETURN, null, null);
      default -> new TerminalAction(TerminalKind.RETURN, null, null);
    };
  }

  private void applyPreDecision(RuntimeDecision decision) throws Throwable {
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.RETURN) {
        if (Boolean.FALSE.equals(terminalAction.returnValue())) {
          throw new RejectedExecutionException("operation suppressed by chaos agent");
        }
        return;
      }
    }
    sleep(decision.delayMillis());
  }

  private void applyGate(GateAction gateAction) throws InterruptedException {
    if (gateAction != null) {
      gateAction.gate().await(gateAction.maxBlock());
    }
  }

  private void sleep(long delayMillis) {
    if (delayMillis <= 0L) {
      return;
    }
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("chaos delay interrupted", interruptedException);
    }
  }

  private RuntimeException propagate(Throwable throwable) {
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

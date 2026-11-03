package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Thin facade that composes {@link ChaosControlPlaneImpl} and {@link ChaosDispatcher}.
 *
 * <p>Retained as a single entry point so that existing callers ({@code ChaosBridge}, bootstrap,
 * tests) do not need to be updated when the control-plane and dispatch halves are profiled or
 * exercised in isolation. Delegates control-plane methods to {@link ChaosControlPlaneImpl} and
 * hot-path methods to {@link ChaosDispatcher}.
 *
 * <p><strong>Not part of the stable API.</strong> This class is {@code public} only because it is
 * referenced across the chaos-agent internal multi-module split (bootstrap, instrumentation,
 * benchmarks). The frozen public surface is {@link ChaosControlPlane} in the api module; bind
 * against that interface instead. The {@code ChaosRuntime}-class surface may change without notice
 * in any release. The {@link Internal @Internal} marker makes that contract explicit to API
 * linters, IDE inspections, and {@code japicmp}-style bytecode tools.
 */
@Internal
public final class ChaosRuntime implements ChaosControlPlane {
  private final ChaosControlPlaneImpl controlPlane;
  private final ChaosDispatcher dispatcher;

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
    this.controlPlane = new ChaosControlPlaneImpl(clock, metricsSink);
    this.dispatcher = new ChaosDispatcher(controlPlane);
  }

  /**
   * Returns the hot-path {@link ChaosDispatcher} composed by this runtime.
   *
   * @return the dispatcher used for hot-path dispatch calls
   */
  public ChaosDispatcher dispatcher() {
    return dispatcher;
  }

  /**
   * Returns the control-plane implementation composed by this runtime.
   *
   * @return the composed control-plane implementation
   */
  public ChaosControlPlaneImpl controlPlane() {
    return controlPlane;
  }

  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    return controlPlane.activate(scenario);
  }

  @Override
  public ChaosSession.ScopeBinding bindSession(final String sessionId) {
    return controlPlane.bindSession(sessionId);
  }

  /**
   * Validates a scenario as {@link #activate(ChaosScenario)} would, without activating it.
   *
   * @param scenario the scenario
   * @throws com.macstab.chaos.jvm.api.ChaosActivationException if the scenario is invalid
   * @throws com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException if the runtime cannot run it
   */
  public void validate(final ChaosScenario scenario) {
    controlPlane.validate(scenario);
  }

  /**
   * Applies a plan's observability settings to scenarios activated one by one (the plan-file
   * reloader does this); {@link #activate(ChaosPlan)} applies them itself.
   *
   * @param scenarioIds the plan's scenario ids
   * @param observability the plan's settings
   * @param owner the plan the settings belong to; only it can clear them
   */
  public void applyPlanObservability(
      final java.util.Collection<String> scenarioIds,
      final ChaosPlan.Observability observability,
      final Object owner) {
    controlPlane.applyPlanObservability(scenarioIds, observability, owner);
  }

  /**
   * Restores the default observability of scenarios that left {@code owner}'s plan.
   *
   * @param scenarioIds the scenario ids
   * @param owner the owner that applied the settings
   */
  public void clearPlanObservability(
      final java.util.Collection<String> scenarioIds, final Object owner) {
    controlPlane.clearPlanObservability(scenarioIds, owner);
  }

  /**
   * Returns whether JMX may list and dump the scenario.
   *
   * @param scenarioId the scenario id
   * @return {@code false} when its plan set {@code jmxEnabled=false}
   */
  public boolean jmxVisible(final String scenarioId) {
    return controlPlane.jmxVisible(scenarioId);
  }

  /**
   * Returns the diagnostics dump restricted to scenarios {@code include} accepts.
   *
   * @param include whether a scenario id is part of the dump
   * @return the dump
   */
  public String debugDump(final java.util.function.Predicate<String> include) {
    return controlPlane.debugDump(include);
  }

  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    return controlPlane.activate(plan);
  }

  @Override
  public ChaosSession openSession(final String displayName) {
    return controlPlane.openSession(displayName, this);
  }

  @Override
  public ChaosDiagnostics diagnostics() {
    return controlPlane.diagnostics();
  }

  @Override
  public void addEventListener(final ChaosEventListener listener) {
    controlPlane.addEventListener(listener);
  }

  @Override
  public boolean removeEventListener(final ChaosEventListener listener) {
    return controlPlane.removeEventListener(listener);
  }

  @Override
  public void close() {
    controlPlane.close();
  }

  /**
   * Returns the session id bound to the current thread, or {@code null} if none is active.
   *
   * @return the current thread-bound session id, or {@code null} if no session is active
   */
  public String currentSessionId() {
    return dispatcher.currentSessionId();
  }

  /**
   * Delegates to {@link ChaosDispatcher#decorateExecutorRunnable}.
   *
   * @param operation the {@link OperationType} name for the submission
   * @param executor the executor receiving the submission
   * @param task the runnable being submitted
   * @return the decorated runnable produced by the dispatcher
   */
  public Runnable decorateExecutorRunnable(
      final String operation, final Object executor, final Runnable task) {
    return dispatcher.decorateExecutorRunnable(operation, executor, task);
  }

  /**
   * Delegates to {@link ChaosDispatcher#decorateExecutorCallable}.
   *
   * @param <T> the callable's result type
   * @param operation the {@link OperationType} name for the submission
   * @param executor the executor receiving the submission
   * @param task the callable being submitted
   * @return the decorated callable produced by the dispatcher
   */
  public <T> Callable<T> decorateExecutorCallable(
      final String operation, final Object executor, final Callable<T> task) {
    return dispatcher.decorateExecutorCallable(operation, executor, task);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeThreadStart}.
   *
   * @param thread the thread being started
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeThreadStart(final Thread thread) throws Throwable {
    dispatcher.beforeThreadStart(thread, false);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeThreadStart(Thread, boolean)}.
   *
   * @param thread the thread being started
   * @param poolWorker whether a thread pool is starting one of its workers
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeThreadStart(final Thread thread, final boolean poolWorker) throws Throwable {
    dispatcher.beforeThreadStart(thread, poolWorker);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeWorkerRun}.
   *
   * @param executor the executor owning the worker
   * @param worker the worker thread about to run the task
   * @param task the task being run, or {@code null} if not yet known
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeWorkerRun(final Object executor, final Thread worker, final Runnable task)
      throws Throwable {
    dispatcher.beforeWorkerRun(executor, worker, task);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeForkJoinTaskRun}.
   *
   * @param task the fork-join task about to execute
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeForkJoinTaskRun(final java.util.concurrent.ForkJoinTask<?> task)
      throws Throwable {
    dispatcher.beforeForkJoinTaskRun(task);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeQueueOperation}.
   *
   * @param operation the {@link OperationType} name for the queue operation
   * @param queue the queue being operated on
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeQueueOperation(final String operation, final Object queue) throws Throwable {
    dispatcher.beforeQueueOperation(operation, queue);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeBooleanQueueOperation}.
   *
   * @param operation the {@link OperationType} name for the queue operation
   * @param queue the queue being operated on
   * @return an override result from the queue operation, or {@code null} to proceed normally
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    return dispatcher.beforeBooleanQueueOperation(operation, queue);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeCompletableFutureComplete}.
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
    return dispatcher.beforeCompletableFutureComplete(operation, future, payload);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeClassLoad}.
   *
   * @param loader the class loader performing the load (may be {@code null} for bootstrap)
   * @param className the binary class name being loaded
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeClassLoad(final ClassLoader loader, final String className) throws Throwable {
    dispatcher.beforeClassLoad(loader, className);
  }

  /**
   * Delegates to {@link ChaosDispatcher#afterResourceLookup}.
   *
   * @param loader the class loader performing the lookup (may be {@code null} for bootstrap)
   * @param name the resource name being looked up
   * @param currentValue the real URL returned by the loader
   * @return the (possibly substituted) URL to return to the caller
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public URL afterResourceLookup(
      final ClassLoader loader, final String name, final URL currentValue) throws Throwable {
    return dispatcher.afterResourceLookup(loader, name, currentValue);
  }

  /**
   * Delegates to {@link ChaosDispatcher#decorateShutdownHook}.
   *
   * @param hook the original shutdown hook being registered
   * @return the wrapper thread that should actually be registered with the runtime
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public Thread decorateShutdownHook(final Thread hook) throws Throwable {
    return dispatcher.decorateShutdownHook(hook);
  }

  /**
   * Delegates to {@link ChaosDispatcher#resolveShutdownHook}.
   *
   * @param original the original shutdown hook that was registered
   * @return the wrapper thread, or {@code original} if no wrapper is registered
   */
  public Thread resolveShutdownHook(final Thread original) {
    return dispatcher.resolveShutdownHook(original);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeExecutorShutdown}.
   *
   * @param operation the {@link OperationType} name for the shutdown operation
   * @param executor the executor being shut down
   * @param timeoutMillis the shutdown timeout in milliseconds, or {@code 0} when not specified
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeExecutorShutdown(
      final String operation, final Object executor, final long timeoutMillis) throws Throwable {
    dispatcher.beforeExecutorShutdown(operation, executor, timeoutMillis);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeScheduledTick}.
   *
   * @param executor the scheduling executor running the tick
   * @param task the task being ticked
   * @param periodic {@code true} if the tick is for a periodic schedule
   * @return {@code true} if the tick should proceed normally, {@code false} to suppress it
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeScheduledTick(
      final Object executor, final Object task, final boolean periodic) throws Throwable {
    return dispatcher.beforeScheduledTick(executor, task, periodic);
  }

  /**
   * Public hook for {@link com.macstab.chaos.jvm.api.OperationType#METHOD_ENTER}. Call this from
   * the application's interception machinery (Spring AOP {@code @Around}, AspectJ, Micronaut /
   * Quarkus interceptors, or your own bytecode advice) at the start of every intercepted method to
   * give chaos scenarios a chance to inject an exception before the body runs.
   *
   * <p>The agent does <i>not</i> auto-rewrite arbitrary user methods, so the entry event must be
   * raised here. A matching {@code MethodSelector} scenario with {@link
   * com.macstab.chaos.jvm.api.ChaosEffect.ExceptionInjectionEffect} causes this method to throw the
   * configured exception type.
   *
   * <pre>{@code
   * @Around("@annotation(com.example.Audited)")
   * public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
   *   chaosRuntime.beforeMethodEnter(
   *       pjp.getTarget().getClass().getName(),
   *       pjp.getSignature().getName());
   *   return pjp.proceed();
   * }
   * }</pre>
   *
   * @param className the fully-qualified binary class name of the method's declaring class
   * @param methodName the simple method name (no descriptor)
   * @throws Throwable the exception type configured by an active {@code ExceptionInjectionEffect};
   *     no-op if no scenario matches
   */
  public void beforeMethodEnter(final String className, final String methodName) throws Throwable {
    dispatcher.beforeMethodEnter(className, methodName);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeMethodEnter(Class, String, String)}: the entry of a
   * method woven for a {@code MethodSelector}.
   *
   * @param declaringType the class declaring the method
   * @param methodName the method name
   * @param descriptor the method's JVM descriptor
   * @throws Throwable if a matching scenario injects an exception
   */
  public void beforeMethodEnter(
      final Class<?> declaringType, final String methodName, final String descriptor)
      throws Throwable {
    dispatcher.beforeMethodEnter(declaringType, methodName, descriptor);
  }

  /**
   * Delegates to {@link ChaosDispatcher#afterMethodExit(Class, String, String, Object)}: the normal
   * exit of a method woven for a {@code MethodSelector}.
   *
   * @param declaringType the class declaring the method
   * @param methodName the method name
   * @param descriptor the method's JVM descriptor
   * @param actualValue the returned value, boxed; {@code null} for a void method
   * @return the (possibly corrupted) value to return
   * @throws Throwable if a matching scenario fails the call
   */
  public Object afterMethodExit(
      final Class<?> declaringType,
      final String methodName,
      final String descriptor,
      final Object actualValue)
      throws Throwable {
    return dispatcher.afterMethodExit(declaringType, methodName, descriptor, actualValue);
  }

  /**
   * Public hook for {@link com.macstab.chaos.jvm.api.OperationType#METHOD_EXIT}. Call this from the
   * same interception machinery as {@link #beforeMethodEnter(String, String)} after the intercepted
   * method body returns; pass the original return value and receive the (possibly-corrupted) value
   * to hand back to the caller.
   *
   * <p>A matching {@code MethodSelector} scenario with {@link
   * com.macstab.chaos.jvm.api.ChaosEffect.ReturnValueCorruptionEffect} replaces {@code actualValue}
   * with a boundary, null, zero, or empty value selected for {@code returnType}.
   *
   * <pre>{@code
   * Object result = pjp.proceed();
   * return chaosRuntime.afterMethodExit(
   *     cls, mth,
   *     ((MethodSignature) pjp.getSignature()).getReturnType(),
   *     result);
   * }</pre>
   *
   * @param className the fully-qualified binary class name of the method's declaring class
   * @param methodName the simple method name (no descriptor)
   * @param returnType the declared return type; used to select an appropriate corrupted value
   *     ({@code null} for reference types, {@code 0} for numeric primitives, {@code false} for
   *     booleans, empty collections / arrays / strings)
   * @param actualValue the original return value; primitives must be boxed
   * @return the (possibly-corrupted) value to return to the caller; equals {@code actualValue} when
   *     no scenario matches
   * @throws Throwable the exception of an active {@code RejectEffect} scenario on {@code
   *     METHOD_EXIT}, thrown after the method body ran ({@code ExceptionInjectionEffect} is valid
   *     on {@code METHOD_ENTER} only)
   */
  public Object afterMethodExit(
      final String className,
      final String methodName,
      final Class<?> returnType,
      final Object actualValue)
      throws Throwable {
    return dispatcher.afterMethodExit(className, methodName, returnType, actualValue);
  }

  /**
   * Delegates to {@link ChaosDispatcher#applyClockSkew}.
   *
   * @param realValue the raw clock value as read from the OS
   * @param clockType {@link OperationType#SYSTEM_CLOCK_MILLIS} or {@link
   *     OperationType#SYSTEM_CLOCK_NANOS}
   * @return the skewed (or unchanged) clock value
   */
  public long applyClockSkew(final long realValue, final OperationType clockType) {
    return dispatcher.applyClockSkew(realValue, clockType);
  }

  /**
   * Installs the callback that weaves hooks on demand. Called by the agent bootstrap once the
   * instrumentation is installed; without it (plain unit tests) activation weaves nothing.
   *
   * @param demand the hook demand; never {@code null}
   */
  public void setHookDemand(final HookDemand demand) {
    controlPlane.setHookDemand(demand);
  }

  /**
   * Delegates to {@link ChaosControlPlaneImpl#setInstrumentation(Instrumentation)}.
   *
   * @param inst the Instrumentation instance to install
   */
  public void setInstrumentation(final Instrumentation inst) {
    controlPlane.setInstrumentation(inst);
  }

  /**
   * Public hook for {@link com.macstab.chaos.jvm.api.OperationType#SYSTEM_CLOCK_MILLIS}. Pass a raw
   * {@link System#currentTimeMillis()} reading and receive the chaos-skewed value to use
   * downstream.
   *
   * <p>The agent cannot auto-instrument {@link System#currentTimeMillis()} (it is {@code
   * native @IntrinsicCandidate} and the JIT replaces calls with a direct hardware-clock read).
   * Application code typically wires this hook into a {@code TimeProvider} / {@code Clock} wrapper:
   *
   * <pre>{@code
   * public long now() {
   *   return chaosRuntime.adjustClockMillis(System.currentTimeMillis());
   * }
   * }</pre>
   *
   * @param realMillis real wall-clock time in milliseconds, typically obtained from {@link
   *     System#currentTimeMillis()}
   * @return chaos-skewed milliseconds when an active {@code SYSTEM_CLOCK_MILLIS} scenario applies;
   *     {@code realMillis} unchanged otherwise
   */
  public long adjustClockMillis(final long realMillis) {
    return dispatcher.adjustClockMillis(realMillis);
  }

  /**
   * Public hook for {@link com.macstab.chaos.jvm.api.OperationType#SYSTEM_CLOCK_NANOS}. Pass a raw
   * {@link System#nanoTime()} reading and receive the chaos-skewed value. See {@link
   * #adjustClockMillis(long)} for the broader context — the same JVM constraint (native intrinsic)
   * applies to {@code nanoTime()}.
   *
   * <p>A backward {@code DRIFT} skew on this hook intentionally violates the monotonicity contract
   * of {@code nanoTime()} to expose timing-loop and profiling assumptions.
   *
   * @param realNanos real monotonic time in nanoseconds, typically obtained from {@link
   *     System#nanoTime()}
   * @return chaos-skewed nanoseconds when an active {@code SYSTEM_CLOCK_NANOS} scenario applies;
   *     {@code realNanos} unchanged otherwise
   */
  public long adjustClockNanos(final long realNanos) {
    return dispatcher.adjustClockNanos(realNanos);
  }

  /**
   * Delegates to {@link ChaosDispatcher#adjustInstantNow}.
   *
   * @param realInstant the real current instant
   * @return adjusted instant
   */
  public java.time.Instant adjustInstantNow(final java.time.Instant realInstant) {
    return dispatcher.adjustInstantNow(realInstant);
  }

  /**
   * Delegates to {@link ChaosDispatcher#adjustLocalDateTimeNowInZone}.
   *
   * @param realValue the real local date-time
   * @param zone the zone {@code LocalDateTime.now(ZoneId)} was called with
   * @return the (possibly skewed) local date-time
   */
  public java.time.LocalDateTime adjustLocalDateTimeNowInZone(
      final java.time.LocalDateTime realValue, final java.time.ZoneId zone) {
    return dispatcher.adjustLocalDateTimeNowInZone(realValue, zone);
  }

  /**
   * Delegates to {@link ChaosDispatcher#adjustLocalDateTimeNow}.
   *
   * @param realValue the real current local date-time
   * @return adjusted local date-time
   */
  public java.time.LocalDateTime adjustLocalDateTimeNow(final java.time.LocalDateTime realValue) {
    return dispatcher.adjustLocalDateTimeNow(realValue);
  }

  /**
   * Delegates to {@link ChaosDispatcher#adjustZonedDateTimeNow}.
   *
   * @param realValue the real current zoned date-time
   * @return adjusted zoned date-time
   */
  public java.time.ZonedDateTime adjustZonedDateTimeNow(final java.time.ZonedDateTime realValue) {
    return dispatcher.adjustZonedDateTimeNow(realValue);
  }

  /**
   * Delegates to {@link ChaosDispatcher#adjustDateNew}.
   *
   * @param realMillis real wall-clock time in milliseconds
   * @return adjusted time in milliseconds
   */
  public long adjustDateNew(final long realMillis) {
    return dispatcher.adjustDateNew(realMillis);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeGcRequest}.
   *
   * @return {@code true} if the GC request should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeGcRequest() throws Throwable {
    return dispatcher.beforeGcRequest();
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeExitRequest}.
   *
   * @param status the requested exit status code
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeExitRequest(final int status) throws Throwable {
    dispatcher.beforeExitRequest(status);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeReflectionInvoke}.
   *
   * @param method the reflective method being invoked
   * @param target the target object
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeReflectionInvoke(final Object method, final Object target) throws Throwable {
    dispatcher.beforeReflectionInvoke(method, target);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeDirectBufferAllocate}.
   *
   * @param capacity requested buffer capacity in bytes
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    dispatcher.beforeDirectBufferAllocate(capacity);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeObjectDeserialize}.
   *
   * @param stream the ObjectInputStream being read
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeObjectDeserialize(final Object stream) throws Throwable {
    dispatcher.beforeObjectDeserialize(stream);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeClassDefine}.
   *
   * @param loader the class loader defining the class
   * @param className the binary name of the class being defined
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeClassDefine(final Object loader, final String className) throws Throwable {
    dispatcher.beforeClassDefine(loader, className);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeMonitorEnter}.
   *
   * @param lock the monitor object
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeMonitorEnter(final Object lock) throws Throwable {
    dispatcher.beforeMonitorEnter(lock);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeThreadPark}.
   *
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeThreadPark() throws Throwable {
    dispatcher.beforeThreadPark();
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeNioSelect}.
   *
   * @param selector the NIO Selector
   * @param timeoutMillis the select timeout
   * @return {@code true} if the select should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeNioSelect(final Object selector, final long timeoutMillis) throws Throwable {
    return dispatcher.beforeNioSelect(selector, timeoutMillis);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeNioChannelOp}.
   *
   * @param operation the channel operation name
   * @param channel the NIO channel
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeNioChannelOp(final String operation, final Object channel) throws Throwable {
    dispatcher.beforeNioChannelOp(operation, channel);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketConnect}.
   *
   * @param socket the socket
   * @param socketAddress the target address
   * @param timeoutMillis connect timeout
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    dispatcher.beforeSocketConnect(socket, socketAddress, timeoutMillis);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketAccept}.
   *
   * @param serverSocket the server socket
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeSocketAccept(final Object serverSocket) throws Throwable {
    dispatcher.beforeSocketAccept(serverSocket);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketRead}.
   *
   * @param stream the socket input stream
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeSocketRead(final Object stream) throws Throwable {
    dispatcher.beforeSocketRead(stream, InvocationContext.UNKNOWN, null, 0, 0);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketRead(Object, int, Object, int, int)}.
   *
   * @param stream the socket implementation being read from
   * @param len the number of bytes requested
   * @param remoteAddress the peer {@link java.net.InetAddress}, or {@code null} if not connected
   * @param remotePort the peer port
   * @param soTimeoutMillis the socket's {@code SO_TIMEOUT}; {@code 0} for infinite
   * @return the byte count to read, encoded when the read must be corrupted
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public int beforeSocketRead(
      final Object stream,
      final int len,
      final Object remoteAddress,
      final int remotePort,
      final int soTimeoutMillis)
      throws Throwable {
    return dispatcher.beforeSocketRead(stream, len, remoteAddress, remotePort, soTimeoutMillis);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketWrite}.
   *
   * @param stream the socket output stream
   * @param len number of bytes to write
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeSocketWrite(final Object stream, final int len) throws Throwable {
    dispatcher.beforeSocketWrite(stream, len, null, 0);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketWrite(Object, int, Object, int)}.
   *
   * @param stream the socket implementation being written to
   * @param len the number of bytes about to be written
   * @param remoteAddress the peer {@link java.net.InetAddress}, or {@code null} if not connected
   * @param remotePort the peer port
   * @return the bytes to write: {@code len}, or a shorter prefix under a torn write
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public int beforeSocketWrite(
      final Object stream, final int len, final Object remoteAddress, final int remotePort)
      throws Throwable {
    return dispatcher.beforeSocketWrite(stream, len, remoteAddress, remotePort);
  }

  /**
   * Delegates to {@link ChaosDispatcher#corruptionSeed()}.
   *
   * @return the seed of the bit flip the current thread's read was told to apply
   */
  public long corruptionSeed() {
    return dispatcher.corruptionSeed();
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeProcessStart(String)}.
   *
   * @param command the program
   * @throws Throwable if an active scenario fails the start
   */
  public void beforeProcessStart(final String command) throws Throwable {
    dispatcher.beforeProcessStart(command);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeNioChannelWrite(Object, int)}.
   *
   * @param channel the channel being written to
   * @param remaining the bytes the buffer holds
   * @return the bytes to write: {@code remaining}, or fewer under a torn write
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public int beforeNioChannelWrite(final Object channel, final int remaining) throws Throwable {
    return dispatcher.beforeNioChannelWrite(channel, remaining);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketClose}.
   *
   * @param socket the socket being closed
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeSocketClose(final Object socket) throws Throwable {
    dispatcher.beforeSocketClose(socket);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketChannelRead(Object, int)}.
   *
   * @param channel the channel being read from
   * @param remaining the bytes the buffers can take
   * @return the byte count to read
   * @throws Throwable if an active scenario injects a failure
   */
  public int beforeSocketChannelRead(final Object channel, final int remaining) throws Throwable {
    return dispatcher.beforeSocketChannelRead(channel, remaining);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSocketChannelWrite(Object, int)}.
   *
   * @param channel the channel being written to
   * @param remaining the bytes the buffers hold
   * @return the bytes to write
   * @throws Throwable if an active scenario injects a failure
   */
  public int beforeSocketChannelWrite(final Object channel, final int remaining) throws Throwable {
    return dispatcher.beforeSocketChannelWrite(channel, remaining);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeJndiLookup}.
   *
   * @param context the JNDI context
   * @param name the name to look up
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeJndiLookup(final Object context, final String name) throws Throwable {
    dispatcher.beforeJndiLookup(context, name);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeObjectSerialize}.
   *
   * @param stream the ObjectOutputStream
   * @param obj the object being serialized
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeObjectSerialize(final Object stream, final Object obj) throws Throwable {
    dispatcher.beforeObjectSerialize(stream, obj);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeNativeLibraryLoad}.
   *
   * @param libraryName the native library name
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    dispatcher.beforeNativeLibraryLoad(libraryName);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeAsyncCancel}.
   *
   * @param future the Future being cancelled
   * @param mayInterruptIfRunning whether to interrupt if running
   * @return {@code true} if cancellation should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeAsyncCancel(final Object future, final boolean mayInterruptIfRunning)
      throws Throwable {
    return dispatcher.beforeAsyncCancel(future, mayInterruptIfRunning);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeZipInflate}.
   *
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeZipInflate() throws Throwable {
    dispatcher.beforeZipInflate();
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeZipDeflate}.
   *
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeZipDeflate() throws Throwable {
    dispatcher.beforeZipDeflate();
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeThreadLocalGet}.
   *
   * @param threadLocal the ThreadLocal being accessed
   * @return {@code true} if the get should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeThreadLocalGet(final Object threadLocal) throws Throwable {
    return dispatcher.beforeThreadLocalGet(threadLocal);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeThreadLocalSet}.
   *
   * @param threadLocal the ThreadLocal being set
   * @param value the value being stored
   * @return {@code true} if the set should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeThreadLocalSet(final Object threadLocal, final Object value)
      throws Throwable {
    return dispatcher.beforeThreadLocalSet(threadLocal, value);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeJmxInvoke}.
   *
   * @param server the MBeanServer
   * @param objectName the target MBean ObjectName
   * @param operationName the JMX operation name
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    dispatcher.beforeJmxInvoke(server, objectName, operationName);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeJmxGetAttr}.
   *
   * @param server the MBeanServer
   * @param objectName the target MBean ObjectName
   * @param attribute the attribute name
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeJmxGetAttr(final Object server, final Object objectName, final String attribute)
      throws Throwable {
    dispatcher.beforeJmxGetAttr(server, objectName, attribute);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeHttpSend}.
   *
   * @param url the request URL
   * @param opType the HTTP operation type
   * @return {@code true} if the request should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeHttpSend(final String url, final OperationType opType) throws Throwable {
    return dispatcher.beforeHttpSend(url, opType);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeJdbcConnectionAcquire}.
   *
   * @param poolName the connection pool name
   * @return {@code true} if connection acquisition should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    return dispatcher.beforeJdbcConnectionAcquire(poolName);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeJdbcStatementExecute}.
   *
   * @param sql the SQL statement
   * @return {@code true} if execution should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeJdbcStatementExecute(final String sql) throws Throwable {
    return dispatcher.beforeJdbcStatementExecute(sql);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeJdbcPreparedStatement}.
   *
   * @param sql the SQL statement being prepared
   * @return {@code true} if preparation should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeJdbcPreparedStatement(final String sql) throws Throwable {
    return dispatcher.beforeJdbcPreparedStatement(sql);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeJdbcTransactionCommit}.
   *
   * @return {@code true} if the commit should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeJdbcTransactionCommit() throws Throwable {
    return dispatcher.beforeJdbcTransactionCommit();
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeJdbcTransactionRollback}.
   *
   * @return {@code true} if the rollback should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeJdbcTransactionRollback() throws Throwable {
    return dispatcher.beforeJdbcTransactionRollback();
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeThreadSleep}.
   *
   * @param millis the requested sleep duration in milliseconds
   * @return {@code true} if the sleep should proceed
   * @throws Throwable if a chaos effect injects an exception
   */
  public boolean beforeThreadSleep(final long millis) throws Throwable {
    return dispatcher.beforeThreadSleep(millis);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeDnsResolve}.
   *
   * @param hostname the hostname being resolved
   * @return the name to resolve: {@code hostname}, or another under a {@code DnsRewriteEffect}
   * @throws Throwable if a chaos effect injects an exception
   */
  public String beforeDnsResolve(final String hostname) throws Throwable {
    return dispatcher.beforeDnsResolve(hostname);
  }

  /**
   * Delegates to {@link ChaosDispatcher#afterDnsAnswer}.
   *
   * @param hostname the name that was looked up
   * @param answer the resolver's answer, or {@code null}
   * @param failure the lookup failure, or {@code null}
   * @return the reshaped answer, or {@code null} to keep the original outcome
   * @throws Throwable if a scenario rejects the lookup
   */
  public java.net.InetAddress[] afterDnsAnswer(
      final String hostname, final java.net.InetAddress[] answer, final Throwable failure)
      throws Throwable {
    return dispatcher.afterDnsAnswer(hostname, answer, failure);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeSslHandshake}.
   *
   * @param socket the SSL socket
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeSslHandshake(final Object socket) throws Throwable {
    dispatcher.beforeSslHandshake(socket);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeClientCall}: a call through a client library.
   *
   * @param operation the {@link com.macstab.chaos.jvm.api.OperationType} name of the call
   * @param client the client object
   * @param target the topic(s), method, command or AWS operation, or {@code null}
   * @param detail the AWS service, or {@code null}
   * @param remoteAddress the peer address of a Netty channel, or {@code null}
   * @throws Throwable if a scenario rejects the call
   */
  public void beforeClientCall(
      final String operation,
      final Object client,
      final String target,
      final String detail,
      final Object remoteAddress)
      throws Throwable {
    dispatcher.beforeClientCall(operation, client, target, detail, remoteAddress);
  }

  /**
   * Delegates to {@link ChaosDispatcher#captureSession}.
   *
   * @param carrier the fork-join task being submitted or forked
   */
  public void captureSession(final Object carrier) {
    dispatcher.captureSession(carrier);
  }

  /**
   * Delegates to {@link ChaosDispatcher#enterSession}.
   *
   * @param carrier the task or thread starting to run
   */
  public void enterSession(final Object carrier) {
    dispatcher.enterSession(carrier);
  }

  /**
   * Delegates to {@link ChaosDispatcher#exitSession}.
   *
   * @param carrier the task or thread that finished
   */
  public void exitSession(final Object carrier) {
    dispatcher.exitSession(carrier);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeFileIo}.
   *
   * @param operation the file I/O operation name
   * @param stream the file stream
   * @throws Throwable if a chaos effect injects an exception
   */
  public void beforeFileIo(final String operation, final Object stream) throws Throwable {
    dispatcher.beforeFileIo(operation, stream, null, InvocationContext.UNKNOWN);
  }

  /**
   * Delegates to {@link ChaosDispatcher#beforeFileIo(String, Object, String, int)}.
   *
   * @param operation the file I/O operation name
   * @param stream the file stream
   * @param path the path the stream was opened with, or {@code null}
   * @param byteCount the bytes read (requested) or written by the call
   * @return {@code byteCount}, encoded when a read's bytes must be corrupted
   * @throws Throwable if a chaos effect injects an exception
   */
  public int beforeFileIo(
      final String operation, final Object stream, final String path, final int byteCount)
      throws Throwable {
    return dispatcher.beforeFileIo(operation, stream, path, byteCount);
  }

  Optional<Instrumentation> instrumentation() {
    return controlPlane.instrumentation();
  }

  DefaultChaosActivationHandle activateInSession(
      final DefaultChaosSession session, final ChaosScenario scenario) {
    return controlPlane.activateInSession(session, scenario);
  }

  ScenarioRegistry registry() {
    return controlPlane.registry();
  }
}

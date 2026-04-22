package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.OperationType;

/**
 * Immutable snapshot of a single intercepted JVM operation, passed to the scenario evaluation
 * pipeline.
 *
 * <p>An {@code InvocationContext} is constructed by {@code ChaosRuntime} at each instrumentation
 * point and forwarded to {@link ScenarioRegistry#match(InvocationContext)}, which passes it to
 * every registered {@link ScenarioController#evaluate(InvocationContext)}. The record is never
 * modified after construction.
 *
 * @param operationType the kind of JVM operation being intercepted (e.g. {@link
 *     OperationType#THREAD_START}, {@link OperationType#SOCKET_CONNECT}); never {@code null}
 * @param targetClassName the binary name of the class that owns the intercepted method (e.g. {@code
 *     "java.net.Socket"}); never {@code null}
 * @param subjectClassName the binary name of the class of the concrete object at the interception
 *     point (e.g. the runtime class of the executor or socket), or {@code null} when not applicable
 *     (e.g. for static-method interceptions)
 * @param targetName the simple name identifying the specific resource within the target class —
 *     typically the method name, a library name, or an operation tag supplied by the advice (e.g.
 *     {@code "connect"}, {@code "libssl"}); never {@code null}
 * @param periodic {@code true} if the intercepted invocation is a recurring scheduled task; {@code
 *     false} for one-shot or non-scheduled operations
 * @param daemonThread {@code Boolean.TRUE} if the calling thread is a daemon thread, {@code
 *     Boolean.FALSE} if it is a non-daemon thread, or {@code null} when the thread classification
 *     is not known or not relevant
 * @param virtualThread {@code Boolean.TRUE} if the calling thread is a virtual (Project Loom)
 *     thread, {@code Boolean.FALSE} if it is a platform thread, or {@code null} when the JVM does
 *     not support virtual threads or the classification is unavailable
 * @param sessionId the chaos-session identifier bound to the current thread via {@link
 *     ScopeContext}, or {@code null} if no session is active on this thread
 */
record InvocationContext(
    OperationType operationType,
    String targetClassName,
    String subjectClassName,
    String targetName,
    boolean periodic,
    Boolean daemonThread,
    Boolean virtualThread,
    String sessionId) {}

package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetAddress;
import org.jspecify.annotations.Nullable;

/**
 * Immutable snapshot of a single intercepted JVM operation, passed to the scenario evaluation
 * pipeline.
 *
 * <p>An {@code InvocationContext} is built by the domain dispatcher of the intercepted call ({@code
 * NetworkDispatch}, {@code FileDispatch}, …) and forwarded to {@link
 * ScenarioRegistry#match(InvocationContext)}, which passes it to every registered {@link
 * ScenarioController#evaluate(InvocationContext)}. The record is never modified after construction.
 *
 * @param operationType the kind of JVM operation being intercepted (e.g. {@link
 *     OperationType#THREAD_START}, {@link OperationType#SOCKET_CONNECT}); never {@code null}
 * @param targetClassName the binary name of the class that owns the intercepted method (e.g. {@code
 *     "java.net.Socket"}), or {@code null} when the advice supplies none
 * @param subjectClassName the binary name of the class of the concrete object at the interception
 *     point (e.g. the runtime class of the executor or socket), or {@code null} when not applicable
 *     (e.g. for static-method interceptions)
 * @param targetName the simple name identifying the specific resource within the target class —
 *     typically the method name, a library name, or an operation tag supplied by the advice (e.g.
 *     {@code "connect"}, {@code "libssl"}), or {@code null} when the advice supplies none
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
 * @param byteCount the number of bytes of a read or write (requested for a read), or {@code -1}
 *     when the operation carries none
 * @param path the file path of a file operation, or {@code null}
 * @param remoteAddress the peer address of a socket operation, or {@code null} when unknown or
 *     unresolved
 * @param remotePort the peer port of a socket operation, or {@code -1} when unknown
 * @param signature the JVM descriptor of a woven method (e.g. {@code (Ljava/lang/String;)V}), or
 *     {@code null} when the call carries none
 * @param timeoutMillis the call's own timeout ({@code SO_TIMEOUT} of a read, the connect timeout),
 *     {@code 0} for infinite, or {@code -1} when the call has none
 */
record InvocationContext(
    OperationType operationType,
    @Nullable String targetClassName,
    @Nullable String subjectClassName,
    @Nullable String targetName,
    boolean periodic,
    @Nullable Boolean daemonThread,
    @Nullable Boolean virtualThread,
    @Nullable String sessionId,
    long byteCount,
    @Nullable String path,
    @Nullable InetAddress remoteAddress,
    int remotePort,
    @Nullable String signature,
    int timeoutMillis) {

  /** {@code byteCount}, {@code remotePort} or {@code timeoutMillis} of a call that has none. */
  static final int UNKNOWN = -1;

  /**
   * Creates a context without byte count, path or peer, for operations that carry none.
   *
   * @param operationType the intercepted operation
   * @param targetClassName the class owning the intercepted method
   * @param subjectClassName the class of the concrete object, or {@code null}
   * @param targetName the resource name, or {@code null}
   * @param periodic whether the invocation is a recurring scheduled task
   * @param daemonThread whether the calling thread is a daemon, or {@code null}
   * @param virtualThread whether the calling thread is virtual, or {@code null}
   * @param sessionId the bound session, or {@code null}
   */
  InvocationContext(
      final OperationType operationType,
      final @Nullable String targetClassName,
      final @Nullable String subjectClassName,
      final @Nullable String targetName,
      final boolean periodic,
      final @Nullable Boolean daemonThread,
      final @Nullable Boolean virtualThread,
      final @Nullable String sessionId) {
    this(
        operationType,
        targetClassName,
        subjectClassName,
        targetName,
        periodic,
        daemonThread,
        virtualThread,
        sessionId,
        UNKNOWN,
        null,
        null,
        UNKNOWN,
        null,
        UNKNOWN);
  }
}

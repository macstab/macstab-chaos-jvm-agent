package com.macstab.chaos.jvm.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.EnumSet;
import java.util.Set;

/**
 * Defines the targeting predicate for a chaos scenario.
 *
 * <p>A selector determines which JVM operations are eligible for chaos application. The {@link
 * ChaosEffect} determines what happens when a selector matches.
 *
 * <p>Selectors divide into two categories:
 *
 * <ul>
 *   <li><b>Interception selectors</b> — match in-flight JVM operations by type and context. Used
 *       with interceptor effects (Delay, Reject, Gate, ExceptionInjection, etc.).
 *   <li>{@link StressSelector} — activates standalone stressor effects that run independently of
 *       any specific operation. Used with stressor effects (HeapPressure, Deadlock, etc.).
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ChaosSelector.ThreadSelector.class, name = "thread"),
  @JsonSubTypes.Type(value = ChaosSelector.ExecutorSelector.class, name = "executor"),
  @JsonSubTypes.Type(value = ChaosSelector.QueueSelector.class, name = "queue"),
  @JsonSubTypes.Type(value = ChaosSelector.AsyncSelector.class, name = "async"),
  @JsonSubTypes.Type(value = ChaosSelector.SchedulingSelector.class, name = "scheduling"),
  @JsonSubTypes.Type(value = ChaosSelector.ShutdownSelector.class, name = "shutdown"),
  @JsonSubTypes.Type(value = ChaosSelector.ClassLoadingSelector.class, name = "classLoading"),
  @JsonSubTypes.Type(value = ChaosSelector.MethodSelector.class, name = "method"),
  @JsonSubTypes.Type(value = ChaosSelector.MonitorSelector.class, name = "monitor"),
  @JsonSubTypes.Type(value = ChaosSelector.JvmRuntimeSelector.class, name = "jvmRuntime"),
  @JsonSubTypes.Type(value = ChaosSelector.NioSelector.class, name = "nio"),
  @JsonSubTypes.Type(value = ChaosSelector.NetworkSelector.class, name = "network"),
  @JsonSubTypes.Type(value = ChaosSelector.ThreadLocalSelector.class, name = "threadLocal"),
  @JsonSubTypes.Type(value = ChaosSelector.HttpClientSelector.class, name = "httpClient"),
  @JsonSubTypes.Type(value = ChaosSelector.JdbcSelector.class, name = "jdbc"),
  @JsonSubTypes.Type(value = ChaosSelector.DnsSelector.class, name = "dns"),
  @JsonSubTypes.Type(value = ChaosSelector.SslSelector.class, name = "ssl"),
  @JsonSubTypes.Type(value = ChaosSelector.FileIoSelector.class, name = "fileIo"),
  @JsonSubTypes.Type(value = ChaosSelector.StressSelector.class, name = "stress"),
})
public sealed interface ChaosSelector
    permits ChaosSelector.ThreadSelector,
        ChaosSelector.ExecutorSelector,
        ChaosSelector.QueueSelector,
        ChaosSelector.AsyncSelector,
        ChaosSelector.SchedulingSelector,
        ChaosSelector.ShutdownSelector,
        ChaosSelector.ClassLoadingSelector,
        ChaosSelector.MethodSelector,
        ChaosSelector.MonitorSelector,
        ChaosSelector.JvmRuntimeSelector,
        ChaosSelector.NioSelector,
        ChaosSelector.NetworkSelector,
        ChaosSelector.ThreadLocalSelector,
        ChaosSelector.HttpClientSelector,
        ChaosSelector.JdbcSelector,
        ChaosSelector.DnsSelector,
        ChaosSelector.SslSelector,
        ChaosSelector.FileIoSelector,
        ChaosSelector.StressSelector {

  // ── Factory methods ────────────────────────────────────────────────────────

  /**
   * Returns a selector that matches thread start operations of the given {@link ThreadKind}.
   *
   * <p>Valid operations: {@link OperationType#THREAD_START}, {@link
   * OperationType#VIRTUAL_THREAD_START}.
   *
   * @param operations set of thread operations to intercept; must not be empty
   * @param kind {@link ThreadKind#ANY} matches all threads; use {@link ThreadKind#VIRTUAL} to
   *     target only virtual threads (requires JDK 21+ at runtime)
   * @return a ThreadSelector
   */
  static ThreadSelector thread(final Set<OperationType> operations, final ThreadKind kind) {
    return new ThreadSelector(operations, kind, NamePattern.any(), null);
  }

  /**
   * Returns a selector that matches all executor submit and worker-run operations across any
   * executor class and task class.
   *
   * <p>Valid operations: {@link OperationType#EXECUTOR_SUBMIT}, {@link
   * OperationType#EXECUTOR_WORKER_RUN}, {@link OperationType#EXECUTOR_SHUTDOWN}, {@link
   * OperationType#EXECUTOR_AWAIT_TERMINATION}.
   *
   * @param operations set of executor operations to intercept; must not be empty
   * @return an ExecutorSelector
   */
  static ExecutorSelector executor(final Set<OperationType> operations) {
    return new ExecutorSelector(operations, NamePattern.any(), NamePattern.any(), null);
  }

  /**
   * Returns a selector that matches blocking queue operations across any queue implementation.
   *
   * <p>Valid operations: {@link OperationType#QUEUE_PUT}, {@link OperationType#QUEUE_OFFER}, {@link
   * OperationType#QUEUE_TAKE}, {@link OperationType#QUEUE_POLL}.
   *
   * @param operations set of queue operations to intercept; must not be empty
   * @return a QueueSelector
   */
  static QueueSelector queue(final Set<OperationType> operations) {
    return new QueueSelector(operations, NamePattern.any());
  }

  /**
   * Returns a selector that matches {@link java.util.concurrent.CompletableFuture} completion
   * operations.
   *
   * <p>Valid operations: {@link OperationType#ASYNC_COMPLETE}, {@link
   * OperationType#ASYNC_COMPLETE_EXCEPTIONALLY}.
   *
   * @param operations set of async operations to intercept; must not be empty
   * @return an AsyncSelector
   */
  static AsyncSelector async(final Set<OperationType> operations) {
    return new AsyncSelector(operations);
  }

  /**
   * Returns a selector that matches scheduled task submission and tick operations across any {@link
   * java.util.concurrent.ScheduledExecutorService}.
   *
   * <p>Valid operations: {@link OperationType#SCHEDULE_SUBMIT}, {@link
   * OperationType#SCHEDULE_TICK}.
   *
   * @param operations set of scheduling operations to intercept; must not be empty
   * @return a SchedulingSelector
   */
  static SchedulingSelector scheduling(final Set<OperationType> operations) {
    return new SchedulingSelector(operations, NamePattern.any(), null);
  }

  /**
   * Returns a selector that matches JVM shutdown operations.
   *
   * <p>Valid operations: {@link OperationType#SHUTDOWN_HOOK_REGISTER}, {@link
   * OperationType#EXECUTOR_SHUTDOWN}, {@link OperationType#EXECUTOR_AWAIT_TERMINATION}.
   *
   * @param operations set of shutdown operations to intercept; must not be empty
   * @return a ShutdownSelector
   */
  static ShutdownSelector shutdown(final Set<OperationType> operations) {
    return new ShutdownSelector(operations, NamePattern.any());
  }

  /**
   * Returns a selector that matches class and resource loading operations where the target name
   * satisfies {@code targetPattern}.
   *
   * <p>Valid operations: {@link OperationType#CLASS_LOAD}, {@link OperationType#CLASS_DEFINE},
   * {@link OperationType#RESOURCE_LOAD}.
   *
   * @param operations set of class-loading operations to intercept; must not be empty
   * @param targetPattern pattern matched against the class or resource name being loaded
   * @return a ClassLoadingSelector
   */
  static ClassLoadingSelector classLoading(
      final Set<OperationType> operations, final NamePattern targetPattern) {
    return new ClassLoadingSelector(operations, targetPattern, NamePattern.any());
  }

  /**
   * Returns a selector that targets entry to or exit from methods whose declaring class and name
   * match {@code classPattern} and {@code methodNamePattern} respectively.
   *
   * <p>This is the most powerful selector in the API. Combined with {@link
   * ChaosEffect#injectException} and {@link OperationType#METHOD_ENTER} it can inject faults into
   * any method in any library. Combined with {@link ChaosEffect#corruptReturnValue} and {@link
   * OperationType#METHOD_EXIT} it corrupts return values on exit.
   *
   * <p><b>Safety constraint:</b> at least one of {@code classPattern} or {@code methodNamePattern}
   * must be non-{@link NamePattern.MatchMode#ANY ANY} to prevent accidental JVM-wide
   * instrumentation.
   *
   * <p>Valid operations: {@link OperationType#METHOD_ENTER}, {@link OperationType#METHOD_EXIT}.
   *
   * @param operations must contain only {@link OperationType#METHOD_ENTER} and/or {@link
   *     OperationType#METHOD_EXIT}; must not be empty
   * @param classPattern pattern matched against the fully-qualified class name (binary form, dots);
   *     must not both be ANY together with {@code methodNamePattern}
   * @param methodNamePattern pattern matched against the method name
   * @return a MethodSelector
   */
  static MethodSelector method(
      final Set<OperationType> operations,
      final NamePattern classPattern,
      final NamePattern methodNamePattern) {
    return new MethodSelector(operations, classPattern, methodNamePattern, null);
  }

  /**
   * Returns a selector that matches monitor entry and thread parking operations across any class.
   *
   * <p>Valid operations: {@link OperationType#MONITOR_ENTER}, {@link OperationType#THREAD_PARK}.
   *
   * @param operations set of monitor/parking operations to intercept; must not be empty
   * @return a MonitorSelector
   */
  static MonitorSelector monitor(final Set<OperationType> operations) {
    return new MonitorSelector(operations, NamePattern.any());
  }

  /**
   * Returns a selector that matches JVM runtime service calls: clock reads, GC requests, process
   * exit, reflection invocations, direct buffer allocations, object serialization/deserialization,
   * native library loading, JNDI lookups, JMX operations, and ZIP compression.
   *
   * <p>Valid operations: {@link OperationType#SYSTEM_CLOCK_MILLIS}, {@link
   * OperationType#SYSTEM_CLOCK_NANOS}, {@link OperationType#INSTANT_NOW}, {@link
   * OperationType#LOCAL_DATE_TIME_NOW}, {@link OperationType#ZONED_DATE_TIME_NOW}, {@link
   * OperationType#DATE_NEW}, {@link OperationType#SYSTEM_GC_REQUEST}, {@link
   * OperationType#SYSTEM_EXIT_REQUEST}, {@link OperationType#REFLECTION_INVOKE}, {@link
   * OperationType#DIRECT_BUFFER_ALLOCATE}, {@link OperationType#OBJECT_DESERIALIZE}, {@link
   * OperationType#OBJECT_SERIALIZE}, {@link OperationType#NATIVE_LIBRARY_LOAD}, {@link
   * OperationType#JNDI_LOOKUP}, {@link OperationType#JMX_INVOKE}, {@link
   * OperationType#JMX_GET_ATTR}, {@link OperationType#ZIP_INFLATE}, {@link
   * OperationType#ZIP_DEFLATE}.
   *
   * @param operations set of JVM runtime operations to intercept; must not be empty
   * @return a JvmRuntimeSelector
   */
  static JvmRuntimeSelector jvmRuntime(final Set<OperationType> operations) {
    return new JvmRuntimeSelector(operations);
  }

  /**
   * Returns a selector that matches NIO channel and selector operations.
   *
   * <p>Valid operations: {@link OperationType#NIO_SELECTOR_SELECT}, {@link
   * OperationType#NIO_CHANNEL_READ}, {@link OperationType#NIO_CHANNEL_WRITE}, {@link
   * OperationType#NIO_CHANNEL_CONNECT}, {@link OperationType#NIO_CHANNEL_ACCEPT}.
   *
   * @param operations set of NIO operations to intercept; must not be empty
   * @return a NioSelector
   */
  static NioSelector nio(final Set<OperationType> operations) {
    return new NioSelector(operations, NamePattern.any());
  }

  /**
   * Returns a selector that matches NIO channel and selector operations, optionally filtering by
   * channel implementation class.
   *
   * @param operations set of NIO operations to intercept; must not be empty
   * @param channelClassPattern pattern matched against the channel implementation class name
   * @return a NioSelector
   */
  static NioSelector nio(
      final Set<OperationType> operations, final NamePattern channelClassPattern) {
    return new NioSelector(operations, channelClassPattern);
  }

  /**
   * Returns a selector that matches socket/network operations across any host.
   *
   * <p>Valid operations: {@link OperationType#SOCKET_CONNECT}, {@link OperationType#SOCKET_ACCEPT},
   * {@link OperationType#SOCKET_READ}, {@link OperationType#SOCKET_WRITE}, {@link
   * OperationType#SOCKET_CLOSE}.
   *
   * @param operations set of socket operations to intercept; must not be empty
   * @return a NetworkSelector
   */
  static NetworkSelector network(final Set<OperationType> operations) {
    return new NetworkSelector(operations, NamePattern.any());
  }

  /**
   * Returns a selector that matches socket/network operations filtered by remote host name.
   *
   * @param operations set of socket operations to intercept; must not be empty
   * @param remoteHostPattern pattern matched against the remote host name or IP string
   * @return a NetworkSelector
   */
  static NetworkSelector network(
      final Set<OperationType> operations, final NamePattern remoteHostPattern) {
    return new NetworkSelector(operations, remoteHostPattern);
  }

  /**
   * Returns a selector that matches {@link ThreadLocal} get and set operations across any
   * ThreadLocal class.
   *
   * <p>Valid operations: {@link OperationType#THREAD_LOCAL_GET}, {@link
   * OperationType#THREAD_LOCAL_SET}.
   *
   * @param operations set of ThreadLocal operations to intercept; must not be empty
   * @return a ThreadLocalSelector
   */
  static ThreadLocalSelector threadLocal(final Set<OperationType> operations) {
    return new ThreadLocalSelector(operations, NamePattern.any());
  }

  /**
   * Returns a selector that matches {@link ThreadLocal} get and set operations, optionally
   * filtering by the ThreadLocal subclass name.
   *
   * @param operations set of ThreadLocal operations to intercept; must not be empty
   * @param threadLocalClassPattern pattern matched against the ThreadLocal class name
   * @return a ThreadLocalSelector
   */
  static ThreadLocalSelector threadLocal(
      final Set<OperationType> operations, final NamePattern threadLocalClassPattern) {
    return new ThreadLocalSelector(operations, threadLocalClassPattern);
  }

  /**
   * Returns a selector that matches HTTP client send operations across all supported clients (Java
   * {@code HttpClient}, OkHttp, Apache HttpComponents 4/5, Spring WebClient) without URL filtering.
   *
   * <p>Valid operations: {@link OperationType#HTTP_CLIENT_SEND}, {@link
   * OperationType#HTTP_CLIENT_SEND_ASYNC}.
   *
   * @param operations set of HTTP client operations to intercept; must not be empty
   * @return an HttpClientSelector
   */
  static HttpClientSelector httpClient(final Set<OperationType> operations) {
    return new HttpClientSelector(operations, NamePattern.any());
  }

  /**
   * Returns a selector that matches HTTP client send operations whose request URL (in {@code
   * scheme://host/path} form) matches {@code urlPattern}.
   *
   * @param operations set of HTTP client operations to intercept; must not be empty
   * @param urlPattern pattern matched against the request URL
   * @return an HttpClientSelector
   */
  static HttpClientSelector httpClient(
      final Set<OperationType> operations, final NamePattern urlPattern) {
    return new HttpClientSelector(operations, urlPattern);
  }

  /**
   * Returns a selector that matches all JDBC operations across HikariCP, c3p0, and standard {@link
   * java.sql.Statement} / {@link java.sql.Connection} implementations without operation filtering.
   *
   * <p>Valid operations: {@link OperationType#JDBC_CONNECTION_ACQUIRE}, {@link
   * OperationType#JDBC_STATEMENT_EXECUTE}, {@link OperationType#JDBC_PREPARED_STATEMENT}, {@link
   * OperationType#JDBC_TRANSACTION_COMMIT}, {@link OperationType#JDBC_TRANSACTION_ROLLBACK}.
   * @return a JdbcSelector
   */
  static JdbcSelector jdbc() {
    return new JdbcSelector(
        EnumSet.of(
            OperationType.JDBC_CONNECTION_ACQUIRE,
            OperationType.JDBC_STATEMENT_EXECUTE,
            OperationType.JDBC_PREPARED_STATEMENT,
            OperationType.JDBC_TRANSACTION_COMMIT,
            OperationType.JDBC_TRANSACTION_ROLLBACK),
        NamePattern.any());
  }

  /**
   * Returns a selector that matches the given JDBC operations across all supported pool and JDBC
   * implementations without target-name filtering.
   *
   * @param operations the JDBC operations to intercept; must not be empty
   * @return a JdbcSelector
   */
  static JdbcSelector jdbc(final OperationType... operations) {
    if (operations == null || operations.length == 0) {
      throw new IllegalArgumentException("operations must not be empty");
    }
    final EnumSet<OperationType> set = EnumSet.noneOf(OperationType.class);
    for (final OperationType op : operations) {
      set.add(op);
    }
    return new JdbcSelector(set, NamePattern.any());
  }

  /**
   * Returns a selector that matches DNS resolution calls across all hostname patterns.
   *
   * <p>Valid operations: {@link OperationType#DNS_RESOLVE}.
   *
   * @param operations set of DNS operations to intercept; must not be empty
   * @return a DnsSelector
   */
  static DnsSelector dns(final Set<OperationType> operations) {
    return new DnsSelector(operations, NamePattern.any());
  }

  /**
   * Returns a selector that matches DNS resolution calls whose resolved hostname matches {@code
   * hostnamePattern}.
   *
   * <p>Valid operations: {@link OperationType#DNS_RESOLVE}.
   *
   * @param operations set of DNS operations to intercept; must not be empty
   * @param hostnamePattern pattern matched against the hostname being resolved; {@code null}
   *     matches {@code InetAddress.getLocalHost()} (which has no hostname argument)
   * @return a DnsSelector
   */
  static DnsSelector dns(final Set<OperationType> operations, final NamePattern hostnamePattern) {
    return new DnsSelector(operations, hostnamePattern);
  }

  /**
   * Returns a selector that matches SSL/TLS handshake operations.
   *
   * <p>Valid operations: {@link OperationType#SSL_HANDSHAKE}.
   *
   * @param operations set of SSL operations to intercept; must not be empty
   * @return a SslSelector
   */
  static SslSelector ssl(final Set<OperationType> operations) {
    return new SslSelector(operations);
  }

  /**
   * Returns a selector that matches file I/O read and write operations across any {@link
   * java.io.FileInputStream} or {@link java.io.FileOutputStream} instance.
   *
   * <p>Valid operations: {@link OperationType#FILE_IO_READ}, {@link OperationType#FILE_IO_WRITE}.
   *
   * @param operations set of file I/O operations to intercept; must not be empty
   * @return a FileIoSelector
   */
  static FileIoSelector fileIo(final Set<OperationType> operations) {
    return new FileIoSelector(operations);
  }

  /**
   * Returns a selector that activates the stressor effect identified by {@code target}. Unlike
   * interception selectors, this does not match in-flight JVM operations — it triggers the stressor
   * immediately on activation and runs it until the handle is closed.
   *
   * <p>The {@code target} value must correspond exactly to the {@link ChaosEffect} type in the same
   * {@link ChaosScenario}. The runtime validator enforces this binding at activation time.
   *
   * @param target the stressor to activate; must not be null
   * @return a StressSelector
   */
  static StressSelector stress(final StressTarget target) {
    return new StressSelector(target);
  }

  // ── Supporting enumerations ────────────────────────────────────────────────

  /** Classifies the thread type targeted by a {@link ThreadSelector}. */
  enum ThreadKind {
    ANY,
    PLATFORM,
    VIRTUAL,
  }

  /**
   * Identifies which stressor to activate when using {@link StressSelector}. Each value corresponds
   * to exactly one {@link ChaosEffect} implementation. The runtime validator enforces the target ↔
   * effect binding at activation time.
   */
  enum StressTarget {
    // Memory stressors
    /** {@link ChaosEffect.HeapPressureEffect} */
    HEAP,
    /** {@link ChaosEffect.MetaspacePressureEffect} */
    METASPACE,
    /** {@link ChaosEffect.DirectBufferPressureEffect} */
    DIRECT_BUFFER,

    // GC stressors
    /** {@link ChaosEffect.GcPressureEffect} */
    GC_PRESSURE,
    /** {@link ChaosEffect.FinalizerBacklogEffect} */
    FINALIZER_BACKLOG,

    // Threading stressors
    /** {@link ChaosEffect.KeepAliveEffect} */
    KEEPALIVE,
    /** {@link ChaosEffect.ThreadLeakEffect} */
    THREAD_LEAK,
    /** {@link ChaosEffect.ThreadLocalLeakEffect} */
    THREAD_LOCAL_LEAK,
    /** {@link ChaosEffect.DeadlockEffect} */
    DEADLOCK,
    /** {@link ChaosEffect.MonitorContentionEffect} */
    MONITOR_CONTENTION,
    /** {@link ChaosEffect.VirtualThreadCarrierPinningEffect} */
    VIRTUAL_THREAD_CARRIER_PINNING,

    // JIT / code cache stressors
    /** {@link ChaosEffect.CodeCachePressureEffect} */
    CODE_CACHE_PRESSURE,

    // Safepoint stressors
    /** {@link ChaosEffect.SafepointStormEffect} */
    SAFEPOINT_STORM,

    // String interning stressors
    /** {@link ChaosEffect.StringInternPressureEffect} */
    STRING_INTERN_PRESSURE,

    // Reference queue stressors
    /** {@link ChaosEffect.ReferenceQueueFloodEffect} */
    REFERENCE_QUEUE_FLOOD,
  }

  // ── Selector record types ──────────────────────────────────────────────────

  /**
   * Matches thread start operations filtered by thread kind and name.
   *
   * @param operations operation types this selector matches
   * @param kind thread kind filter; {@code null} defaults to {@link ThreadKind#ANY}
   * @param threadNamePattern pattern matched against the thread name
   * @param daemon match only daemon ({@code true}) or non-daemon ({@code false}); {@code null}
   *     matches both
   */
  record ThreadSelector(
      Set<OperationType> operations, ThreadKind kind, NamePattern threadNamePattern, Boolean daemon)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.THREAD_START,
            OperationType.VIRTUAL_THREAD_START,
            OperationType.THREAD_SLEEP);

    public ThreadSelector {
      operations = validatedOperations(operations, VALID_OPS, "ThreadSelector");
      kind = kind == null ? ThreadKind.ANY : kind;
      threadNamePattern = threadNamePattern == null ? NamePattern.any() : threadNamePattern;
    }
  }

  /**
   * Matches executor submit and worker-run operations, optionally filtering by executor class and
   * submitted task class.
   *
   * @param operations operation types this selector matches
   * @param executorClassPattern pattern matched against the executor's runtime class name
   * @param taskClassPattern pattern matched against the submitted task's class name
   * @param scheduledOnly when {@code true}, match only scheduled executor invocations; {@code null}
   *     matches both
   */
  record ExecutorSelector(
      Set<OperationType> operations,
      NamePattern executorClassPattern,
      NamePattern taskClassPattern,
      Boolean scheduledOnly)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.EXECUTOR_SUBMIT,
            OperationType.EXECUTOR_WORKER_RUN,
            OperationType.EXECUTOR_SHUTDOWN,
            OperationType.EXECUTOR_AWAIT_TERMINATION,
            OperationType.FORK_JOIN_TASK_RUN);

    /** Validates the ExecutorSelector parameters. */
    public ExecutorSelector {
      operations = validatedOperations(operations, VALID_OPS, "ExecutorSelector");
      executorClassPattern =
          executorClassPattern == null ? NamePattern.any() : executorClassPattern;
      taskClassPattern = taskClassPattern == null ? NamePattern.any() : taskClassPattern;
    }
  }

  /**
   * Matches blocking queue operations, optionally filtering by queue implementation class.
   *
   * @param operations operation types this selector matches
   * @param queueClassPattern pattern matched against the queue's runtime class name
   */
  record QueueSelector(Set<OperationType> operations, NamePattern queueClassPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.QUEUE_PUT,
            OperationType.QUEUE_OFFER,
            OperationType.QUEUE_TAKE,
            OperationType.QUEUE_POLL);

    /** Validates the QueueSelector parameters. */
    public QueueSelector {
      operations = validatedOperations(operations, VALID_OPS, "QueueSelector");
      queueClassPattern = queueClassPattern == null ? NamePattern.any() : queueClassPattern;
    }
  }

  /**
   * Matches CompletableFuture completion operations.
   *
   * @param operations operation types this selector matches
   */
  record AsyncSelector(Set<OperationType> operations) implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.ASYNC_COMPLETE,
            OperationType.ASYNC_COMPLETE_EXCEPTIONALLY,
            OperationType.ASYNC_CANCEL);

    /** Validates the AsyncSelector parameters. */
    public AsyncSelector {
      operations = validatedOperations(operations, VALID_OPS, "AsyncSelector");
    }
  }

  /**
   * Matches scheduled task submission and tick operations.
   *
   * @param operations operation types this selector matches
   * @param executorClassPattern pattern matched against the executor's runtime class name
   * @param periodicOnly when {@code true}, match only periodic schedules; {@code null} matches both
   */
  record SchedulingSelector(
      Set<OperationType> operations, NamePattern executorClassPattern, Boolean periodicOnly)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(OperationType.SCHEDULE_SUBMIT, OperationType.SCHEDULE_TICK);

    /** Validates the SchedulingSelector parameters. */
    public SchedulingSelector {
      operations = validatedOperations(operations, VALID_OPS, "SchedulingSelector");
      executorClassPattern =
          executorClassPattern == null ? NamePattern.any() : executorClassPattern;
    }
  }

  /**
   * Matches JVM shutdown hook registration and executor shutdown operations.
   *
   * @param operations operation types this selector matches
   * @param targetClassPattern pattern matched against the shutdown target's class name
   */
  record ShutdownSelector(Set<OperationType> operations, NamePattern targetClassPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.SHUTDOWN_HOOK_REGISTER,
            OperationType.EXECUTOR_SHUTDOWN,
            OperationType.EXECUTOR_AWAIT_TERMINATION);

    /** Validates the ShutdownSelector parameters. */
    public ShutdownSelector {
      operations = validatedOperations(operations, VALID_OPS, "ShutdownSelector");
      targetClassPattern = targetClassPattern == null ? NamePattern.any() : targetClassPattern;
    }
  }

  /**
   * Matches class and resource loading operations.
   *
   * @param operations operation types this selector matches
   * @param targetNamePattern pattern matched against the class or resource name being loaded
   * @param loaderClassPattern pattern matched against the classloader's runtime class name
   */
  record ClassLoadingSelector(
      Set<OperationType> operations, NamePattern targetNamePattern, NamePattern loaderClassPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.CLASS_LOAD, OperationType.CLASS_DEFINE, OperationType.RESOURCE_LOAD);

    /** Validates the ClassLoadingSelector parameters. */
    public ClassLoadingSelector {
      operations = validatedOperations(operations, VALID_OPS, "ClassLoadingSelector");
      targetNamePattern = targetNamePattern == null ? NamePattern.any() : targetNamePattern;
      loaderClassPattern = loaderClassPattern == null ? NamePattern.any() : loaderClassPattern;
    }
  }

  /**
   * Matches entry to or exit from any method matching the class and method name patterns.
   *
   * <p>This is the most powerful selector in the API. Combined with {@link
   * ChaosEffect.ExceptionInjectionEffect} and {@link OperationType#METHOD_ENTER} it can inject
   * faults into any method in any library without prior preparation. Combined with {@link
   * ChaosEffect.ReturnValueCorruptionEffect} and {@link OperationType#METHOD_EXIT} it corrupts
   * return values on exit.
   *
   * <p><b>Safety constraint:</b> at least one of classPattern or methodNamePattern must be non-ANY.
   * A fully wildcard selector would instrument every method in the JVM.
   *
   * <p>signaturePattern is reserved: the runtime does not currently propagate the JVM method
   * descriptor through to the matcher, so a non-null value here would silently never match any
   * invocation. To prevent scenarios from being silently inert, the canonical constructor rejects
   * any non-null {@code signaturePattern} other than {@link NamePattern#any()}. Support for
   * descriptor-based filtering is tracked as a follow-up feature — construct with {@code null} or
   * {@link NamePattern#any()} until it ships.
   *
   * <p><b>Valid operations:</b> {@link OperationType#METHOD_ENTER}, {@link
   * OperationType#METHOD_EXIT}.
   *
   * @param operations operation types this selector matches
   * @param classPattern pattern matched against the declaring class name
   * @param methodNamePattern pattern matched against the method name
   * @param signaturePattern reserved; must be {@code null} or {@link NamePattern#any()} until
   *     descriptor-based filtering ships
   */
  record MethodSelector(
      Set<OperationType> operations,
      NamePattern classPattern,
      NamePattern methodNamePattern,
      NamePattern signaturePattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(OperationType.METHOD_ENTER, OperationType.METHOD_EXIT);

    /** Validates the MethodSelector parameters. */
    public MethodSelector {
      operations = validatedOperations(operations, VALID_OPS, "MethodSelector");
      classPattern = classPattern == null ? NamePattern.any() : classPattern;
      methodNamePattern = methodNamePattern == null ? NamePattern.any() : methodNamePattern;
      if (classPattern.mode() == NamePattern.MatchMode.ANY
          && methodNamePattern.mode() == NamePattern.MatchMode.ANY) {
        throw new IllegalArgumentException(
            "MethodSelector requires at least one non-ANY pattern to prevent "
                + "accidental global method instrumentation");
      }
      if (signaturePattern != null && signaturePattern.mode() != NamePattern.MatchMode.ANY) {
        // A non-ANY signature pattern would silently never match: the InvocationContext fed to
        // SelectorMatcher does not carry the JVM method descriptor for METHOD_ENTER/METHOD_EXIT
        // advice, so the matcher would dereference a null subjectClassName and fail to match
        // every call. Fail loud at plan-build time rather than ship a zero-activation scenario.
        throw new IllegalArgumentException(
            "MethodSelector.signaturePattern is not yet implemented in the runtime; use "
                + "NamePattern.any() or null. Filter by classPattern/methodNamePattern for now.");
      }
    }
  }

  /**
   * Matches monitor entry and thread parking operations, optionally filtering by the class of the
   * object whose monitor is being entered.
   *
   * <p><b>Valid operations:</b> {@link OperationType#MONITOR_ENTER}, {@link
   * OperationType#THREAD_PARK}.
   *
   * @param operations operation types this selector matches
   * @param monitorClassPattern pattern matched against the class of the monitor object
   */
  record MonitorSelector(Set<OperationType> operations, NamePattern monitorClassPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(OperationType.MONITOR_ENTER, OperationType.THREAD_PARK);

    /** Validates the MonitorSelector parameters. */
    public MonitorSelector {
      operations = validatedOperations(operations, VALID_OPS, "MonitorSelector");
      monitorClassPattern = monitorClassPattern == null ? NamePattern.any() : monitorClassPattern;
    }
  }

  /**
   * Matches JVM runtime service calls: clock reads, GC requests, process exit, reflection
   * invocations, direct buffer allocations, and object deserialization.
   *
   * <p>Primary use cases:
   *
   * <ul>
   *   <li>Clock skew — intercept {@link OperationType#SYSTEM_CLOCK_MILLIS} / {@link
   *       OperationType#SYSTEM_CLOCK_NANOS} / {@link OperationType#INSTANT_NOW} / {@link
   *       OperationType#LOCAL_DATE_TIME_NOW} / {@link OperationType#ZONED_DATE_TIME_NOW} / {@link
   *       OperationType#DATE_NEW} with {@link ChaosEffect.ClockSkewEffect}
   *   <li>GC chaos — intercept {@link OperationType#SYSTEM_GC_REQUEST}
   *   <li>Exit interception — intercept {@link OperationType#SYSTEM_EXIT_REQUEST}
   *   <li>Reflection latency — intercept {@link OperationType#REFLECTION_INVOKE}
   *   <li>Deserialization fault — intercept {@link OperationType#OBJECT_DESERIALIZE}
   * </ul>
   *
   * @param operations operation types this selector matches
   */
  record JvmRuntimeSelector(Set<OperationType> operations) implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.SYSTEM_CLOCK_MILLIS,
            OperationType.SYSTEM_CLOCK_NANOS,
            OperationType.INSTANT_NOW,
            OperationType.LOCAL_DATE_TIME_NOW,
            OperationType.ZONED_DATE_TIME_NOW,
            OperationType.DATE_NEW,
            OperationType.SYSTEM_GC_REQUEST,
            OperationType.SYSTEM_EXIT_REQUEST,
            OperationType.REFLECTION_INVOKE,
            OperationType.DIRECT_BUFFER_ALLOCATE,
            OperationType.OBJECT_DESERIALIZE,
            OperationType.OBJECT_SERIALIZE,
            OperationType.NATIVE_LIBRARY_LOAD,
            OperationType.JNDI_LOOKUP,
            OperationType.JMX_INVOKE,
            OperationType.JMX_GET_ATTR,
            OperationType.ZIP_INFLATE,
            OperationType.ZIP_DEFLATE);

    /** Validates the JvmRuntimeSelector parameters. */
    public JvmRuntimeSelector {
      operations = validatedOperations(operations, VALID_OPS, "JvmRuntimeSelector");
    }
  }

  /**
   * Matches NIO channel and selector operations, optionally filtering by channel class name.
   *
   * <p><b>Valid operations:</b> {@link OperationType#NIO_SELECTOR_SELECT}, {@link
   * OperationType#NIO_CHANNEL_READ}, {@link OperationType#NIO_CHANNEL_WRITE}, {@link
   * OperationType#NIO_CHANNEL_CONNECT}, {@link OperationType#NIO_CHANNEL_ACCEPT}.
   *
   * @param operations operation types this selector matches
   * @param channelClassPattern pattern matched against the NIO channel's runtime class name
   */
  record NioSelector(Set<OperationType> operations, NamePattern channelClassPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.NIO_SELECTOR_SELECT,
            OperationType.NIO_CHANNEL_READ,
            OperationType.NIO_CHANNEL_WRITE,
            OperationType.NIO_CHANNEL_CONNECT,
            OperationType.NIO_CHANNEL_ACCEPT);

    /** Validates the NioSelector parameters. */
    public NioSelector {
      operations = validatedOperations(operations, VALID_OPS, "NioSelector");
      channelClassPattern = channelClassPattern == null ? NamePattern.any() : channelClassPattern;
    }
  }

  /**
   * Matches socket/network operations, optionally filtering by remote host name or IP string.
   *
   * <p><b>Valid operations:</b> {@link OperationType#SOCKET_CONNECT}, {@link
   * OperationType#SOCKET_ACCEPT}, {@link OperationType#SOCKET_READ}, {@link
   * OperationType#SOCKET_WRITE}, {@link OperationType#SOCKET_CLOSE}.
   *
   * <p>For {@link OperationType#SOCKET_CONNECT} the {@code remoteHostPattern} is matched against
   * the {@link java.net.InetSocketAddress} host string. For other operations the pattern is matched
   * against the remote host recorded at connect time.
   *
   * @param operations operation types this selector matches
   * @param remoteHostPattern pattern matched against the remote host name or IP string
   */
  record NetworkSelector(Set<OperationType> operations, NamePattern remoteHostPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.SOCKET_CONNECT,
            OperationType.SOCKET_ACCEPT,
            OperationType.SOCKET_READ,
            OperationType.SOCKET_WRITE,
            OperationType.SOCKET_CLOSE);

    /** Validates the NetworkSelector parameters. */
    public NetworkSelector {
      operations = validatedOperations(operations, VALID_OPS, "NetworkSelector");
      remoteHostPattern = remoteHostPattern == null ? NamePattern.any() : remoteHostPattern;
    }
  }

  /**
   * Matches {@link ThreadLocal} get and set operations, optionally filtering by the ThreadLocal
   * subclass name. The {@code threadLocalClassPattern} is matched against the runtime class name of
   * the {@code ThreadLocal} instance being read or written.
   *
   * <p><b>Valid operations:</b> {@link OperationType#THREAD_LOCAL_GET}, {@link
   * OperationType#THREAD_LOCAL_SET}.
   *
   * <p><b>Caution:</b> {@code ThreadLocal.get()} is called pervasively inside the JVM and the chaos
   * runtime itself. The agent reentrancy guard in {@code BootstrapDispatcher} prevents infinite
   * recursion, but activating this selector without a restrictive {@code threadLocalClassPattern}
   * will match a very high volume of calls.
   *
   * @param operations operation types this selector matches
   * @param threadLocalClassPattern pattern matched against the ThreadLocal subclass name
   */
  record ThreadLocalSelector(Set<OperationType> operations, NamePattern threadLocalClassPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(OperationType.THREAD_LOCAL_GET, OperationType.THREAD_LOCAL_SET);

    public ThreadLocalSelector {
      operations = validatedOperations(operations, VALID_OPS, "ThreadLocalSelector");
      threadLocalClassPattern =
          threadLocalClassPattern == null ? NamePattern.any() : threadLocalClassPattern;
    }
  }

  /**
   * Matches HTTP client send operations across supported clients, optionally filtering by request
   * URL.
   *
   * <p>The {@code urlPattern} is matched against the request URL in {@code scheme://host/path}
   * form, e.g. {@code "https://api.example.com/users"}. A {@link NamePattern#any()} value matches
   * every HTTP call irrespective of URL.
   *
   * <p><b>Valid operations:</b> {@link OperationType#HTTP_CLIENT_SEND}, {@link
   * OperationType#HTTP_CLIENT_SEND_ASYNC}.
   *
   * @param operations operation types this selector matches
   * @param urlPattern pattern matched against the HTTP request URL
   */
  record HttpClientSelector(Set<OperationType> operations, NamePattern urlPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(OperationType.HTTP_CLIENT_SEND, OperationType.HTTP_CLIENT_SEND_ASYNC);

    /** Validates the HttpClientSelector parameters. */
    public HttpClientSelector {
      operations = validatedOperations(operations, VALID_OPS, "HttpClientSelector");
      urlPattern = urlPattern == null ? NamePattern.any() : urlPattern;
    }
  }

  /**
   * Matches JDBC connection-pool and {@link java.sql.Connection} / {@link java.sql.Statement}
   * operations, optionally filtering by a target-name pattern (pool identifier or SQL snippet).
   *
   * <p>The {@code targetPattern} is matched against the {@code targetName} field of the invocation
   * context, which is populated by the instrumentation with one of:
   *
   * <ul>
   *   <li>the pool identifier (e.g. HikariCP pool name) for {@link
   *       OperationType#JDBC_CONNECTION_ACQUIRE}
   *   <li>a SQL snippet (first 200 characters) for {@link OperationType#JDBC_STATEMENT_EXECUTE} and
   *       {@link OperationType#JDBC_PREPARED_STATEMENT}
   *   <li>{@code null} for {@link OperationType#JDBC_TRANSACTION_COMMIT} and {@link
   *       OperationType#JDBC_TRANSACTION_ROLLBACK}
   * </ul>
   *
   * <p><b>Valid operations:</b> {@link OperationType#JDBC_CONNECTION_ACQUIRE}, {@link
   * OperationType#JDBC_STATEMENT_EXECUTE}, {@link OperationType#JDBC_PREPARED_STATEMENT}, {@link
   * OperationType#JDBC_TRANSACTION_COMMIT}, {@link OperationType#JDBC_TRANSACTION_ROLLBACK}.
   *
   * @param operations operation types this selector matches
   * @param targetPattern pattern matched against the pool identifier or SQL snippet
   */
  record JdbcSelector(Set<OperationType> operations, NamePattern targetPattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(
            OperationType.JDBC_CONNECTION_ACQUIRE,
            OperationType.JDBC_STATEMENT_EXECUTE,
            OperationType.JDBC_PREPARED_STATEMENT,
            OperationType.JDBC_TRANSACTION_COMMIT,
            OperationType.JDBC_TRANSACTION_ROLLBACK);

    /** Validates the JdbcSelector parameters. */
    public JdbcSelector {
      operations = validatedOperations(operations, VALID_OPS, "JdbcSelector");
      targetPattern = targetPattern == null ? NamePattern.any() : targetPattern;
    }
  }

  /**
   * Matches DNS resolution operations, optionally filtering by the hostname being resolved.
   *
   * <p>The {@code hostnamePattern} is matched against the hostname argument passed to {@link
   * java.net.InetAddress#getByName(String)} or {@link java.net.InetAddress#getAllByName(String)}.
   * For {@link java.net.InetAddress#getLocalHost()} the hostname is {@code null}; a {@link
   * NamePattern#any()} pattern matches all DNS calls including {@code getLocalHost()}.
   *
   * <p><b>Valid operations:</b> {@link OperationType#DNS_RESOLVE}.
   *
   * @param operations operation types this selector matches
   * @param hostnamePattern pattern matched against the hostname being resolved
   */
  record DnsSelector(Set<OperationType> operations, NamePattern hostnamePattern)
      implements ChaosSelector {
    static final Set<OperationType> VALID_OPS = EnumSet.of(OperationType.DNS_RESOLVE);

    /** Validates the DnsSelector parameters. */
    public DnsSelector {
      operations = validatedOperations(operations, VALID_OPS, "DnsSelector");
      hostnamePattern = hostnamePattern == null ? NamePattern.any() : hostnamePattern;
    }
  }

  /**
   * Matches SSL/TLS handshake operations on both {@link javax.net.ssl.SSLSocket} and {@link
   * javax.net.ssl.SSLEngine}.
   *
   * <p><b>Valid operations:</b> {@link OperationType#SSL_HANDSHAKE}.
   *
   * @param operations operation types this selector matches
   */
  record SslSelector(Set<OperationType> operations) implements ChaosSelector {
    static final Set<OperationType> VALID_OPS = EnumSet.of(OperationType.SSL_HANDSHAKE);

    /** Validates the SslSelector parameters. */
    public SslSelector {
      operations = validatedOperations(operations, VALID_OPS, "SslSelector");
    }
  }

  /**
   * Matches file I/O read and write operations on {@link java.io.FileInputStream} and {@link
   * java.io.FileOutputStream}.
   *
   * <p><b>Valid operations:</b> {@link OperationType#FILE_IO_READ}, {@link
   * OperationType#FILE_IO_WRITE}.
   *
   * @param operations operation types this selector matches
   */
  record FileIoSelector(Set<OperationType> operations) implements ChaosSelector {
    static final Set<OperationType> VALID_OPS =
        EnumSet.of(OperationType.FILE_IO_READ, OperationType.FILE_IO_WRITE);

    /** Validates the FileIoSelector parameters. */
    public FileIoSelector {
      operations = validatedOperations(operations, VALID_OPS, "FileIoSelector");
    }
  }

  /**
   * Activates a standalone stressor effect. Unlike interception selectors this does not match
   * invocation events — it triggers the stressor immediately on activation and runs it until the
   * handle is closed or the activation policy expires.
   *
   * <p>The StressTarget value must correspond to the ChaosEffect type provided in the same
   * ChaosScenario. This correspondence is enforced by the runtime validator at activation time.
   *
   * @param target stressor target whose type must match the paired {@link ChaosEffect}
   */
  record StressSelector(StressTarget target) implements ChaosSelector {
    /** Validates the StressSelector parameters. */
    public StressSelector {
      if (target == null) {
        throw new IllegalArgumentException("target must not be null");
      }
    }
  }

  // ── Internal utilities ─────────────────────────────────────────────────────

  private static Set<OperationType> validatedOperations(
      final Set<OperationType> operations,
      final Set<OperationType> validOps,
      final String selectorName) {
    if (operations == null || operations.isEmpty()) {
      throw new IllegalArgumentException("operations must not be empty");
    }
    for (final OperationType op : operations) {
      if (!validOps.contains(op)) {
        throw new IllegalArgumentException(
            selectorName
                + " does not support operation "
                + op
                + "; valid operations are: "
                + validOps);
      }
    }
    // Set.copyOf returns a truly unmodifiable snapshot — prevents callers (and the record's
    // auto-generated accessor) from leaking a reference the caller can mutate behind our back.
    // We accept the loss of EnumSet bulk-op speed on the hot path; the selector's `contains`
    // checks remain O(1) against the compact ImmutableCollections set.
    return Set.copyOf(operations);
  }
}

package com.macstab.chaos.jvm.api;

/**
 * Identifies the JVM operation being intercepted or the lifecycle event being signalled.
 *
 * <p>Each value maps to a specific instrumentation point installed by the agent. Selectors declare
 * which operation types they match; the runtime evaluates active scenarios only when the
 * corresponding instrumentation fires.
 *
 * <p>Valid selector ↔ operation pairings:
 *
 * <ul>
 *   <li>{@link ChaosSelector.ThreadSelector} — {@link #THREAD_START}, {@link #VIRTUAL_THREAD_START}
 *   <li>{@link ChaosSelector.ExecutorSelector} — {@link #EXECUTOR_SUBMIT}, {@link
 *       #EXECUTOR_WORKER_RUN}, {@link #EXECUTOR_SHUTDOWN}, {@link #EXECUTOR_AWAIT_TERMINATION}
 *   <li>{@link ChaosSelector.QueueSelector} — {@link #QUEUE_PUT}, {@link #QUEUE_OFFER}, {@link
 *       #QUEUE_TAKE}, {@link #QUEUE_POLL}
 *   <li>{@link ChaosSelector.AsyncSelector} — {@link #ASYNC_COMPLETE}, {@link
 *       #ASYNC_COMPLETE_EXCEPTIONALLY}, {@link #ASYNC_CANCEL}
 *   <li>{@link ChaosSelector.SchedulingSelector} — {@link #SCHEDULE_SUBMIT}, {@link #SCHEDULE_TICK}
 *   <li>{@link ChaosSelector.ShutdownSelector} — {@link #SHUTDOWN_HOOK_REGISTER}, {@link
 *       #EXECUTOR_SHUTDOWN}, {@link #EXECUTOR_AWAIT_TERMINATION}
 *   <li>{@link ChaosSelector.ClassLoadingSelector} — {@link #CLASS_LOAD}, {@link #CLASS_DEFINE},
 *       {@link #RESOURCE_LOAD}
 *   <li>{@link ChaosSelector.MethodSelector} — {@link #METHOD_ENTER}, {@link #METHOD_EXIT}
 *   <li>{@link ChaosSelector.MonitorSelector} — {@link #MONITOR_ENTER}, {@link #THREAD_PARK}
 *   <li>{@link ChaosSelector.JvmRuntimeSelector} — {@link #SYSTEM_CLOCK_MILLIS}, {@link
 *       #SYSTEM_CLOCK_NANOS}, {@link #INSTANT_NOW}, {@link #CLOCK_READ}, {@link
 *       #LOCAL_DATE_TIME_NOW}, {@link #ZONED_DATE_TIME_NOW}, {@link #DATE_NEW}, {@link
 *       #SYSTEM_GC_REQUEST}, {@link #SYSTEM_EXIT_REQUEST}, {@link #REFLECTION_INVOKE}, {@link
 *       #DIRECT_BUFFER_ALLOCATE}, {@link #OBJECT_DESERIALIZE}, {@link #OBJECT_SERIALIZE}, {@link
 *       #NATIVE_LIBRARY_LOAD}, {@link #JNDI_LOOKUP}, {@link #JMX_INVOKE}, {@link #JMX_GET_ATTR},
 *       {@link #ZIP_INFLATE}, {@link #ZIP_DEFLATE}
 *   <li>{@link ChaosSelector.NioSelector} — {@link #NIO_SELECTOR_SELECT}, {@link
 *       #NIO_CHANNEL_READ}, {@link #NIO_CHANNEL_WRITE}, {@link #NIO_CHANNEL_CONNECT}, {@link
 *       #NIO_CHANNEL_ACCEPT}
 *   <li>{@link ChaosSelector.NetworkSelector} — {@link #SOCKET_CONNECT}, {@link #SOCKET_ACCEPT},
 *       {@link #SOCKET_READ}, {@link #SOCKET_WRITE}, {@link #SOCKET_CLOSE}, {@link #SOCKET_BIND},
 *       {@link #SOCKET_SHUTDOWN}, {@link #DATAGRAM_SEND}, {@link #DATAGRAM_RECEIVE}
 *   <li>{@link ChaosSelector.ThreadLocalSelector} — {@link #THREAD_LOCAL_GET}, {@link
 *       #THREAD_LOCAL_SET}
 *   <li>{@link ChaosSelector.HttpClientSelector} — {@link #HTTP_CLIENT_SEND}, {@link
 *       #HTTP_CLIENT_SEND_ASYNC}
 *   <li>{@link ChaosSelector.JdbcSelector} — {@link #JDBC_CONNECTION_ACQUIRE}, {@link
 *       #JDBC_STATEMENT_EXECUTE}, {@link #JDBC_PREPARED_STATEMENT}, {@link
 *       #JDBC_TRANSACTION_COMMIT}, {@link #JDBC_TRANSACTION_ROLLBACK}
 *   <li>{@link ChaosSelector.ThreadSelector} — {@link #THREAD_SLEEP} (in addition to thread start
 *       operations)
 *   <li>{@link ChaosSelector.DnsSelector} — {@link #DNS_RESOLVE}, {@link #DNS_ANSWER}, {@link
 *       #DNS_REVERSE}
 *   <li>{@link ChaosSelector.SslSelector} — {@link #SSL_HANDSHAKE}
 *   <li>{@link ChaosSelector.FileIoSelector} — {@link #FILE_IO_READ}, {@link #FILE_IO_WRITE},
 *       {@link #FILE_IO_SYNC}, {@link #FILE_IO_TRUNCATE}, {@link #FILE_IO_DELETE}, {@link
 *       #FILE_IO_MOVE}, {@link #FILE_IO_MOVE_TARGET}, {@link #FILE_IO_OPEN}, {@link #FILE_IO_MAP},
 *       {@link #FILE_IO_CLOSE}
 *   <li>{@link ChaosSelector.MessagingSelector} — {@link #MESSAGE_SEND}, {@link #MESSAGE_RECEIVE}
 *   <li>{@link ChaosSelector.RpcSelector} — {@link #RPC_CALL}
 *   <li>{@link ChaosSelector.RedisSelector} — {@link #REDIS_COMMAND}
 *   <li>{@link ChaosSelector.AwsSdkSelector} — {@link #AWS_SDK_CALL}
 *   <li>{@link ChaosSelector.ProcessSelector} — {@link #PROCESS_START}, {@link #PROCESS_WAIT}
 *   <li>{@link ChaosSelector.NettySelector} — {@link #NETTY_CHANNEL_WRITE}, {@link
 *       #NETTY_CHANNEL_READ}
 *   <li>{@link ChaosSelector.JdbcSelector} — also {@link #R2DBC_STATEMENT_EXECUTE}
 *   <li>{@link ChaosSelector.StressSelector} — {@link #LIFECYCLE} (internal; not set by callers)
 * </ul>
 */
public enum OperationType {

  // ── Thread lifecycle ────────────────────────────────────────────────────────

  /**
   * Fires before a platform thread is started via {@link Thread#start()}. Chaos applied here can
   * delay, reject, or otherwise prevent the thread from starting.
   *
   * <p>Use with {@link ChaosSelector.ThreadSelector} filtered to {@link
   * ChaosSelector.ThreadKind#PLATFORM}.
   */
  THREAD_START,

  /**
   * Fires before a virtual thread (Project Loom) is started. Requires JDK 21+ at runtime; the agent
   * probes availability at startup and rejects activation on older runtimes.
   *
   * <p>Use with {@link ChaosSelector.ThreadSelector} filtered to {@link
   * ChaosSelector.ThreadKind#VIRTUAL}.
   */
  VIRTUAL_THREAD_START,

  // ── Executor operations ─────────────────────────────────────────────────────

  /**
   * Fires when a task is submitted to an {@link java.util.concurrent.ExecutorService} via {@code
   * submit()}, {@code execute()}, or {@code invokeAll()}. Chaos here can delay or reject task
   * acceptance before the task enters the work queue.
   */
  EXECUTOR_SUBMIT,

  /**
   * Fires just before a worker thread in an executor pool runs a submitted task. Chaos here applies
   * latency (delay, gate) to the execution phase, independent of submission. A reject is refused at
   * activation: the pool cannot fail the task at this point.
   */
  EXECUTOR_WORKER_RUN,

  /**
   * Fires when {@link java.util.concurrent.ExecutorService#shutdown()} or {@code shutdownNow()} is
   * called. Chaos here can delay or gate the shutdown of executor services. A reject is refused at
   * activation: a shutdown never fails.
   */
  EXECUTOR_SHUTDOWN,

  /**
   * Fires when {@link java.util.concurrent.ExecutorService#awaitTermination} is called. Chaos here
   * can simulate a slow or hung executor that never fully terminates.
   */
  EXECUTOR_AWAIT_TERMINATION,

  // ── ForkJoin ────────────────────────────────────────────────────────────────

  /**
   * Fires before a {@link java.util.concurrent.ForkJoinTask} executes within a {@link
   * java.util.concurrent.ForkJoinPool}. Covers tasks submitted via {@code
   * ForkJoinPool.commonPool()} as well as custom pools, including the pool backing parallel
   * streams. Chaos here applies latency (delay, gate); a reject is refused at activation, because
   * the pool cannot fail the task at this point. Not evaluated on the carrier threads of the JDK's
   * virtual-thread scheduler, where every start and wake-up of a virtual thread runs as a fork-join
   * task; a virtual thread with a custom scheduler is still evaluated.
   */
  FORK_JOIN_TASK_RUN,

  // ── Scheduling ──────────────────────────────────────────────────────────────

  /**
   * Fires when a task is submitted to a {@link java.util.concurrent.ScheduledExecutorService} via
   * {@code schedule()}, {@code scheduleAtFixedRate()}, or {@code scheduleWithFixedDelay()}. Chaos
   * here can delay or suppress the registration of the scheduled task.
   */
  SCHEDULE_SUBMIT,

  /**
   * Fires each time the scheduler's internal timer fires and is about to execute a scheduled task.
   * Applying chaos here simulates timer jitter, task skipping, or slow scheduler throughput without
   * affecting submission.
   */
  SCHEDULE_TICK,

  // ── Blocking queues ─────────────────────────────────────────────────────────

  /**
   * Fires before a blocking {@link java.util.concurrent.BlockingQueue#put} call. Chaos here can add
   * latency to producers, simulating a slow or backpressure-constrained queue.
   */
  QUEUE_PUT,

  /**
   * Fires before a non-blocking {@link java.util.concurrent.BlockingQueue#offer} call. Chaos here
   * can simulate a full queue by returning {@code false} or delaying the offer. A reject or
   * suppress applies only to a queue with a capacity limit: on one without (its {@code
   * remainingCapacity()} is {@code Integer.MAX_VALUE}) the offer runs as usual and counts no
   * application.
   */
  QUEUE_OFFER,

  /**
   * Fires before a blocking {@link java.util.concurrent.BlockingQueue#take} call. Chaos here can
   * add latency to consumers, simulating a slow or starved queue.
   */
  QUEUE_TAKE,

  /**
   * Fires before a {@link java.util.concurrent.BlockingQueue#poll} call, with or without a timeout.
   * Chaos here can simulate an empty queue by returning {@code null} (a poll with a timeout first
   * waits it out, as on a real empty queue) or introduce polling delays.
   */
  QUEUE_POLL,

  // ── Async completion ────────────────────────────────────────────────────────

  /**
   * Fires before {@link java.util.concurrent.CompletableFuture#complete} is called. Chaos here can
   * suppress normal completion or replace it with an exceptional completion.
   *
   * <p>Use with {@link ChaosSelector.AsyncSelector} and {@link
   * ChaosEffect.ExceptionalCompletionEffect}.
   */
  ASYNC_COMPLETE,

  /**
   * Fires before {@link java.util.concurrent.CompletableFuture#completeExceptionally} is called.
   * Chaos here can suppress or replace the exceptional completion.
   */
  ASYNC_COMPLETE_EXCEPTIONALLY,

  // ── JVM shutdown ────────────────────────────────────────────────────────────

  /**
   * Fires when a shutdown hook thread is registered via {@link Runtime#addShutdownHook(Thread)}.
   * Chaos here can delay or reject the registration; the hook itself is registered and run by the
   * JDK.
   */
  SHUTDOWN_HOOK_REGISTER,

  // ── Class and resource loading ──────────────────────────────────────────────

  /**
   * Fires before a class is looked up via {@link ClassLoader#loadClass}. Chaos here can simulate
   * {@link ClassNotFoundException}, introduce class-loading latency, or test classloader fallback
   * paths.
   */
  CLASS_LOAD,

  /**
   * Fires before a class bytecode is defined via {@link ClassLoader#defineClass}. Chaos here can
   * delay or suppress dynamic class definition, affecting bytecode instrumentation agents,
   * ASM/ByteBuddy-generated classes, and Groovy/Kotlin dynamic dispatch.
   */
  CLASS_DEFINE,

  /**
   * Fires when {@link ClassLoader#getResource} or {@link ClassLoader#getResourceAsStream} is
   * called. Chaos here can return {@code null} to simulate missing classpath resources, or delay
   * the lookup to expose lazy-loading assumptions.
   */
  RESOURCE_LOAD,

  // ── Method-level interception ───────────────────────────────────────────────

  /**
   * Fires on entry to a method a {@link ChaosSelector.MethodSelector} names. Combined with {@link
   * ChaosEffect.ExceptionInjectionEffect} this injects any exception into any method without
   * modifying its source.
   *
   * <p>With the agent attached, the methods are woven automatically when the scenario is activated
   * (see {@link ChaosSelector.MethodSelector}). Without the agent — a {@code ChaosRuntime} in a
   * plain unit test — the event can be raised by interception the application already runs (Spring
   * AOP, AspectJ, Micronaut / Quarkus interceptors). Inside that interceptor:
   *
   * <pre>{@code
   * @Around("@annotation(com.example.Audited)")
   * public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
   *   String cls = pjp.getTarget().getClass().getName();
   *   String mth = pjp.getSignature().getName();
   *   chaosRuntime.beforeMethodEnter(cls, mth);  // may throw the configured exception
   *   return pjp.proceed();
   * }
   * }</pre>
   *
   * <p>Used exclusively with {@link ChaosSelector.MethodSelector}.
   *
   * @see ChaosSelector.MethodSelector
   * @see ChaosEffect.ExceptionInjectionEffect
   */
  METHOD_ENTER,

  /**
   * Fires on the normal exit of a method a {@link ChaosSelector.MethodSelector} names. Combined
   * with {@link ChaosEffect.ReturnValueCorruptionEffect} this replaces the return value with a
   * boundary, null, zero, or empty value.
   *
   * <p>Woven automatically like {@link #METHOD_ENTER}; without the agent, raise it from the same
   * interceptor:
   *
   * <pre>{@code
   * Object result = pjp.proceed();
   * return chaosRuntime.afterMethodExit(
   *     cls, mth,
   *     ((MethodSignature) pjp.getSignature()).getReturnType(),
   *     result);  // returns possibly-corrupted value
   * }</pre>
   *
   * <p>Used exclusively with {@link ChaosSelector.MethodSelector}.
   *
   * @see ChaosSelector.MethodSelector
   * @see ChaosEffect.ReturnValueCorruptionEffect
   */
  METHOD_EXIT,

  // ── Monitor and parking ─────────────────────────────────────────────────────

  /**
   * Fires before a thread acquires a {@code synchronized} monitor or calls {@link Object#wait()}.
   * Chaos here can inject latency before lock acquisition to simulate lock contention and convoy
   * effects. A reject is refused at activation: entering a monitor never fails.
   *
   * <p>Used exclusively with {@link ChaosSelector.MonitorSelector}.
   */
  MONITOR_ENTER,

  /**
   * Fires before a thread parks itself via {@link java.util.concurrent.locks.LockSupport#park}.
   * Chaos here can delay or gate the park to expose threading bugs in lock-based data structures. A
   * reject is refused at activation: a park never fails.
   *
   * <p>Used exclusively with {@link ChaosSelector.MonitorSelector}.
   */
  THREAD_PARK,

  // ── JVM runtime services ────────────────────────────────────────────────────

  /**
   * Fires when application code routes a {@link System#currentTimeMillis()} value through the
   * agent's public clock-skew hook. Used with {@link ChaosEffect.ClockSkewEffect} to apply fixed,
   * drifting, or frozen offsets and test time-dependent logic.
   *
   * <p><b>Call sites, not the method.</b> {@link System#currentTimeMillis()} is {@code public
   * static native} and marked {@code @IntrinsicCandidate}, and HotSpot's JIT replaces the call with
   * a direct hardware-clock instruction (RDTSC / MRS CNTVCT_EL0) that bypasses any bytecode
   * wrapper, so the method itself cannot be advised. Its call sites can: with the agent setting
   * {@code clockCallSites=<package prefixes>}, calls in those packages are rewritten to read the
   * clock through this operation while a scenario needs it. Without the setting, route real time
   * through the agent yourself:
   *
   * <pre>{@code
   * long real = System.currentTimeMillis();
   * long now  = chaosRuntime.adjustClockMillis(real); // returns chaos-skewed value
   * }</pre>
   *
   * <p>Typical wiring point: an application-level {@code TimeProvider} / {@code Clock} wrapper
   * already used in place of {@code System.currentTimeMillis()}. For skew without any setting, use
   * {@link #INSTANT_NOW}, which the agent always wires.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  SYSTEM_CLOCK_MILLIS,

  /**
   * Fires when application code routes a {@link System#nanoTime()} value through the agent's public
   * clock-skew hook. A backward skew intentionally violates the monotonicity contract to expose
   * assumptions in timing loops and profiling code.
   *
   * <p><b>Call sites, not the method.</b> Same JVM constraint as {@link #SYSTEM_CLOCK_MILLIS}:
   * {@link System#nanoTime()} is {@code native @IntrinsicCandidate} and is JIT-replaced with a
   * direct hardware counter read. With {@code clockCallSites} its call sites in the named packages
   * are rewritten; otherwise route real time through the agent yourself:
   *
   * <pre>{@code
   * long real = System.nanoTime();
   * long now  = chaosRuntime.adjustClockNanos(real); // returns chaos-skewed value
   * }</pre>
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  SYSTEM_CLOCK_NANOS,

  /**
   * Fires on every call to {@link java.time.Instant#now()}. Used with {@link
   * ChaosEffect.ClockSkewEffect} to apply the same fixed, drifting, or frozen offsets as {@link
   * #SYSTEM_CLOCK_MILLIS} but at the higher-level {@link java.time.Instant} API.
   *
   * <p>This operation exists because direct interception of {@link System#currentTimeMillis()} is
   * blocked by JVM retransformation and {@code @IntrinsicCandidate} constraints; modern code that
   * reads the wall clock via {@link java.time.Instant#now()} is instrumentable here instead.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  INSTANT_NOW,

  /**
   * Fires when the system {@link java.time.Clock} is read: {@code instant()} and {@code millis()}
   * of {@link java.time.Clock#systemUTC()}, {@link java.time.Clock#systemDefaultZone()}, {@link
   * java.time.Clock#system(java.time.ZoneId)} and {@link java.time.InstantSource#system()}, and so
   * every {@code java.time} {@code now()} without an operation of its own: {@code LocalDate},
   * {@code LocalTime}, {@code OffsetDateTime}, {@code OffsetTime}, {@code Year}, {@code YearMonth},
   * {@code MonthDay}, and clocks derived from the system clock ({@code Clock.offset}, {@code
   * Clock.tick}). The reads inside {@link #INSTANT_NOW}, {@link #LOCAL_DATE_TIME_NOW} and {@link
   * #ZONED_DATE_TIME_NOW} belong to those operations and are not skewed a second time.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector} and a clock-skew effect.
   */
  CLOCK_READ,

  /**
   * Fires on every call to {@link java.time.LocalDateTime#now()} (no-argument variant). Used with
   * {@link ChaosEffect.ClockSkewEffect} to skew the returned local date-time.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  LOCAL_DATE_TIME_NOW,

  /**
   * Fires on every call to {@link java.time.ZonedDateTime#now()} (no-argument variant). Used with
   * {@link ChaosEffect.ClockSkewEffect} to skew the returned zoned date-time.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  ZONED_DATE_TIME_NOW,

  /**
   * Fires on every invocation of the no-argument {@link java.util.Date#Date()} constructor. Used
   * with {@link ChaosEffect.ClockSkewEffect} to skew the embedded wall-clock value before the
   * constructor returns to the caller.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  DATE_NEW,

  /**
   * Fires when {@link System#gc()} or {@link Runtime#gc()} is called. Chaos here can suppress
   * explicit GC requests or inject delays, testing code that relies on GC hints for memory
   * management. A reject is refused at activation: a GC request never fails.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  SYSTEM_GC_REQUEST,

  /**
   * Fires when {@link System#exit(int)} or {@link Runtime#halt(int)} is called. Chaos here can
   * delay or suppress process exit, allowing assertions to run after code that calls {@code
   * System.exit} in error paths. A reject is refused at activation: an exit never fails.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  SYSTEM_EXIT_REQUEST,

  /**
   * Fires on every call to {@link java.lang.reflect.Method#invoke}. Chaos here can inject latency
   * into reflection-heavy frameworks (Spring, Hibernate, Jackson) or simulate {@link
   * java.lang.reflect.InvocationTargetException} wrapping.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  REFLECTION_INVOKE,

  /**
   * Fires when {@link java.nio.ByteBuffer#allocateDirect} is called. Chaos here can delay or
   * suppress native direct memory allocation, testing OOM handling in NIO and Netty-based stacks.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  DIRECT_BUFFER_ALLOCATE,

  /**
   * Fires before {@link java.io.ObjectInputStream#readObject()} deserializes an object graph. Chaos
   * here can inject deserialization exceptions to test error handling in RPC layers and caches that
   * rely on Java serialization.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  OBJECT_DESERIALIZE,

  // ── NIO channels and selectors ──────────────────────────────────────────────

  /**
   * Fires on every call to {@link java.nio.channels.Selector#select()}, {@code select(long)}, or
   * {@code selectNow()}. Chaos here can inject spurious wakeups (returning 0 with no ready keys) or
   * add latency, testing event-loop resilience in Netty, gRPC-Java, and Tomcat NIO.
   *
   * <p>Use with {@link ChaosSelector.NioSelector}.
   */
  NIO_SELECTOR_SELECT,

  /**
   * Fires before a {@link java.nio.channels.ReadableByteChannel#read} call. Chaos here can add
   * latency or inject {@link java.io.IOException} to simulate partial reads and connection drops.
   *
   * <p>Use with {@link ChaosSelector.NioSelector}.
   */
  NIO_CHANNEL_READ,

  /**
   * Fires before a {@link java.nio.channels.WritableByteChannel#write} call. Chaos here can add
   * latency or inject {@link java.io.IOException} to simulate write failures and backpressure.
   *
   * <p>Use with {@link ChaosSelector.NioSelector}.
   */
  NIO_CHANNEL_WRITE,

  /**
   * Fires before {@link java.nio.channels.SocketChannel#connect} is called. Chaos here can reject
   * the connection attempt to test circuit-breaker and retry behaviour.
   *
   * <p>Use with {@link ChaosSelector.NioSelector}.
   */
  NIO_CHANNEL_CONNECT,

  /**
   * Fires before {@link java.nio.channels.ServerSocketChannel#accept} is called. Chaos here can add
   * latency or suppress the accept to simulate a saturated server.
   *
   * <p>Use with {@link ChaosSelector.NioSelector}.
   */
  NIO_CHANNEL_ACCEPT,

  // ── Socket / network ────────────────────────────────────────────────────────

  /**
   * Fires before {@link java.net.Socket#connect} is called. Chaos here can inject latency or throw
   * {@link java.net.ConnectException} to test connection-refused handling.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}.
   */
  SOCKET_CONNECT,

  /**
   * Fires before {@link java.net.ServerSocket#accept} is called. Chaos here can add latency to
   * simulate a slow accept loop.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}.
   */
  SOCKET_ACCEPT,

  /**
   * Fires before a socket read operation. Chaos here can inject latency to simulate network
   * congestion, or inject EOF to simulate unexpected connection closure.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}.
   */
  SOCKET_READ,

  /**
   * Fires before a socket write operation. Chaos here can inject latency to simulate slow network
   * paths or a receiver that is not draining its buffer.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}.
   */
  SOCKET_WRITE,

  /**
   * Fires before {@link java.net.Socket#close} is called. Chaos here can delay socket closure to
   * simulate lingering connections or resource-pool exhaustion.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}.
   */
  SOCKET_CLOSE,

  /**
   * Fires before a socket is bound to a local address: {@code Socket.bind}, {@code
   * ServerSocket.bind}, {@code SocketChannel.bind}, {@code ServerSocketChannel.bind} and the
   * implicit binds before them, through the JDK's {@code sun.nio.ch.Net.bind}. An {@code errno}
   * such as {@code EADDRINUSE} fails with {@link java.net.BindException}.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}: for a bind, {@code remoteCidr} and {@code
   * remotePort} match the local address and port being bound.
   */
  SOCKET_BIND,

  /**
   * Fires before a connection is half-closed: {@code Socket.shutdownInput} / {@code shutdownOutput}
   * and those of {@code SocketChannel}. An {@code errno} such as {@code ENOTCONN} fails it with
   * {@link java.net.SocketException}.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}; the peer is the connection's.
   */
  SOCKET_SHUTDOWN,

  /**
   * Fires before a UDP datagram is sent: {@code DatagramSocket.send}, {@code DatagramChannel.send}
   * and {@code DatagramChannel.write}. The peer is the destination, the byte count the datagram's
   * length. {@code ChaosEffect.suppress()} drops the datagram silently: the send reports success
   * and nothing goes on the wire, as a lossy network does.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}.
   */
  DATAGRAM_SEND,

  /**
   * Fires before a UDP datagram is received: {@code DatagramSocket.receive}, {@code
   * DatagramChannel.receive} and {@code DatagramChannel.read}. The peer is known only for a
   * connected socket or channel; the byte count is the space the buffer offers. {@code
   * ChaosEffect.corruptRead()} flips one bit of the datagram received.
   *
   * <p>Use with {@link ChaosSelector.NetworkSelector}.
   */
  DATAGRAM_RECEIVE,

  // ── Extended JVM runtime services ───────────────────────────────────────────

  /**
   * Fires before {@link java.io.ObjectOutputStream#writeObject} serializes an object graph. Chaos
   * here can inject delays or throw {@link java.io.NotSerializableException} to test serialization
   * error handling in RPC layers.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  OBJECT_SERIALIZE,

  /**
   * Fires when {@link System#loadLibrary} or {@link System#load} is called. Chaos here can delay or
   * reject native library loading, testing fallback paths in JNI-dependent code.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  NATIVE_LIBRARY_LOAD,

  /**
   * Fires when {@link javax.naming.Context#lookup} is called. Chaos here can inject latency to
   * simulate slow LDAP/JNDI responses, or throw {@link javax.naming.NamingException} to test
   * resource-lookup failure handling.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  JNDI_LOOKUP,

  /**
   * Fires when {@link javax.management.MBeanServer#invoke} is called. Chaos here can inject latency
   * to simulate slow JMX operations or throw {@link javax.management.MBeanException}.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  JMX_INVOKE,

  /**
   * Fires when {@link javax.management.MBeanServer#getAttribute} is called. Chaos here can inject
   * latency to simulate monitoring timeouts.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  JMX_GET_ATTR,

  /**
   * Fires before {@link java.util.zip.Inflater#inflate} decompresses data. Chaos here can add
   * latency to simulate slow decompression, e.g., during Spring Boot startup from nested JARs or
   * GZIP HTTP response decompression.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  ZIP_INFLATE,

  /**
   * Fires before {@link java.util.zip.Deflater#deflate} compresses data. Chaos here can add latency
   * to simulate slow compression on the response path. A reject is refused at activation: {@code
   * deflate} never fails.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  ZIP_DEFLATE,

  // ── Async completion ────────────────────────────────────────────────────────

  /**
   * Fires before {@link java.util.concurrent.CompletableFuture#cancel} is called. Chaos here can
   * suppress the cancellation (pretend it succeeded without cancelling) or delay it. A reject is
   * refused at activation: a cancel never fails.
   *
   * <p>Use with {@link ChaosSelector.AsyncSelector}.
   */
  ASYNC_CANCEL,

  // ── ThreadLocal operations ───────────────────────────────────────────────────

  /**
   * Fires before {@link ThreadLocal#get()} returns a value. Chaos here can return null to simulate
   * missing request context (e.g., Spring {@code RequestContextHolder}, SLF4J MDC) or delay the
   * lookup. A reject is refused at activation: {@code get()} never fails.
   *
   * <p>Use with {@link ChaosSelector.ThreadLocalSelector}.
   */
  THREAD_LOCAL_GET,

  /**
   * Fires before {@link ThreadLocal#set(Object)} stores a value. Chaos here can suppress the set so
   * that subsequent gets return the old or null value. A reject is refused at activation: {@code
   * set} never fails.
   *
   * <p>Use with {@link ChaosSelector.ThreadLocalSelector}.
   */
  THREAD_LOCAL_SET,

  // ── HTTP client operations ──────────────────────────────────────────────────

  /**
   * Fires before a synchronous HTTP client request is dispatched. Covers {@code
   * java.net.http.HttpClient.send}, {@code okhttp3.RealCall.execute}, and Apache HttpComponents
   * {@code CloseableHttpClient.execute}. Chaos here can inject latency or reject the call with the
   * client's own {@link java.io.IOException}; a suppress is refused at activation (no real client
   * skips a request without failing it).
   *
   * <p>The {@code targetName} field of the invocation context carries the request URL ({@code
   * scheme://host/path}). Use with {@link ChaosSelector.HttpClientSelector}.
   */
  HTTP_CLIENT_SEND,

  /**
   * Fires before an asynchronous HTTP client request is dispatched. Covers {@code
   * java.net.http.HttpClient.sendAsync}, {@code okhttp3.RealCall.enqueue}, and Spring WebClient's
   * Reactor Netty client. Chaos here can inject latency or reject the call with the client's own
   * {@link java.io.IOException}; a suppress is refused at activation.
   *
   * <p>The {@code targetName} field of the invocation context carries the request URL. Use with
   * {@link ChaosSelector.HttpClientSelector}.
   */
  HTTP_CLIENT_SEND_ASYNC,

  // ── JDBC / connection pool operations ──────────────────────────────────────

  /**
   * Fires before a JDBC connection is acquired from a pool. Covers {@code
   * com.zaxxer.hikari.pool.HikariPool.getConnection(long)} and {@code
   * com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool.checkoutPooledConnection()}. Chaos here can
   * inject latency to simulate pool exhaustion or throw to simulate {@link java.sql.SQLException}.
   *
   * <p>The {@code targetName} field of the invocation context carries the pool identifier. Use with
   * {@link ChaosSelector.JdbcSelector}.
   */
  JDBC_CONNECTION_ACQUIRE,

  /**
   * Fires before {@link java.sql.Statement#execute(String)}, {@link
   * java.sql.Statement#executeQuery(String)}, or {@link java.sql.Statement#executeUpdate(String)}.
   * Chaos here can inject latency to simulate slow queries or throw to simulate SQL failures.
   *
   * <p>The {@code targetName} field of the invocation context carries a SQL snippet (first 200
   * characters). Use with {@link ChaosSelector.JdbcSelector}.
   */
  JDBC_STATEMENT_EXECUTE,

  /**
   * Fires before {@link java.sql.Connection#prepareStatement(String)}. Chaos here can inject
   * latency during statement preparation or throw to simulate parse failures.
   *
   * <p>The {@code targetName} field of the invocation context carries a SQL snippet (first 200
   * characters). Use with {@link ChaosSelector.JdbcSelector}.
   */
  JDBC_PREPARED_STATEMENT,

  /**
   * Fires before {@link java.sql.Connection#commit()}. Chaos here can inject latency or throw to
   * simulate transaction commit failures.
   *
   * <p>Use with {@link ChaosSelector.JdbcSelector}.
   */
  JDBC_TRANSACTION_COMMIT,

  /**
   * Fires before {@link java.sql.Connection#rollback()}. Chaos here can inject latency or throw to
   * simulate transaction rollback failures.
   *
   * <p>Use with {@link ChaosSelector.JdbcSelector}.
   */
  JDBC_TRANSACTION_ROLLBACK,

  // ── Thread sleep ────────────────────────────────────────────────────────────

  /**
   * Fires before {@link Thread#sleep(long)} is called. Chaos here can suppress the sleep entirely
   * (returning immediately, simulating a spurious wake-up) or inject a delay. Suppressing the sleep
   * is particularly useful for exposing race conditions hidden by artificial pauses in test code or
   * retry loops that back off via {@code Thread.sleep}.
   *
   * <p>Use with {@link ChaosSelector.ThreadSelector}.
   */
  THREAD_SLEEP,

  // ── DNS resolution ──────────────────────────────────────────────────────────

  /**
   * Fires before {@link java.net.InetAddress#getByName(String)}, {@link
   * java.net.InetAddress#getAllByName(String)}, or {@link java.net.InetAddress#getLocalHost()} is
   * called. Chaos here can inject latency to simulate slow DNS, or throw {@link
   * java.net.UnknownHostException} to simulate DNS lookup failures.
   *
   * <p>The {@code targetName} field of the invocation context carries the hostname being resolved
   * ({@code null} for {@code getLocalHost()}). Use with {@link ChaosSelector.DnsSelector}.
   */
  DNS_RESOLVE,

  /**
   * Fires when {@code InetAddress.getAllByName} returns or fails, with the answer: the point where
   * a {@link ChaosEffect.DnsAnswerEffect} overrides, filters, shuffles or limits the addresses.
   * {@code InetAddress.getByName} resolves through it too. Evaluated once per lookup, separately
   * from {@link #DNS_RESOLVE} (which fires before the lookup).
   *
   * <p>Use with {@link ChaosSelector.DnsSelector}.
   */
  DNS_ANSWER,

  /**
   * Fires before a reverse lookup: {@link java.net.InetAddress#getHostName()} and {@link
   * java.net.InetAddress#getCanonicalHostName()} of an address without a known name. The host
   * pattern matches the address's IP literal. A rejection fails the lookup the way the JDK reports
   * it: the IP literal is returned instead of a name.
   *
   * <p>Use with {@link ChaosSelector.DnsSelector}.
   */
  DNS_REVERSE,

  // ── SSL/TLS handshake ───────────────────────────────────────────────────────

  /**
   * Fires before {@link javax.net.ssl.SSLSocket#startHandshake()} or {@link
   * javax.net.ssl.SSLEngine#beginHandshake()} initiates a TLS handshake. Chaos here can inject
   * latency to simulate slow TLS negotiation, or throw {@link javax.net.ssl.SSLHandshakeException}
   * to simulate certificate validation failures and broken TLS stacks.
   *
   * <p>Use with {@link ChaosSelector.SslSelector}.
   */
  SSL_HANDSHAKE,

  // ── File I/O ────────────────────────────────────────────────────────────────

  /**
   * Fires before a {@link java.io.FileInputStream#read} call. Chaos here can inject latency to
   * simulate a slow disk, or throw {@link java.io.IOException} to simulate read failures.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}.
   */
  FILE_IO_READ,

  /**
   * Fires before a {@link java.io.FileOutputStream#write} call. Chaos here can inject latency to
   * simulate a slow or full disk, or throw {@link java.io.IOException} to simulate write failures.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}.
   */
  FILE_IO_WRITE,

  /**
   * Fires before data is forced to the storage device: {@code FileChannel.force(boolean)} and
   * {@code FileDescriptor.sync()} (fsync). A reject or an {@code ErrnoEffect} throws {@link
   * java.io.SyncFailedException}: the durability failure write-ahead logs and databases must
   * survive.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}.
   */
  FILE_IO_SYNC,

  /**
   * Fires before a file is shortened or extended: {@code FileChannel.truncate(long)} and {@code
   * RandomAccessFile.setLength(long)}. A reject or an {@code ErrnoEffect} ({@code EIO}) throws
   * {@link java.io.IOException}.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}; the path is the file's.
   */
  FILE_IO_TRUNCATE,

  /**
   * Fires before a file or empty directory is deleted: {@code Files.delete}, {@code
   * Files.deleteIfExists} and {@link java.io.File#delete()}. A reject or an {@code ErrnoEffect}
   * fails the delete: the {@code Files} methods throw ({@link java.io.IOException} or the matching
   * {@link java.nio.file.FileSystemException}), {@code File.delete()} returns {@code false}, which
   * is how {@code java.io} reports a failed delete.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}; the path is the file being deleted.
   */
  FILE_IO_DELETE,

  /**
   * Fires before a file is moved or renamed: {@code Files.move} and {@link
   * java.io.File#renameTo(java.io.File)}. Failures behave as for {@link #FILE_IO_DELETE}: the
   * {@code Files} method throws, {@code File.renameTo} returns {@code false}.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}; the path is the source.
   */
  FILE_IO_MOVE,

  /**
   * Fires before a file is opened: the {@code FileInputStream}, {@code FileOutputStream} and {@code
   * RandomAccessFile} constructors, and the platform file system's {@code newByteChannel} and
   * {@code newFileChannel} ({@code Files.newInputStream}, {@code Files.newOutputStream}, {@code
   * Files.newByteChannel}, {@code FileChannel.open} and the readers and writers built on them). A
   * {@code java.io} constructor fails with {@link java.io.FileNotFoundException} ({@code "path
   * (reason)"}), as the JDK does; a {@code java.nio} open with the matching {@link
   * java.nio.file.FileSystemException}.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}; the path is the file opened.
   */
  FILE_IO_OPEN,

  /**
   * Fires before a file region is memory-mapped: {@code FileChannel.map}, also to a {@code
   * MemorySegment}. The byte count is the size of the region. {@code ENOMEM} fails with the JDK's
   * {@code IOException("Map failed")}.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}; the path is the channel's file.
   */
  FILE_IO_MAP,

  /**
   * Fires after a file is closed: the {@code FileInputStream}, {@code FileOutputStream} and {@code
   * RandomAccessFile} {@code close()}, and a {@code FileChannel}'s. Evaluated once the descriptor
   * is released, as a real {@code close} that reports an error ({@code EIO}, {@code ENOSPC} on a
   * network file system) still releases it; a stream and its channel are one close.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}; the path is the file closed.
   */
  FILE_IO_CLOSE,

  /**
   * Fires before a file is moved or renamed, by its destination: {@code Files.move} and {@link
   * java.io.File#renameTo(java.io.File)}, as {@link #FILE_IO_MOVE} is by its source. Failures
   * behave as for {@link #FILE_IO_MOVE}.
   *
   * <p>Use with {@link ChaosSelector.FileIoSelector}; the path is the destination.
   */
  FILE_IO_MOVE_TARGET,

  // ── Client libraries ───────────────────────────────────────────────────────────

  /**
   * Fires before a Kafka producer sends a record: {@code KafkaProducer.send(ProducerRecord,
   * Callback)} (the one-argument {@code send} delegates to it). The destination is the record's
   * topic. A reject throws Kafka's {@code org.apache.kafka.common.errors.TimeoutException} from
   * {@code send()}.
   *
   * <p>Use with {@link ChaosSelector.MessagingSelector}.
   */
  MESSAGE_SEND,

  /**
   * Fires before a Kafka consumer polls: {@code KafkaConsumer.poll(Duration)}. The destinations are
   * the consumer's subscribed topics. A reject throws Kafka's {@code
   * org.apache.kafka.common.errors.TimeoutException} from {@code poll()}.
   *
   * <p>Use with {@link ChaosSelector.MessagingSelector}.
   */
  MESSAGE_RECEIVE,

  /**
   * Fires when a gRPC client call starts ({@code io.grpc.internal.ClientCallImpl.start}), once per
   * call, for blocking, future and async stubs and any transport. The target is the full method
   * name ({@code package.Service/Method}). A reject throws {@code StatusRuntimeException} with
   * status {@code UNAVAILABLE}.
   *
   * <p>Use with {@link ChaosSelector.RpcSelector}.
   */
  RPC_CALL,

  /**
   * Fires before a Redis command is written: Jedis {@code Connection.sendCommand(CommandArguments)}
   * (every Jedis command, pipelined or not) and Lettuce {@code RedisChannelHandler.dispatch}. The
   * target is the command name in upper case ({@code GET}, {@code SET}, ...). A reject throws
   * {@code JedisConnectionException} or Lettuce's {@code RedisCommandTimeoutException}.
   *
   * <p>Use with {@link ChaosSelector.RedisSelector}.
   */
  REDIS_COMMAND,

  /**
   * Fires when an R2DBC statement is executed ({@code io.r2dbc.spi.Statement.execute()}, in any
   * driver). The call is evaluated when the execution is requested (the publisher is created), not
   * when it is subscribed. A reject throws {@code R2dbcTransientResourceException}.
   *
   * <p>Use with {@link ChaosSelector.JdbcSelector}; the target pattern does not apply (R2DBC
   * statements do not expose their SQL).
   */
  R2DBC_STATEMENT_EXECUTE,

  /**
   * Fires when an AWS SDK for Java v2 client calls an operation, sync or async ({@code
   * BaseSyncClientHandler} / {@code BaseAsyncClientHandler.execute}), before signing and sending.
   * The service is taken from the request's package ({@code s3}, {@code dynamodb}, ...), the
   * operation from the SDK ({@code GetObject}). A reject throws {@code SdkClientException}.
   *
   * <p>Use with {@link ChaosSelector.AwsSdkSelector}.
   */
  AWS_SDK_CALL,

  /**
   * Fires before Netty flushes a channel's outbound buffer ({@code
   * AbstractChannel.AbstractUnsafe.flush0()}), for every transport including the native epoll and
   * kqueue ones that bypass {@code java.nio}. A reject throws {@link java.io.IOException}.
   *
   * <p>Use with {@link ChaosSelector.NettySelector}.
   */
  NETTY_CHANNEL_WRITE,

  /**
   * Fires before Netty reads from a channel: the NIO, epoll and kqueue stream-channel read loops. A
   * reject throws {@link java.io.IOException}.
   *
   * <p>Use with {@link ChaosSelector.NettySelector}.
   */
  NETTY_CHANNEL_READ,

  // ── Processes ─────────────────────────────────────────────────────────────────

  /**
   * Fires before a child process is started: {@link ProcessBuilder#start()}, {@code
   * ProcessBuilder.startPipeline} and {@link Runtime#exec(String[])}, which all reach the same
   * funnel. The target name is the program (the first element of the command). A reject throws
   * {@link java.io.IOException}; an {@code ErrnoEffect} throws the JDK's own failure, {@code
   * IOException: Cannot run program "x": error=N, reason}.
   *
   * <p>Use with {@link ChaosSelector.ProcessSelector}.
   */
  PROCESS_START,

  /**
   * Fires before a thread waits for a child process: {@code Process.waitFor()} and {@code
   * waitFor(long, TimeUnit)}. A delay makes the child seem slower; {@code ChaosEffect.timeout()} on
   * a timed wait waits it out and returns {@code false}, as for a child that hangs; a rejection
   * throws {@link InterruptedException}. The pattern matches the program's file name.
   *
   * <p>Use with {@link ChaosSelector.ProcessSelector}.
   */
  PROCESS_WAIT,

  // ── Agent lifecycle ─────────────────────────────────────────────────────────

  /**
   * Internal sentinel operation type used by the runtime to signal stressor lifecycle events. Not
   * set by callers — present in the runtime invocation context only when evaluating {@link
   * ChaosSelector.StressSelector} scenarios at activation time.
   */
  LIFECYCLE,
}

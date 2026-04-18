package com.macstab.chaos.api;

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
 *       #SYSTEM_CLOCK_NANOS}, {@link #INSTANT_NOW}, {@link #LOCAL_DATE_TIME_NOW}, {@link
 *       #ZONED_DATE_TIME_NOW}, {@link #DATE_NEW}, {@link #SYSTEM_GC_REQUEST}, {@link
 *       #SYSTEM_EXIT_REQUEST}, {@link #REFLECTION_INVOKE}, {@link #DIRECT_BUFFER_ALLOCATE}, {@link
 *       #OBJECT_DESERIALIZE}, {@link #OBJECT_SERIALIZE}, {@link #NATIVE_LIBRARY_LOAD}, {@link
 *       #JNDI_LOOKUP}, {@link #JMX_INVOKE}, {@link #JMX_GET_ATTR}, {@link #ZIP_INFLATE}, {@link
 *       #ZIP_DEFLATE}
 *   <li>{@link ChaosSelector.NioSelector} — {@link #NIO_SELECTOR_SELECT}, {@link
 *       #NIO_CHANNEL_READ}, {@link #NIO_CHANNEL_WRITE}, {@link #NIO_CHANNEL_CONNECT}, {@link
 *       #NIO_CHANNEL_ACCEPT}
 *   <li>{@link ChaosSelector.NetworkSelector} — {@link #SOCKET_CONNECT}, {@link #SOCKET_ACCEPT},
 *       {@link #SOCKET_READ}, {@link #SOCKET_WRITE}, {@link #SOCKET_CLOSE}
 *   <li>{@link ChaosSelector.ThreadLocalSelector} — {@link #THREAD_LOCAL_GET}, {@link
 *       #THREAD_LOCAL_SET}
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
   * latency or rejections to the execution phase, independent of submission.
   */
  EXECUTOR_WORKER_RUN,

  /**
   * Fires when {@link java.util.concurrent.ExecutorService#shutdown()} or {@code shutdownNow()} is
   * called. Chaos here can delay or prevent orderly shutdown of executor services.
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
   * streams.
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
   * can simulate a full queue by returning {@code false} or delaying the offer.
   */
  QUEUE_OFFER,

  /**
   * Fires before a blocking {@link java.util.concurrent.BlockingQueue#take} call. Chaos here can
   * add latency to consumers, simulating a slow or starved queue.
   */
  QUEUE_TAKE,

  /**
   * Fires before a non-blocking {@link java.util.concurrent.BlockingQueue#poll} call. Chaos here
   * can simulate an empty queue by returning {@code null} or introducing polling delays.
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
   * Chaos here can delay registration, reject hooks, or substitute a wrapped implementation to
   * observe shutdown sequencing.
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
   * Fires before the body of an instrumented method executes. Combined with {@link
   * ChaosEffect.ExceptionInjectionEffect} this injects any exception into any method in any library
   * without modifying its bytecode at the source level.
   *
   * <p>Used exclusively with {@link ChaosSelector.MethodSelector}.
   *
   * @see ChaosSelector.MethodSelector
   * @see ChaosEffect.ExceptionInjectionEffect
   */
  METHOD_ENTER,

  /**
   * Fires after the body of an instrumented method completes and the return value is available.
   * Combined with {@link ChaosEffect.ReturnValueCorruptionEffect} this replaces the return value
   * with a boundary, null, zero, or empty value.
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
   * effects.
   *
   * <p>Used exclusively with {@link ChaosSelector.MonitorSelector}.
   */
  MONITOR_ENTER,

  /**
   * Fires before a thread parks itself via {@link java.util.concurrent.locks.LockSupport#park}.
   * Chaos here can delay the park or skip it to simulate spurious wake-ups and expose threading
   * bugs in lock-based data structures.
   *
   * <p>Used exclusively with {@link ChaosSelector.MonitorSelector}.
   */
  THREAD_PARK,

  // ── JVM runtime services ────────────────────────────────────────────────────

  /**
   * Fires on every call to {@link System#currentTimeMillis()}. Used with {@link
   * ChaosEffect.ClockSkewEffect} to apply fixed, drifting, or frozen clock offsets and test
   * time-dependent logic.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  SYSTEM_CLOCK_MILLIS,

  /**
   * Fires on every call to {@link System#nanoTime()}. A backward skew intentionally violates the
   * monotonicity contract to expose assumptions in timing loops and profiling code.
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
   * management.
   *
   * <p>Used exclusively with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  SYSTEM_GC_REQUEST,

  /**
   * Fires when {@link System#exit(int)} or {@link Runtime#halt(int)} is called. Chaos here can
   * delay or suppress process exit, allowing assertions to run after code that calls {@code
   * System.exit} in error paths.
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
   * to simulate slow compression on the response path.
   *
   * <p>Use with {@link ChaosSelector.JvmRuntimeSelector}.
   */
  ZIP_DEFLATE,

  // ── Async completion ────────────────────────────────────────────────────────

  /**
   * Fires before {@link java.util.concurrent.CompletableFuture#cancel} is called. Chaos here can
   * suppress the cancellation (pretend it succeeded without cancelling) or delay it.
   *
   * <p>Use with {@link ChaosSelector.AsyncSelector}.
   */
  ASYNC_CANCEL,

  // ── ThreadLocal operations ───────────────────────────────────────────────────

  /**
   * Fires before {@link ThreadLocal#get()} returns a value. Chaos here can return null to simulate
   * missing request context (e.g., Spring {@code RequestContextHolder}, SLF4J MDC) or delay the
   * lookup.
   *
   * <p>Use with {@link ChaosSelector.ThreadLocalSelector}.
   */
  THREAD_LOCAL_GET,

  /**
   * Fires before {@link ThreadLocal#set(Object)} stores a value. Chaos here can suppress the set so
   * that subsequent gets return the old or null value.
   *
   * <p>Use with {@link ChaosSelector.ThreadLocalSelector}.
   */
  THREAD_LOCAL_SET,

  // ── Agent lifecycle ─────────────────────────────────────────────────────────

  /**
   * Internal sentinel operation type used by the runtime to signal stressor lifecycle events. Not
   * set by callers — present in the runtime invocation context only when evaluating {@link
   * ChaosSelector.StressSelector} scenarios at activation time.
   */
  LIFECYCLE,
}

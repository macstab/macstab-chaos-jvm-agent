package io.macstab.chaos.api;

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
 *       #ASYNC_COMPLETE_EXCEPTIONALLY}
 *   <li>{@link ChaosSelector.SchedulingSelector} — {@link #SCHEDULE_SUBMIT}, {@link #SCHEDULE_TICK}
 *   <li>{@link ChaosSelector.ShutdownSelector} — {@link #SHUTDOWN_HOOK_REGISTER}, {@link
 *       #EXECUTOR_SHUTDOWN}, {@link #EXECUTOR_AWAIT_TERMINATION}
 *   <li>{@link ChaosSelector.ClassLoadingSelector} — {@link #CLASS_LOAD}, {@link #CLASS_DEFINE},
 *       {@link #RESOURCE_LOAD}
 *   <li>{@link ChaosSelector.MethodSelector} — {@link #METHOD_ENTER}, {@link #METHOD_EXIT}
 *   <li>{@link ChaosSelector.MonitorSelector} — {@link #MONITOR_ENTER}, {@link #THREAD_PARK}
 *   <li>{@link ChaosSelector.JvmRuntimeSelector} — {@link #SYSTEM_CLOCK_MILLIS}, {@link
 *       #SYSTEM_CLOCK_NANOS}, {@link #SYSTEM_GC_REQUEST}, {@link #SYSTEM_EXIT_REQUEST}, {@link
 *       #REFLECTION_INVOKE}, {@link #DIRECT_BUFFER_ALLOCATE}, {@link #OBJECT_DESERIALIZE}
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

  // ── Agent lifecycle ─────────────────────────────────────────────────────────

  /**
   * Internal sentinel operation type used by the runtime to signal stressor lifecycle events. Not
   * set by callers — present in the runtime invocation context only when evaluating {@link
   * ChaosSelector.StressSelector} scenarios at activation time.
   */
  LIFECYCLE,
}

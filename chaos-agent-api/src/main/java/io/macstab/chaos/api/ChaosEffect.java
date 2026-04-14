package io.macstab.chaos.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;

/**
 * Describes the chaos behavior applied when a scenario matches an invocation.
 *
 * <p>Effects fall into two categories:
 *
 * <ul>
 *   <li><b>Interceptors</b> — modify, delay, or terminate an in-flight JVM operation. Used with
 *       operation-targeted selectors (Thread, Executor, Method, etc.).
 *   <li><b>Stressors</b> — active chaos that runs independently of any specific operation. Used
 *       exclusively with {@link ChaosSelector.StressSelector}.
 * </ul>
 *
 * <p>Valid selector ↔ effect combinations are enforced by the runtime validator at activation time,
 * not by the type system.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  // ── Interceptor effects ────────────────────────────────────────────────────
  @JsonSubTypes.Type(value = ChaosEffect.DelayEffect.class, name = "delay"),
  @JsonSubTypes.Type(value = ChaosEffect.GateEffect.class, name = "gate"),
  @JsonSubTypes.Type(value = ChaosEffect.RejectEffect.class, name = "reject"),
  @JsonSubTypes.Type(value = ChaosEffect.SuppressEffect.class, name = "suppress"),
  @JsonSubTypes.Type(
      value = ChaosEffect.ExceptionalCompletionEffect.class,
      name = "exceptionalCompletion"),
  @JsonSubTypes.Type(
      value = ChaosEffect.ExceptionInjectionEffect.class,
      name = "exceptionInjection"),
  @JsonSubTypes.Type(
      value = ChaosEffect.ReturnValueCorruptionEffect.class,
      name = "returnValueCorruption"),
  @JsonSubTypes.Type(value = ChaosEffect.ClockSkewEffect.class, name = "clockSkew"),

  // ── Stressor effects ───────────────────────────────────────────────────────
  @JsonSubTypes.Type(value = ChaosEffect.HeapPressureEffect.class, name = "heapPressure"),
  @JsonSubTypes.Type(value = ChaosEffect.KeepAliveEffect.class, name = "keepAlive"),
  @JsonSubTypes.Type(value = ChaosEffect.MetaspacePressureEffect.class, name = "metaspacePressure"),
  @JsonSubTypes.Type(
      value = ChaosEffect.DirectBufferPressureEffect.class,
      name = "directBufferPressure"),
  @JsonSubTypes.Type(value = ChaosEffect.GcPressureEffect.class, name = "gcPressure"),
  @JsonSubTypes.Type(value = ChaosEffect.FinalizerBacklogEffect.class, name = "finalizerBacklog"),
  @JsonSubTypes.Type(value = ChaosEffect.DeadlockEffect.class, name = "deadlock"),
  @JsonSubTypes.Type(value = ChaosEffect.ThreadLeakEffect.class, name = "threadLeak"),
  @JsonSubTypes.Type(value = ChaosEffect.ThreadLocalLeakEffect.class, name = "threadLocalLeak"),
  @JsonSubTypes.Type(value = ChaosEffect.MonitorContentionEffect.class, name = "monitorContention"),
})
public sealed interface ChaosEffect
    permits ChaosEffect.DelayEffect,
        ChaosEffect.GateEffect,
        ChaosEffect.RejectEffect,
        ChaosEffect.SuppressEffect,
        ChaosEffect.ExceptionalCompletionEffect,
        ChaosEffect.ExceptionInjectionEffect,
        ChaosEffect.ReturnValueCorruptionEffect,
        ChaosEffect.ClockSkewEffect,
        ChaosEffect.HeapPressureEffect,
        ChaosEffect.KeepAliveEffect,
        ChaosEffect.MetaspacePressureEffect,
        ChaosEffect.DirectBufferPressureEffect,
        ChaosEffect.GcPressureEffect,
        ChaosEffect.FinalizerBacklogEffect,
        ChaosEffect.DeadlockEffect,
        ChaosEffect.ThreadLeakEffect,
        ChaosEffect.ThreadLocalLeakEffect,
        ChaosEffect.MonitorContentionEffect {

  // ── Interceptor factory methods ────────────────────────────────────────────

  /**
   * Returns a deterministic delay effect where every matched operation is paused for exactly
   * {@code delay}.
   *
   * @param delay the fixed pause duration; must be non-negative
   */
  static DelayEffect delay(Duration delay) {
    return new DelayEffect(delay, delay);
  }

  /**
   * Returns a randomised delay effect where each matched operation is paused for a duration
   * uniformly sampled from [{@code minDelay}, {@code maxDelay}].
   *
   * @param minDelay lower bound (inclusive); must be non-negative
   * @param maxDelay upper bound (inclusive); must be &gt;= minDelay
   */
  static DelayEffect delay(Duration minDelay, Duration maxDelay) {
    return new DelayEffect(minDelay, maxDelay);
  }

  /**
   * Returns a gate effect that blocks the matched operation until
   * {@link ChaosActivationHandle#release()} is called or {@code maxBlock} elapses.
   *
   * @param maxBlock maximum time to block; {@code null} blocks indefinitely
   */
  static GateEffect gate(Duration maxBlock) {
    return new GateEffect(maxBlock);
  }

  /**
   * Returns a reject effect that throws an appropriate exception for the matched operation type
   * (e.g., {@link java.util.concurrent.RejectedExecutionException} for executor submissions).
   *
   * @param message the exception message; must be non-blank
   */
  static RejectEffect reject(String message) {
    return new RejectEffect(message);
  }

  /**
   * Returns a suppress effect that silently discards the matched operation. Callers receive
   * {@code null} or {@code false} depending on operation semantics.
   */
  static SuppressEffect suppress() {
    return new SuppressEffect();
  }

  /**
   * Returns an exceptional completion effect that completes a matched {@link
   * java.util.concurrent.CompletableFuture} with the specified failure kind before the normal
   * completion path executes. Only valid with {@link ChaosSelector.AsyncSelector}.
   *
   * @param failureKind the type of exception to inject
   * @param message     the exception message; must be non-blank
   */
  static ExceptionalCompletionEffect exceptionalCompletion(
      FailureKind failureKind, String message) {
    return new ExceptionalCompletionEffect(failureKind, message);
  }

  /**
   * Returns an exception injection effect that throws an instance of {@code exceptionClassName}
   * at the entry of matched methods, before the method body executes. Only valid with
   * {@link ChaosSelector.MethodSelector} and {@link OperationType#METHOD_ENTER}.
   *
   * <p>The exception is instantiated via reflection. Both checked exceptions and {@link Error}
   * subclasses are supported — the runtime uses {@code Unsafe.throwException} to bypass the
   * compiler's checked-exception enforcement.
   *
   * <p>With stack trace enabled (the default here), the exception carries a full stack trace.
   * Use {@link ExceptionInjectionEffect#ExceptionInjectionEffect(String, String, boolean)} directly
   * with {@code withStackTrace=false} to suppress it for lower-overhead fault injection.
   *
   * @param exceptionClassName binary class name of the exception type (e.g.,
   *                           {@code "java.io.IOException"}); must be a valid binary class name
   * @param message            the exception message passed to the constructor; must be non-blank
   */
  static ExceptionInjectionEffect injectException(String exceptionClassName, String message) {
    return new ExceptionInjectionEffect(exceptionClassName, message, true);
  }

  /**
   * Returns a return value corruption effect that replaces the actual return value of matched
   * methods on exit. Only valid with {@link ChaosSelector.MethodSelector} and
   * {@link OperationType#METHOD_EXIT}.
   *
   * <p>If the strategy is inapplicable to the actual return type (e.g., {@link
   * ReturnValueStrategy#EMPTY} on a primitive), the runtime falls back to {@link
   * ReturnValueStrategy#ZERO} and reports the substitution via the observability bus.
   *
   * @param strategy the substitution strategy to apply
   */
  static ReturnValueCorruptionEffect corruptReturnValue(ReturnValueStrategy strategy) {
    return new ReturnValueCorruptionEffect(strategy);
  }

  /**
   * Returns a clock skew effect that offsets or distorts the JVM clock as observed through
   * {@link System#currentTimeMillis()} and {@link System#nanoTime()}. Only valid with
   * {@link ChaosSelector.JvmRuntimeSelector}.
   *
   * <p>The skew is applied in the instrumentation advice layer and does not affect the OS clock or
   * other processes.
   *
   * @param skewAmount the clock offset to apply; positive = future, negative = past; must not be
   *                   zero
   * @param mode       how the skew evolves over time
   */
  static ClockSkewEffect skewClock(Duration skewAmount, ClockSkewMode mode) {
    return new ClockSkewEffect(skewAmount, mode);
  }

  // ── Stressor factory methods ───────────────────────────────────────────────

  /**
   * Returns a heap pressure stressor that allocates and retains {@code bytes} of heap memory in
   * chunks of {@code chunkSizeBytes}. The retained memory is released when the
   * {@link ChaosActivationHandle} is closed.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#HEAP}.
   *
   * @param bytes          total bytes to allocate and retain; must be &gt; 0
   * @param chunkSizeBytes size of each allocation chunk; must be &gt; 0
   */
  static HeapPressureEffect heapPressure(long bytes, int chunkSizeBytes) {
    return new HeapPressureEffect(bytes, chunkSizeBytes);
  }

  /**
   * Returns a keep-alive stressor that spawns a named thread which refuses to terminate,
   * simulating a thread that blocks JVM shutdown or holds references to resources.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#KEEPALIVE}.
   *
   * @param threadName name of the kept-alive thread; must be non-blank
   * @param daemon     {@code false} prevents JVM shutdown until the handle is closed
   * @param heartbeat  interval between keep-alive park cycles; must be positive
   */
  static KeepAliveEffect keepAlive(String threadName, boolean daemon, Duration heartbeat) {
    return new KeepAliveEffect(threadName, daemon, heartbeat);
  }

  /**
   * Returns a metaspace pressure stressor that generates and loads {@code generatedClassCount}
   * synthetic classes with {@code fieldsPerClass} fields each, retaining strong references to
   * prevent unloading. Simulates classloader leaks common in hot-deployment frameworks.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#METASPACE}.
   *
   * @param generatedClassCount number of synthetic classes to generate; must be &gt; 0
   * @param fieldsPerClass      static fields per class (controls per-class metaspace footprint);
   *                            must be &gt;= 0
   */
  static MetaspacePressureEffect metaspacePressure(int generatedClassCount, int fieldsPerClass) {
    return new MetaspacePressureEffect(generatedClassCount, fieldsPerClass, true);
  }

  /**
   * Returns a direct buffer pressure stressor that allocates off-heap {@link
   * java.nio.ByteBuffer#allocateDirect direct ByteBuffers} without registering a Cleaner,
   * simulating NIO/Netty buffer leaks. The buffers are intentionally not cleaned up until the JVM
   * exits.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#DIRECT_BUFFER}.
   *
   * @param totalBytes     total bytes of native memory to exhaust; must be &gt; 0
   * @param bufferSizeBytes size of each individual buffer allocation; must be &gt; 0 and
   *                        &lt;= totalBytes
   */
  static DirectBufferPressureEffect directBufferPressure(long totalBytes, int bufferSizeBytes) {
    return new DirectBufferPressureEffect(totalBytes, bufferSizeBytes, false);
  }

  /**
   * Returns a GC pressure stressor that sustains a target allocation rate to stress the garbage
   * collector. Allocated objects do not survive young-gen collections (survivorship is off by
   * default).
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#GC_PRESSURE}.
   *
   * @param allocationRateBytesPerSecond target allocation rate; must be &gt; 0
   * @param duration                     how long the stressor runs; must be positive
   */
  static GcPressureEffect gcPressure(long allocationRateBytesPerSecond, Duration duration) {
    return new GcPressureEffect(allocationRateBytesPerSecond, 1024, false, duration);
  }

  /**
   * Returns a finalizer backlog stressor that creates {@code objectCount} objects with slow
   * finalizers sleeping for {@code finalizerDelay} each. This backs up the finalizer thread queue,
   * delaying GC reclamation and eventually causing OOM conditions.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#FINALIZER_BACKLOG}.
   *
   * @param objectCount     number of objects with slow finalizers to create; must be &gt; 0
   * @param finalizerDelay  how long each finalizer sleeps; must be &gt;= 0
   */
  static FinalizerBacklogEffect finalizerBacklog(int objectCount, Duration finalizerDelay) {
    return new FinalizerBacklogEffect(objectCount, finalizerDelay);
  }

  /**
   * Returns a deadlock stressor that permanently deadlocks {@code participantCount} threads using
   * a 1-second acquisition delay between lock steps. The deadlock is released when the
   * {@link ChaosActivationHandle} is closed.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#DEADLOCK}.
   *
   * @param participantCount number of threads to deadlock; must be &gt;= 2
   */
  static DeadlockEffect deadlock(int participantCount) {
    return new DeadlockEffect(participantCount, Duration.ofSeconds(1));
  }

  /**
   * Returns a thread leak stressor that spawns {@code threadCount} threads that never terminate.
   * When {@code daemon=false} and the handle is not closed before JVM shutdown, the leaked threads
   * will prevent clean JVM exit.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#THREAD_LEAK}.
   *
   * @param threadCount number of threads to leak; must be &gt; 0
   * @param namePrefix  prefix for thread names (e.g., {@code "leaked-worker-"}); must be non-blank
   * @param daemon      if {@code false}, the threads block JVM exit until they terminate or the
   *                    handle is closed
   */
  static ThreadLeakEffect threadLeak(int threadCount, String namePrefix, boolean daemon) {
    return new ThreadLeakEffect(threadCount, namePrefix, daemon, null);
  }

  /**
   * Returns a ThreadLocal leak stressor that plants {@code entriesPerThread} ThreadLocal entries
   * of {@code valueSizeBytes} each in threads from the common pool, simulating large
   * request-scoped objects retained across requests in a pool thread.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#THREAD_LOCAL_LEAK}.
   *
   * @param entriesPerThread number of ThreadLocal entries per pool thread; must be &gt; 0
   * @param valueSizeBytes   size of each entry's byte-array value; must be &gt; 0
   */
  static ThreadLocalLeakEffect threadLocalLeak(int entriesPerThread, int valueSizeBytes) {
    return new ThreadLocalLeakEffect(entriesPerThread, valueSizeBytes);
  }

  /**
   * Returns a monitor contention stressor that spawns {@code contendingThreadCount} threads
   * competing for a single lock held for {@code lockHoldDuration} each cycle, using fair lock
   * ordering (FIFO). For a more destructive starvation scenario, use the full
   * {@link MonitorContentionEffect} constructor with {@code unfair=true}.
   *
   * <p>Use with {@link ChaosSelector.StressSelector} targeting
   * {@link ChaosSelector.StressTarget#MONITOR_CONTENTION}.
   *
   * @param lockHoldDuration     how long each thread holds the lock per cycle; must be positive
   * @param contendingThreadCount number of threads competing for the lock; must be &gt;= 2
   */
  static MonitorContentionEffect monitorContention(
      Duration lockHoldDuration, int contendingThreadCount) {
    return new MonitorContentionEffect(lockHoldDuration, contendingThreadCount, false);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Supporting enumerations
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Failure kind for {@link ExceptionalCompletionEffect}. Determines which exception type is used
   * to complete a CompletableFuture exceptionally.
   */
  enum FailureKind {
    /** {@link java.util.concurrent.RejectedExecutionException} */
    REJECTED,
    /** {@link java.util.concurrent.TimeoutException} */
    TIMEOUT,
    /** {@link IllegalStateException} */
    ILLEGAL_STATE,
    /** {@link ClassNotFoundException} wrapped in an IllegalStateException */
    CLASS_NOT_FOUND,
    /** {@link java.io.IOException} */
    IO,
    /** {@link InterruptedException} wrapped in an IllegalStateException */
    INTERRUPTED,
    /** {@link RuntimeException} */
    RUNTIME,
    /** {@link SecurityException} */
    SECURITY,
  }

  /**
   * Strategy for {@link ReturnValueCorruptionEffect}. Determines what value replaces the actual
   * return value on method exit.
   */
  enum ReturnValueStrategy {
    /**
     * Return null. Causes NullPointerException in callers that do not null-check. Applicable to
     * reference return types only.
     */
    NULL,
    /** Return zero or false. For numeric types: 0. For boolean: false. */
    ZERO,
    /**
     * Return an empty collection, Optional, or String. Applicable to Collection, Map, Optional, and
     * String return types.
     */
    EMPTY,
    /**
     * Return the maximum boundary value for the return type. For int: Integer.MAX_VALUE. For long:
     * Long.MAX_VALUE.
     */
    BOUNDARY_MAX,
    /**
     * Return the minimum boundary value for the return type. For int: Integer.MIN_VALUE. For long:
     * Long.MIN_VALUE.
     */
    BOUNDARY_MIN,
  }

  /** Mode for {@link ClockSkewEffect}. Controls how the skew evolves over time. */
  enum ClockSkewMode {
    /**
     * Apply a constant fixed offset to every clock read. System.currentTimeMillis() returns real +
     * skewAmount.toMillis().
     */
    FIXED,
    /**
     * Increase the skew monotonically with each read, simulating clock drift. Each call adds
     * skewAmount to the accumulated offset.
     */
    DRIFT,
    /**
     * Freeze the clock. Every call returns the same timestamp captured at activation. Simulates a
     * hung NTP daemon or failed clock source.
     */
    FREEZE,
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Interceptor effect types
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Delays the matched operation by a random duration in [minDelay, maxDelay]. If min equals max
   * the delay is deterministic.
   */
  record DelayEffect(Duration minDelay, Duration maxDelay) implements ChaosEffect {
    public DelayEffect {
      if (minDelay == null || maxDelay == null) {
        throw new IllegalArgumentException("delay bounds must be non-null");
      }
      if (minDelay.isNegative() || maxDelay.isNegative()) {
        throw new IllegalArgumentException("delay bounds must be >= 0");
      }
      if (maxDelay.compareTo(minDelay) < 0) {
        throw new IllegalArgumentException("maxDelay must be >= minDelay");
      }
    }
  }

  /**
   * Blocks the matched operation on a manual gate until the gate is opened or maxBlock elapses. A
   * null maxBlock blocks indefinitely.
   */
  record GateEffect(Duration maxBlock) implements ChaosEffect {
    public GateEffect {
      if (maxBlock != null && (maxBlock.isZero() || maxBlock.isNegative())) {
        throw new IllegalArgumentException("maxBlock must be positive when set");
      }
    }
  }

  /**
   * Rejects the matched operation by throwing an appropriate exception. The exception type is
   * inferred from the OperationType by the runtime.
   */
  record RejectEffect(String message) implements ChaosEffect {
    public RejectEffect {
      if (message == null || message.isBlank()) {
        throw new IllegalArgumentException("message must be non-blank");
      }
    }
  }

  /**
   * Suppresses the matched operation silently. Callers receive a null return or false boolean
   * depending on operation semantics.
   */
  record SuppressEffect() implements ChaosEffect {}

  /**
   * Completes a matched CompletableFuture exceptionally before normal completion runs. Only valid
   * with {@link ChaosSelector.AsyncSelector}.
   */
  record ExceptionalCompletionEffect(FailureKind failureKind, String message)
      implements ChaosEffect {
    public ExceptionalCompletionEffect {
      if (failureKind == null) {
        throw new IllegalArgumentException("failureKind must not be null");
      }
      if (message == null || message.isBlank()) {
        throw new IllegalArgumentException("message must be non-blank");
      }
    }
  }

  /**
   * Injects an exception at the entry of a matched method, before the method body executes. Only
   * valid with {@link ChaosSelector.MethodSelector} using {@link OperationType#METHOD_ENTER}.
   *
   * <p>The exception is instantiated via reflection using a (String) constructor when available, or
   * the no-arg constructor otherwise. Both checked exceptions and Error subclasses
   * (StackOverflowError, OutOfMemoryError) are supported — the runtime uses Unsafe.throwException
   * to bypass compiler checked-exception enforcement.
   *
   * <p>When withStackTrace is false the exception is constructed without a stack trace, which is
   * faster and not detectable by callers inspecting the stack trace.
   */
  record ExceptionInjectionEffect(String exceptionClassName, String message, boolean withStackTrace)
      implements ChaosEffect {
    public ExceptionInjectionEffect {
      if (exceptionClassName == null || exceptionClassName.isBlank()) {
        throw new IllegalArgumentException("exceptionClassName must be non-blank");
      }
      if (!isValidBinaryClassName(exceptionClassName)) {
        throw new IllegalArgumentException(
            "exceptionClassName is not a valid binary class name: " + exceptionClassName);
      }
      if (message == null || message.isBlank()) {
        throw new IllegalArgumentException("message must be non-blank");
      }
    }

    private static boolean isValidBinaryClassName(String name) {
      return name.matches("[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*");
    }
  }

  /**
   * Corrupts the return value of a matched method on exit. Only valid with {@link
   * ChaosSelector.MethodSelector} using {@link OperationType#METHOD_EXIT}.
   *
   * <p>If the chosen strategy is inapplicable to the actual return type (e.g., EMPTY on a
   * primitive), the runtime falls back to ZERO and reports the fallback via the observability bus.
   */
  record ReturnValueCorruptionEffect(ReturnValueStrategy strategy) implements ChaosEffect {
    public ReturnValueCorruptionEffect {
      if (strategy == null) {
        throw new IllegalArgumentException("strategy must not be null");
      }
    }
  }

  /**
   * Skews the JVM clock as observed through System.currentTimeMillis() and System.nanoTime(). Only
   * valid with {@link ChaosSelector.JvmRuntimeSelector}.
   *
   * <p>The skew is applied in the advice layer and does not affect the OS clock. A positive
   * skewAmount moves the clock forward; negative moves it backward. A backward skew on nanoTime
   * violates its monotonicity contract — this is intentional as it simulates a defective or
   * replaced clock source.
   */
  record ClockSkewEffect(Duration skewAmount, ClockSkewMode mode) implements ChaosEffect {
    public ClockSkewEffect {
      if (skewAmount == null) {
        throw new IllegalArgumentException("skewAmount must not be null");
      }
      if (skewAmount.isZero()) {
        throw new IllegalArgumentException("skewAmount must not be zero");
      }
      if (mode == null) {
        throw new IllegalArgumentException("mode must not be null");
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Stressor effect types
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Allocates and retains heap memory in chunks, simulating a memory leak or spike. Released when
   * the activation handle is closed.
   */
  record HeapPressureEffect(long bytes, int chunkSizeBytes) implements ChaosEffect {
    public HeapPressureEffect {
      if (bytes <= 0) {
        throw new IllegalArgumentException("bytes must be > 0");
      }
      if (chunkSizeBytes <= 0) {
        throw new IllegalArgumentException("chunkSizeBytes must be > 0");
      }
    }
  }

  /**
   * Keeps a named thread alive to simulate a thread that refuses to terminate. Released when the
   * activation handle is closed.
   */
  record KeepAliveEffect(String threadName, boolean daemon, Duration heartbeat)
      implements ChaosEffect {
    public KeepAliveEffect {
      if (threadName == null || threadName.isBlank()) {
        throw new IllegalArgumentException("threadName must be non-blank");
      }
      if (heartbeat == null || heartbeat.isZero() || heartbeat.isNegative()) {
        throw new IllegalArgumentException("heartbeat must be positive");
      }
    }
  }

  /**
   * Generates and loads synthetic classes to fill JVM Metaspace. Simulates classloader leaks and
   * metaspace exhaustion.
   *
   * <p>When retain is true, strong references to the generated classes are kept to prevent
   * unloading during GC — the realistic scenario for a classloader leak.
   */
  record MetaspacePressureEffect(int generatedClassCount, int fieldsPerClass, boolean retain)
      implements ChaosEffect {
    public MetaspacePressureEffect {
      if (generatedClassCount <= 0) {
        throw new IllegalArgumentException("generatedClassCount must be > 0");
      }
      if (fieldsPerClass < 0) {
        throw new IllegalArgumentException("fieldsPerClass must be >= 0");
      }
    }
  }

  /**
   * Allocates off-heap direct ByteBuffers to exhaust native direct memory. Simulates direct buffer
   * leaks common in NIO-heavy applications (Netty, Kafka clients).
   *
   * <p>When registerCleaner is false the buffers are intentionally leaked — no Cleaner is
   * registered. When true a Cleaner is registered but the reference is dropped, so the buffer is
   * cleaned only when GC runs.
   */
  record DirectBufferPressureEffect(long totalBytes, int bufferSizeBytes, boolean registerCleaner)
      implements ChaosEffect {
    public DirectBufferPressureEffect {
      if (totalBytes <= 0) {
        throw new IllegalArgumentException("totalBytes must be > 0");
      }
      if (bufferSizeBytes <= 0) {
        throw new IllegalArgumentException("bufferSizeBytes must be > 0");
      }
      if (bufferSizeBytes > totalBytes) {
        throw new IllegalArgumentException("bufferSizeBytes must be <= totalBytes");
      }
    }
  }

  /**
   * Sustains a target allocation rate to create GC pressure. Simulates high-throughput phases that
   * stress the garbage collector.
   *
   * <p>When promoteToOldGen is true, allocated objects are kept alive long enough to survive a
   * young-gen collection, forcing them into the old generation and triggering major or full GC
   * cycles.
   */
  record GcPressureEffect(
      long allocationRateBytesPerSecond,
      int objectSizeBytes,
      boolean promoteToOldGen,
      Duration duration)
      implements ChaosEffect {
    public GcPressureEffect {
      if (allocationRateBytesPerSecond <= 0) {
        throw new IllegalArgumentException("allocationRateBytesPerSecond must be > 0");
      }
      if (objectSizeBytes <= 0) {
        throw new IllegalArgumentException("objectSizeBytes must be > 0");
      }
      if (duration == null || duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException("duration must be positive");
      }
    }
  }

  /**
   * Creates objects with slow finalizers to back up the finalizer queue. Simulates finalizer
   * starvation, which delays GC reclamation and can cause OOM.
   *
   * <p>finalizerDelay is the duration each finalizer sleeps before completing. A long delay
   * combined with a high objectCount causes the finalizer thread to fall progressively further
   * behind.
   */
  record FinalizerBacklogEffect(int objectCount, Duration finalizerDelay) implements ChaosEffect {
    public FinalizerBacklogEffect {
      if (objectCount <= 0) {
        throw new IllegalArgumentException("objectCount must be > 0");
      }
      if (finalizerDelay == null || finalizerDelay.isNegative()) {
        throw new IllegalArgumentException("finalizerDelay must be >= 0");
      }
    }
  }

  /**
   * Creates a deadlock between participantCount threads by acquiring locks in conflicting orders.
   *
   * <p>The deadlock is permanent until the activation handle is closed, at which point
   * participating threads are interrupted and locks released. This tests whether health checks,
   * watchdogs, and deadlock detectors respond correctly.
   *
   * <p>acquisitionDelay is the pause each thread takes after acquiring its first lock before
   * attempting the second. A longer delay makes the deadlock harder to detect via thread dump
   * analysis alone.
   */
  record DeadlockEffect(int participantCount, Duration acquisitionDelay) implements ChaosEffect {
    public DeadlockEffect {
      if (participantCount < 2) {
        throw new IllegalArgumentException(
            "participantCount must be >= 2 — a deadlock requires at least two participants");
      }
      if (acquisitionDelay == null || acquisitionDelay.isNegative()) {
        throw new IllegalArgumentException("acquisitionDelay must be >= 0");
      }
    }
  }

  /**
   * Spawns threads that are intentionally never terminated.
   *
   * <p>When daemon is false and lifespan is null this effect prevents clean JVM shutdown. This is
   * valid for testing shutdown resilience but requires either a bounded ActivationPolicy or a
   * non-null lifespan in most test scenarios.
   *
   * <p>lifespan sets a maximum lifetime per leaked thread. Null means threads run until JVM exit or
   * the activation handle is closed.
   */
  record ThreadLeakEffect(int threadCount, String namePrefix, boolean daemon, Duration lifespan)
      implements ChaosEffect {
    public ThreadLeakEffect {
      if (threadCount <= 0) {
        throw new IllegalArgumentException("threadCount must be > 0");
      }
      if (namePrefix == null || namePrefix.isBlank()) {
        throw new IllegalArgumentException("namePrefix must be non-blank");
      }
    }
  }

  /**
   * Plants ThreadLocal entries in threads obtained from the common thread pool, simulating the most
   * common form of ThreadLocal memory leak in production: pool threads retaining large
   * request-scoped objects across requests.
   *
   * <p>Planted entries are removed when the activation handle is closed.
   */
  record ThreadLocalLeakEffect(int entriesPerThread, int valueSizeBytes) implements ChaosEffect {
    public ThreadLocalLeakEffect {
      if (entriesPerThread <= 0) {
        throw new IllegalArgumentException("entriesPerThread must be > 0");
      }
      if (valueSizeBytes <= 0) {
        throw new IllegalArgumentException("valueSizeBytes must be > 0");
      }
    }
  }

  /**
   * Creates high contention on a shared monitor by spawning threads that repeatedly acquire and
   * hold a single lock, simulating lock convoy conditions.
   *
   * <p>When unfair is true the underlying lock does not guarantee FIFO ordering, increasing the
   * likelihood of thread starvation — the more destructive and realistic scenario.
   */
  record MonitorContentionEffect(
      Duration lockHoldDuration, int contendingThreadCount, boolean unfair) implements ChaosEffect {
    public MonitorContentionEffect {
      if (lockHoldDuration == null || lockHoldDuration.isNegative() || lockHoldDuration.isZero()) {
        throw new IllegalArgumentException("lockHoldDuration must be positive");
      }
      if (contendingThreadCount < 2) {
        throw new IllegalArgumentException("contendingThreadCount must be >= 2");
      }
    }
  }
}

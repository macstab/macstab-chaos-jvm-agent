package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Validates that a {@link ChaosScenario} is consistent with the current {@link FeatureSet} and with
 * internal invariants of the chaos model.
 *
 * <p>Called by {@code ChaosRuntime} during scenario registration before any {@link
 * ScenarioController} is created. Throws {@link com.macstab.chaos.jvm.api.ChaosValidationException}
 * on the first constraint violation found.
 *
 * <p>Constraints checked include:
 *
 * <ul>
 *   <li>Scope / operation compatibility (a session-scoped scenario must not select an operation
 *       that never runs in a session).
 *   <li>Stress-effect binding (each stress target takes its own effect type) and the direct memory
 *       limit.
 *   <li>Effect / operation support (an effect is refused where the real call cannot carry it out,
 *       for example a reject where no real call fails).
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is stateless; all methods are static and may be called concurrently.
 */
final class CompatibilityValidator {
  private CompatibilityValidator() {}

  /** Operations evaluated without a session: their calls carry none, whatever thread makes them. */
  private static final Set<OperationType> SESSIONLESS_OPS =
      EnumSet.of(
          OperationType.CLASS_LOAD,
          OperationType.CLASS_DEFINE,
          OperationType.RESOURCE_LOAD,
          OperationType.SHUTDOWN_HOOK_REGISTER);

  /**
   * Validates {@code scenario} for an activation in {@code activationScope}: first that its scope
   * fits there, then {@link #validate(ChaosScenario, FeatureSet)}. A session-scoped scenario is
   * refused outside a session, where it would apply to every thread, and a session accepts only
   * session-scoped scenarios. These refusals are {@link ChaosActivationException}s (where the
   * scenario is activated is wrong, not the scenario), the others {@link ChaosValidationException}
   * or {@link ChaosUnsupportedFeatureException}.
   *
   * @param scenario the scenario to validate
   * @param featureSet the runtime feature flags describing the active JVM capabilities
   * @param activationScope {@code JVM} for the control plane, {@code SESSION} for a session
   * @throws ChaosActivationException if the scenario's scope does not fit {@code activationScope}
   * @throws ChaosValidationException if any structural or semantic constraint is violated
   * @throws ChaosUnsupportedFeatureException if the scenario needs more than the runtime provides
   */
  static void validate(
      final ChaosScenario scenario,
      final FeatureSet featureSet,
      final ChaosScenario.ScenarioScope activationScope) {
    if (activationScope == ChaosScenario.ScenarioScope.SESSION) {
      if (scenario.scope() != ChaosScenario.ScenarioScope.SESSION) {
        throw new ChaosActivationException(
            "session activation requires scenario scope SESSION for " + scenario.id());
      }
    } else if (scenario.scope() == ChaosScenario.ScenarioScope.SESSION) {
      throw new ChaosActivationException(
          "session-scoped scenario "
              + scenario.id()
              + " must be activated on a session (ChaosSession.activate); outside one it"
              + " would apply to every thread");
    }
    validate(scenario, featureSet);
  }

  /**
   * Validates {@code scenario} against {@code featureSet} and the internal chaos model invariants,
   * wherever it is activated.
   *
   * <p>Validation is fail-fast: the method throws on the first constraint violation it encounters.
   * The following checks are performed in order:
   *
   * <ol>
   *   <li><b>Session-scope exclusions</b> — {@link ChaosScenario.ScenarioScope#SESSION} scenarios
   *       may not select an operation that runs outside any session (class loading, class
   *       definition, resource loading and shutdown-hook registration).
   *   <li><b>Stress-scope constraint</b> — stress scenarios must use {@link
   *       ChaosScenario.ScenarioScope#JVM} scope.
   *   <li><b>Stress-effect binding</b> — each {@link ChaosSelector.StressTarget} value must be
   *       paired with its corresponding {@link ChaosEffect} subtype.
   *   <li><b>Direct memory limit</b> — direct-buffer pressure must fit under {@code
   *       -XX:MaxDirectMemorySize} ({@link ChaosUnsupportedFeatureException}).
   *   <li><b>Runtime flags</b> — a safepoint storm without retransforms under {@code
   *       -XX:+DisableExplicitGC}, a finalizer backlog under {@code --finalization=disabled} and
   *       carrier pinning without {@code allowDestructiveEffects} on a single-carrier scheduler
   *       produce no load ({@link ChaosUnsupportedFeatureException}).
   *   <li><b>Stressor limits</b> — heap pressure must fit in the free heap ({@link
   *       ChaosUnsupportedFeatureException}); interned strings must be distinct and within their
   *       caps; stressor intervals must be at least one millisecond.
   *   <li><b>Interceptor constraints</b> — effect/selector combinations must be semantically
   *       compatible (e.g. {@code ExceptionInjectionEffect} requires {@code MethodSelector} with
   *       {@code METHOD_ENTER} only; a reject only where the real call can fail).
   * </ol>
   *
   * @param scenario the scenario to validate; must not be {@code null}
   * @param featureSet the runtime feature flags describing the active JVM capabilities; must not be
   *     {@code null}
   * @throws com.macstab.chaos.jvm.api.ChaosValidationException if any structural or semantic
   *     constraint is violated
   * @throws com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException if the scenario needs more
   *     than the runtime provides (direct-buffer pressure above the direct memory limit, heap
   *     pressure above the free heap, a stressor this JVM's flags leave without load)
   */
  static void validate(final ChaosScenario scenario, final FeatureSet featureSet) {
    if (scenario.scope() == ChaosScenario.ScenarioScope.SESSION) {
      for (final OperationType operation : SelectorOperations.of(scenario.selector())) {
        if (SESSIONLESS_OPS.contains(operation)) {
          throw new ChaosValidationException(
              operation
                  + " never runs in a session (the JVM loads classes and registers shutdown"
                  + " hooks for every thread), so a session-scoped scenario could never apply;"
                  + " scope it JVM");
        }
      }
    }
    if (scenario.selector() instanceof ChaosSelector.StressSelector stressSelector) {
      if (scenario.scope() != ChaosScenario.ScenarioScope.JVM) {
        throw new ChaosValidationException("stress scenarios must be JVM scoped");
      }
      validateStressBinding(stressSelector.target(), scenario.effect());
      validateStressActivationPolicy(scenario.activationPolicy());
      validateDirectMemoryLimit(scenario.effect(), featureSet);
      validateRuntimeFlags(scenario.effect(), featureSet);
      validateCarrierPinning(scenario);
      validateStressorLimits(scenario.effect());
    }
    if (scenario.effect() instanceof ChaosEffect.ClockSkewEffect) {
      validateClockSkewActivationPolicy(scenario.activationPolicy());
    }
    validateInterceptorConstraints(scenario);
  }

  /**
   * Refuses direct-buffer pressure above the JVM's direct memory limit: the allocation could only
   * fail. An unknown limit allows it. Memory already reserved by the application makes a size under
   * the limit no guarantee; the stressor frees what it took when it runs out.
   */
  private static void validateDirectMemoryLimit(
      final ChaosEffect effect, final FeatureSet featureSet) {
    if (effect instanceof ChaosEffect.DirectBufferPressureEffect direct) {
      final long limit = featureSet.directMemoryLimit();
      if (limit != FeatureSet.UNKNOWN_LIMIT && direct.totalBytes() > limit) {
        throw new ChaosUnsupportedFeatureException(
            "direct-buffer pressure of "
                + direct.totalBytes()
                + " bytes exceeds the JVM's direct memory limit of "
                + limit
                + " bytes (-XX:MaxDirectMemorySize)");
      }
    }
  }

  /**
   * Refuses a stressor that this JVM's flags leave without any load (U-36, L17), each refusal a
   * {@link ChaosUnsupportedFeatureException}:
   *
   * <ul>
   *   <li>a safepoint storm without class retransforms ({@code retransformClassCount = 0}) under
   *       {@code -XX:+DisableExplicitGC}: {@code System.gc()} does nothing, so no safepoint is
   *       reached (retransforms reach one under every flag);
   *   <li>a finalizer backlog under {@code --finalization=disabled}: the JVM runs no finalizer.
   * </ul>
   *
   * <p>Not refused, because the load is real though smaller: a reference-queue flood under {@code
   * -XX:+DisableExplicitGC} (only the collections the application causes enqueue its references),
   * and every stressor under ZGC, Shenandoah or {@code -XX:+ExplicitGCInvokesConcurrent} (shorter
   * pauses). The flags are read only for the stressors in the table, at their first validation.
   */
  private static void validateRuntimeFlags(final ChaosEffect effect, final FeatureSet featureSet) {
    if (effect instanceof ChaosEffect.SafepointStormEffect storm
        && storm.retransformClassCount() == 0
        && !featureSet.explicitGc()) {
      throw new ChaosUnsupportedFeatureException(
          "a safepoint storm without class retransforms (retransformClassCount=0) reaches no"
              + " safepoint under -XX:+DisableExplicitGC, where System.gc() does nothing; set"
              + " retransformClassCount above 0");
    }
    if (effect instanceof ChaosEffect.FinalizerBacklogEffect && !featureSet.finalization()) {
      throw new ChaosUnsupportedFeatureException(
          "a finalizer backlog cannot build up under --finalization=disabled, where the JVM runs"
              + " no finalizer");
    }
  }

  /**
   * Refuses carrier pinning without {@code allowDestructiveEffects} on a virtual-thread scheduler
   * with a single carrier ({@code -Djdk.virtualThreadScheduler.parallelism=1}, a one-CPU
   * container): the stressor must leave one carrier free (L18), so it would hold none (L17, {@link
   * ChaosUnsupportedFeatureException}).
   */
  private static void validateCarrierPinning(final ChaosScenario scenario) {
    if (scenario.effect() instanceof ChaosEffect.VirtualThreadCarrierPinningEffect
        && !scenario.activationPolicy().allowDestructiveEffects()
        && VirtualThreadCarrierPinningStressor.schedulerParallelism() < 2) {
      throw new ChaosUnsupportedFeatureException(
          "virtual-thread carrier pinning holds no carrier on a scheduler with a single carrier"
              + " (jdk.virtualThreadScheduler.parallelism or the processor count is 1): it leaves"
              + " one carrier free unless allowDestructiveEffects is set");
    }
  }

  /**
   * Refuses stressor parameters a stressor cannot honour, instead of clamping them: heap pressure,
   * a thread-local leak or a reference-queue flood cycle above the free heap (it depends on the
   * runtime's state, {@link ChaosUnsupportedFeatureException}), a thread-local leak whose entries
   * overflow an {@code int}, more interned strings than their length can make distinct or than the
   * caps allow, and an interval below the millisecond a stressor sleeps in.
   */
  private static void validateStressorLimits(final ChaosEffect effect) {
    switch (effect) {
      case ChaosEffect.HeapPressureEffect heap ->
          validateHeapLimit("heap pressure of " + heap.bytes() + " bytes exceeds", heap.bytes());
      case ChaosEffect.StringInternPressureEffect intern -> validateStringIntern(intern);
      case ChaosEffect.ReferenceQueueFloodEffect flood -> {
        requireMillisecond("floodInterval", flood.floodInterval());
        validateHeapLimit(
            "referenceQueueFlood of "
                + flood.referenceCount()
                + " references per cycle needs about "
                + saturatedProduct(flood.referenceCount(), REFERENCE_BYTES)
                + " bytes, which exceeds",
            saturatedProduct(flood.referenceCount(), REFERENCE_BYTES));
      }
      case ChaosEffect.ThreadLocalLeakEffect leak -> validateThreadLocalLeak(leak);
      case ChaosEffect.SafepointStormEffect storm ->
          requireMillisecond("gcInterval", storm.gcInterval());
      case ChaosEffect.KeepAliveEffect keepAlive ->
          requireMillisecond("heartbeat", keepAlive.heartbeat());
      default -> {
        // no limit beyond the effect's own
      }
    }
  }

  /**
   * What one reference of a flood cycle keeps reachable: the {@code WeakReference} and its slot in
   * the cycle's anchor list, rounded up. Its 1 KiB referent is only weakly reachable, so a
   * collection reclaims it during the cycle.
   */
  static final long REFERENCE_BYTES = 64L;

  /**
   * Refuses a thread-local leak whose entries overflow an {@code int} (an invalid scenario) or
   * whose planted values exceed the free heap (the runtime cannot hold them): the common pool's
   * parallelism × {@code entriesPerThread} values of {@code valueSizeBytes} each.
   */
  private static void validateThreadLocalLeak(final ChaosEffect.ThreadLocalLeakEffect leak) {
    final long parallelism = java.util.concurrent.ForkJoinPool.commonPool().getParallelism();
    final long entries = parallelism * leak.entriesPerThread();
    if (entries > Integer.MAX_VALUE) {
      throw new ChaosValidationException(
          "threadLocalLeak entriesPerThread "
              + leak.entriesPerThread()
              + " times the common pool's parallelism of "
              + parallelism
              + " is "
              + entries
              + " entries, more than "
              + Integer.MAX_VALUE);
    }
    final long bytes = saturatedProduct(entries, leak.valueSizeBytes());
    validateHeapLimit(
        "threadLocalLeak of " + entries + " values of " + leak.valueSizeBytes() + " bytes exceeds",
        bytes);
  }

  /** {@code a × b}, saturated at {@link Long#MAX_VALUE}, for non-negative factors. */
  private static long saturatedProduct(final long a, final long b) {
    try {
      return Math.multiplyExact(a, b);
    } catch (final ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  /**
   * Refuses a stressor that retains more than the heap left free: the maximum heap less what is in
   * use now. A snapshot: the application allocating meanwhile can still make the stressor run out,
   * which fails its start.
   *
   * @param what the refusal's start, naming the stressor and its size, ending in "exceeds"
   * @param bytes what the stressor retains
   */
  private static void validateHeapLimit(final String what, final long bytes) {
    final Runtime jvm = Runtime.getRuntime();
    final long max = jvm.maxMemory();
    if (max == Long.MAX_VALUE) {
      return;
    }
    final long free = max - (jvm.totalMemory() - jvm.freeMemory());
    if (bytes > free) {
      throw new ChaosUnsupportedFeatureException(
          what
              + " the free heap of "
              + free
              + " bytes (the maximum heap, -Xmx, less what is in use)");
    }
  }

  private static void validateStringIntern(final ChaosEffect.StringInternPressureEffect intern) {
    if (intern.internCount() > StringInternPressureStressor.MAX_INTERN_COUNT) {
      throw new ChaosValidationException(
          "stringInternPressure internCount "
              + intern.internCount()
              + " exceeds the limit of "
              + StringInternPressureStressor.MAX_INTERN_COUNT
              + " strings");
    }
    if (intern.stringLengthBytes() > StringInternPressureStressor.MAX_STRING_LENGTH_BYTES) {
      throw new ChaosValidationException(
          "stringInternPressure stringLengthBytes "
              + intern.stringLengthBytes()
              + " exceeds the limit of "
              + StringInternPressureStressor.MAX_STRING_LENGTH_BYTES
              + " bytes");
    }
    // One Latin-1 character per byte: 256^length distinct strings (above 3 bytes, more than the
    // count cap).
    if (intern.stringLengthBytes() < 4) {
      final long distinct = 1L << (8 * intern.stringLengthBytes());
      if (intern.internCount() > distinct) {
        throw new ChaosValidationException(
            "stringInternPressure cannot make "
                + intern.internCount()
                + " distinct strings of "
                + intern.stringLengthBytes()
                + " bytes: there are 256^"
                + intern.stringLengthBytes()
                + " = "
                + distinct
                + "; raise stringLengthBytes");
      }
    }
  }

  /**
   * Refuses an interval below one millisecond: the stressor sleeps whole ones, so it would spin.
   */
  private static void requireMillisecond(final String field, final java.time.Duration interval) {
    if (interval.compareTo(java.time.Duration.ofMillis(1)) < 0) {
      throw new ChaosValidationException(
          field
              + " "
              + interval
              + " is below 1 ms; the stressor sleeps whole milliseconds and would spin");
    }
  }

  /**
   * Rejects activation-policy fields that cannot apply to a stressor. A stressor runs as background
   * load from {@code start()} until it is stopped or its {@code activeFor} window ends; it is never
   * evaluated per call, so per-call gating (probability, warm-up, rate limit, application cap)
   * never took effect. Rejecting them makes the misconfiguration visible instead of silent.
   */
  private static void validateStressActivationPolicy(final ActivationPolicy policy) {
    if (policy.maxApplications() != null
        || policy.rateLimit() != null
        || policy.activateAfterMatches() > 0
        || policy.probability() < 1.0d
        || policy.activeFrom() != null
        || policy.replayMatches() != null) {
      throw new ChaosValidationException(
          "stress scenarios run from start until stop, activeFor or activeUntil ends;"
              + " maxApplications, rateLimit, activateAfterMatches, probability, activeFrom and"
              + " replayMatches do not apply to them");
    }
  }

  /**
   * A clock skew is applied on every clock read while its time bounds hold; it is not a per-call
   * application, so the counting and sampling fields of the policy have no meaning for it and are
   * rejected rather than silently ignored.
   */
  private static void validateClockSkewActivationPolicy(final ActivationPolicy policy) {
    if (policy.maxApplications() != null
        || policy.rateLimit() != null
        || policy.activateAfterMatches() > 0
        || policy.probability() < 1.0d
        || policy.replayMatches() != null) {
      throw new ChaosValidationException(
          "a clock skew applies to every clock read while active (bounded by activeFor,"
              + " activeFrom and activeUntil); maxApplications, rateLimit, activateAfterMatches,"
              + " probability and replayMatches do not apply to it");
    }
  }

  /**
   * Verifies that the {@link ChaosSelector.StressTarget} is paired with its required {@link
   * ChaosEffect} subtype.
   *
   * <p>Each stress target has exactly one valid effect class; any other pairing is a configuration
   * error and is rejected immediately.
   *
   * @param target the stress target declared in the scenario's {@link ChaosSelector.StressSelector}
   * @param effect the effect declared in the scenario
   * @throws com.macstab.chaos.jvm.api.ChaosValidationException if {@code effect} is not the
   *     required type for {@code target}
   */
  private static void validateStressBinding(
      final ChaosSelector.StressTarget target, final ChaosEffect effect) {
    switch (target) {
      case HEAP -> {
        if (!(effect instanceof ChaosEffect.HeapPressureEffect)) {
          throw new ChaosValidationException("StressTarget.HEAP requires HeapPressureEffect");
        }
      }
      case METASPACE -> {
        if (!(effect instanceof ChaosEffect.MetaspacePressureEffect)) {
          throw new ChaosValidationException(
              "StressTarget.METASPACE requires MetaspacePressureEffect");
        }
      }
      case DIRECT_BUFFER -> {
        if (!(effect instanceof ChaosEffect.DirectBufferPressureEffect)) {
          throw new ChaosValidationException(
              "StressTarget.DIRECT_BUFFER requires DirectBufferPressureEffect");
        }
      }
      case GC_PRESSURE -> {
        if (!(effect instanceof ChaosEffect.GcPressureEffect)) {
          throw new ChaosValidationException("StressTarget.GC_PRESSURE requires GcPressureEffect");
        }
      }
      case FINALIZER_BACKLOG -> {
        if (!(effect instanceof ChaosEffect.FinalizerBacklogEffect)) {
          throw new ChaosValidationException(
              "StressTarget.FINALIZER_BACKLOG requires FinalizerBacklogEffect");
        }
      }
      case KEEPALIVE -> {
        if (!(effect instanceof ChaosEffect.KeepAliveEffect)) {
          throw new ChaosValidationException("StressTarget.KEEPALIVE requires KeepAliveEffect");
        }
      }
      case THREAD_LEAK -> {
        if (!(effect instanceof ChaosEffect.ThreadLeakEffect)) {
          throw new ChaosValidationException("StressTarget.THREAD_LEAK requires ThreadLeakEffect");
        }
      }
      // NOTE: DEADLOCK and THREAD_LEAK destructive guards are enforced in
      // validateInterceptorConstraints via allowDestructiveEffects flag.
      case THREAD_LOCAL_LEAK -> {
        if (!(effect instanceof ChaosEffect.ThreadLocalLeakEffect)) {
          throw new ChaosValidationException(
              "StressTarget.THREAD_LOCAL_LEAK requires ThreadLocalLeakEffect");
        }
      }
      case DEADLOCK -> {
        if (!(effect instanceof ChaosEffect.DeadlockEffect)) {
          throw new ChaosValidationException("StressTarget.DEADLOCK requires DeadlockEffect");
        }
      }
      case MONITOR_CONTENTION -> {
        if (!(effect instanceof ChaosEffect.MonitorContentionEffect)) {
          throw new ChaosValidationException(
              "StressTarget.MONITOR_CONTENTION requires MonitorContentionEffect");
        }
      }
      case CODE_CACHE_PRESSURE -> {
        if (!(effect instanceof ChaosEffect.CodeCachePressureEffect)) {
          throw new ChaosValidationException(
              "StressTarget.CODE_CACHE_PRESSURE requires CodeCachePressureEffect");
        }
      }
      case SAFEPOINT_STORM -> {
        if (!(effect instanceof ChaosEffect.SafepointStormEffect)) {
          throw new ChaosValidationException(
              "StressTarget.SAFEPOINT_STORM requires SafepointStormEffect");
        }
      }
      case STRING_INTERN_PRESSURE -> {
        if (!(effect instanceof ChaosEffect.StringInternPressureEffect)) {
          throw new ChaosValidationException(
              "StressTarget.STRING_INTERN_PRESSURE requires StringInternPressureEffect");
        }
      }
      case REFERENCE_QUEUE_FLOOD -> {
        if (!(effect instanceof ChaosEffect.ReferenceQueueFloodEffect)) {
          throw new ChaosValidationException(
              "StressTarget.REFERENCE_QUEUE_FLOOD requires ReferenceQueueFloodEffect");
        }
      }
      case VIRTUAL_THREAD_CARRIER_PINNING -> {
        // Without this case a user-supplied pairing like
        // (VIRTUAL_THREAD_CARRIER_PINNING, HeapPressureEffect) would slip through and fail
        // far downstream with a ClassCastException inside the stressor factory. The JDK-21
        // feature gate lives in validate(); this case exists to enforce the effect-type
        // contract so every StressTarget has a binding rule.
        if (!(effect instanceof ChaosEffect.VirtualThreadCarrierPinningEffect)) {
          throw new ChaosValidationException(
              "StressTarget.VIRTUAL_THREAD_CARRIER_PINNING requires"
                  + " VirtualThreadCarrierPinningEffect");
        }
      }
    }
  }

  /**
   * Validates that the effect/selector pairing in {@code scenario} satisfies all interceptor-level
   * semantic constraints.
   *
   * <p>Checks performed:
   *
   * <ul>
   *   <li>Stressor effects (heap, metaspace, GC, etc.) must use {@link
   *       ChaosSelector.StressSelector}.
   *   <li>{@link ChaosEffect.ExceptionalCompletionEffect} requires {@link
   *       ChaosSelector.AsyncSelector}.
   *   <li>{@link ChaosEffect.ExceptionInjectionEffect} requires {@link
   *       ChaosSelector.MethodSelector} with {@code METHOD_ENTER} operations only.
   *   <li>{@link ChaosEffect.ReturnValueCorruptionEffect} requires {@link
   *       ChaosSelector.MethodSelector} with {@code METHOD_EXIT} operations only.
   *   <li>{@link ChaosEffect.ClockSkewEffect} requires {@link ChaosSelector.JvmRuntimeSelector}.
   *   <li>{@link ChaosEffect.SpuriousWakeupEffect} requires {@link ChaosSelector.NioSelector} with
   *       {@code NIO_SELECTOR_SELECT} as its only operation.
   *   <li>{@link ChaosSelector.NioSelector} operations must all be NIO operation types.
   *   <li>{@link ChaosSelector.NetworkSelector} operations must all be socket operation types.
   *   <li>{@link ChaosSelector.ThreadLocalSelector} operations must be confined to {@code
   *       THREAD_LOCAL_GET} / {@code THREAD_LOCAL_SET}.
   * </ul>
   *
   * @param scenario the scenario whose effect and selector are cross-checked
   * @throws com.macstab.chaos.jvm.api.ChaosValidationException if any interceptor constraint is
   *     violated
   */
  private static void validateInterceptorConstraints(final ChaosScenario scenario) {
    final ChaosEffect effect = scenario.effect();
    final ChaosSelector selector = scenario.selector();

    validateDestructiveEffectGuard(effect, scenario);
    validateStressorEffectRequiresStressSelector(effect, selector);
    validateEffectSelectorAffinity(effect, selector);
    validateEffectOperationSupport(effect, selector);
    validateNetworkHostFilter(selector);
  }

  /**
   * Rejects {@link ChaosEffect.DeadlockEffect} and {@link ChaosEffect.ThreadLeakEffect} unless the
   * scenario explicitly opts in via {@link
   * com.macstab.chaos.jvm.api.ActivationPolicy#allowDestructiveEffects()}.
   */
  private static void validateDestructiveEffectGuard(
      final ChaosEffect effect, final ChaosScenario scenario) {
    if ((effect instanceof ChaosEffect.DeadlockEffect
            || effect instanceof ChaosEffect.ThreadLeakEffect)
        && !scenario.activationPolicy().allowDestructiveEffects()) {
      throw new ChaosValidationException(
          effect.getClass().getSimpleName()
              + " holds deadlocked or leaked threads, with their locks and memory, until the"
              + " scenario is stopped (leaked non-daemon threads also keep the JVM from exiting"
              + " until then). Activate only with ActivationPolicy.withDestructiveEffects() or"
              + " allowDestructiveEffects=true in your JSON plan.");
    }
  }

  /**
   * Rejects stressor effects (heap, metaspace, GC, etc.) when paired with any selector other than
   * {@link ChaosSelector.StressSelector}.
   */
  private static void validateStressorEffectRequiresStressSelector(
      final ChaosEffect effect, final ChaosSelector selector) {
    if (isStressorEffect(effect) && !(selector instanceof ChaosSelector.StressSelector)) {
      throw new ChaosValidationException(
          effect.getClass().getSimpleName() + " is a stressor effect and requires StressSelector");
    }
  }

  /**
   * The effects a stressor carries out; they run beside the application and need a {@link
   * ChaosSelector.StressSelector}.
   */
  static final Set<Class<? extends ChaosEffect>> STRESSOR_EFFECTS =
      Set.of(
          ChaosEffect.HeapPressureEffect.class,
          ChaosEffect.MetaspacePressureEffect.class,
          ChaosEffect.DirectBufferPressureEffect.class,
          ChaosEffect.GcPressureEffect.class,
          ChaosEffect.FinalizerBacklogEffect.class,
          ChaosEffect.KeepAliveEffect.class,
          ChaosEffect.ThreadLeakEffect.class,
          ChaosEffect.ThreadLocalLeakEffect.class,
          ChaosEffect.DeadlockEffect.class,
          ChaosEffect.MonitorContentionEffect.class,
          ChaosEffect.CodeCachePressureEffect.class,
          ChaosEffect.SafepointStormEffect.class,
          ChaosEffect.StringInternPressureEffect.class,
          ChaosEffect.ReferenceQueueFloodEffect.class,
          ChaosEffect.VirtualThreadCarrierPinningEffect.class);

  private static boolean isStressorEffect(final ChaosEffect effect) {
    return STRESSOR_EFFECTS.contains(effect.getClass());
  }

  /**
   * Validates effect-to-selector affinity rules: each effect type that requires a specific selector
   * kind is checked here, including operation-set sub-constraints for method and NIO effects.
   */
  private static void validateEffectSelectorAffinity(
      final ChaosEffect effect, final ChaosSelector selector) {
    if (effect instanceof ChaosEffect.ExceptionalCompletionEffect
        && !(selector instanceof ChaosSelector.AsyncSelector)) {
      throw new ChaosValidationException(
          "ExceptionalCompletionEffect is only valid with AsyncSelector");
    }
    validateExceptionInjectionSelector(effect, selector);
    validateReturnValueCorruptionSelector(effect, selector);
    if (effect instanceof ChaosEffect.ClockSkewEffect
        && !(selector instanceof ChaosSelector.JvmRuntimeSelector)) {
      throw new ChaosValidationException("ClockSkewEffect requires JvmRuntimeSelector");
    }
    validateSpuriousWakeupSelector(effect, selector);
  }

  private static void validateExceptionInjectionSelector(
      final ChaosEffect effect, final ChaosSelector selector) {
    if (!(effect instanceof ChaosEffect.ExceptionInjectionEffect injection)) {
      return;
    }
    // The class must be one the agent can throw: refused now, not when the method is called.
    FailureFactory.injectedConstructor(injection.exceptionClassName());
    if (!(selector instanceof ChaosSelector.MethodSelector methodSelector)) {
      throw new ChaosValidationException("ExceptionInjectionEffect requires MethodSelector");
    }
    // METHOD_EXIT is semantically wrong for injection: the method body has already run.
    if (methodSelector.operations().contains(OperationType.METHOD_EXIT)) {
      throw new ChaosValidationException(
          "ExceptionInjectionEffect requires METHOD_ENTER operations only;"
              + " found METHOD_EXIT in the selector");
    }
  }

  private static void validateReturnValueCorruptionSelector(
      final ChaosEffect effect, final ChaosSelector selector) {
    if (!(effect instanceof ChaosEffect.ReturnValueCorruptionEffect)) {
      return;
    }
    if (!(selector instanceof ChaosSelector.MethodSelector methodSelector)) {
      throw new ChaosValidationException("ReturnValueCorruptionEffect requires MethodSelector");
    }
    // METHOD_ENTER is wrong: the return value does not exist yet at entry.
    if (methodSelector.operations().contains(OperationType.METHOD_ENTER)) {
      throw new ChaosValidationException(
          "ReturnValueCorruptionEffect requires METHOD_EXIT operations only;"
              + " found METHOD_ENTER in the selector");
    }
  }

  private static void validateSpuriousWakeupSelector(
      final ChaosEffect effect, final ChaosSelector selector) {
    if (!(effect instanceof ChaosEffect.SpuriousWakeupEffect)) {
      return;
    }
    if (!(selector instanceof ChaosSelector.NioSelector nioSelector)) {
      throw new ChaosValidationException("SpuriousWakeupEffect requires NioSelector");
    }
    // A wakeup is a select() that returns 0 keys: on any other NIO call the same terminal would
    // skip the call instead.
    if (!nioSelector.operations().equals(Set.of(OperationType.NIO_SELECTOR_SELECT))) {
      throw new ChaosValidationException(
          "SpuriousWakeupEffect requires NioSelector with NIO_SELECTOR_SELECT as its only"
              + " operation, not "
              + nioSelector.operations());
    }
  }

  /**
   * Rejects a host filter on {@code SOCKET_ACCEPT}: the remote host of an accepted connection is
   * only known after {@code accept()} returns, so the pattern can never match before the call is
   * intercepted.
   */
  private static void validateNetworkHostFilter(final ChaosSelector selector) {
    if (selector instanceof ChaosSelector.NetworkSelector network
        && network.operations().contains(OperationType.SOCKET_ACCEPT)
        && ((network.remoteHostPattern() != null
                && network.remoteHostPattern().mode() != NamePattern.MatchMode.ANY)
            || network.remoteCidr() != null
            || network.remotePort() != null)) {
      throw new ChaosValidationException(
          "NetworkSelector remoteHostPattern, remoteCidr and remotePort cannot apply to"
              + " SOCKET_ACCEPT: the peer is unknown until accept() returns; use a separate"
              + " scenario without a peer filter for accepts");
    }
  }

  /** The operations each data-delivery effect applies to. */
  private static final java.util.Map<Class<? extends ChaosEffect>, Set<OperationType>>
      DELIVERY_EFFECT_OPS =
          java.util.Map.of(
              ChaosEffect.ConnectionResetEffect.class,
              EnumSet.of(OperationType.SOCKET_CLOSE),
              ChaosEffect.TrickleEffect.class,
              EnumSet.of(OperationType.SOCKET_READ),
              ChaosEffect.CorruptReadEffect.class,
              EnumSet.of(
                  OperationType.SOCKET_READ,
                  OperationType.FILE_IO_READ,
                  OperationType.DATAGRAM_RECEIVE),
              ChaosEffect.TimeoutEffect.class,
              EnumSet.of(
                  OperationType.SOCKET_READ,
                  OperationType.SOCKET_CONNECT,
                  OperationType.NIO_SELECTOR_SELECT,
                  OperationType.PROCESS_WAIT),
              ChaosEffect.TornWriteEffect.class,
              EnumSet.of(
                  OperationType.FILE_IO_WRITE,
                  OperationType.SOCKET_WRITE,
                  OperationType.NIO_CHANNEL_WRITE),
              ChaosEffect.DnsAnswerEffect.class,
              EnumSet.of(OperationType.DNS_ANSWER),
              ChaosEffect.DnsRewriteEffect.class,
              EnumSet.of(OperationType.DNS_RESOLVE),
              ChaosEffect.RedirectEffect.class,
              EnumSet.of(OperationType.SOCKET_CONNECT));

  /**
   * The operations each {@link ChaosEffect.Errno} applies to: the calls the kernel reports it for.
   * A network error applies to every socket call. A file error applies per call: a call on an open
   * descriptor (read, write, sync, truncate, map, close) never reports a missing file or a
   * permission, and a path call (open, delete, move) never reports a full disk on delete. A program
   * that cannot be started reports any file error, and {@code EAGAIN} or {@code ENOMEM} of {@code
   * fork}.
   */
  private static final java.util.Map<ChaosEffect.Errno, Set<OperationType>> ERRNO_OPS =
      new java.util.EnumMap<>(ChaosEffect.Errno.class);

  static {
    final Set<OperationType> socketCalls =
        EnumSet.of(
            OperationType.SOCKET_CONNECT,
            OperationType.SOCKET_ACCEPT,
            OperationType.SOCKET_READ,
            OperationType.SOCKET_WRITE,
            OperationType.SOCKET_CLOSE,
            OperationType.SOCKET_BIND,
            OperationType.SOCKET_SHUTDOWN,
            OperationType.DATAGRAM_SEND,
            OperationType.DATAGRAM_RECEIVE,
            OperationType.NIO_CHANNEL_CONNECT,
            OperationType.NIO_CHANNEL_ACCEPT,
            OperationType.NIO_CHANNEL_READ,
            OperationType.NIO_CHANNEL_WRITE);
    for (final ChaosEffect.Errno errno : ChaosEffect.Errno.values()) {
      if (errno.family() == ChaosEffect.Errno.Family.NETWORK) {
        ERRNO_OPS.put(errno, socketCalls);
      }
    }
    // connects and accepts open a descriptor
    final Set<OperationType> descriptorOpens =
        EnumSet.of(
            OperationType.FILE_IO_OPEN,
            OperationType.SOCKET_CONNECT,
            OperationType.SOCKET_ACCEPT,
            OperationType.NIO_CHANNEL_CONNECT,
            OperationType.NIO_CHANNEL_ACCEPT,
            OperationType.PROCESS_START);
    ERRNO_OPS.put(ChaosEffect.Errno.EMFILE, descriptorOpens);
    ERRNO_OPS.put(ChaosEffect.Errno.ENFILE, descriptorOpens);
    final Set<OperationType> noSpace =
        EnumSet.of(
            OperationType.FILE_IO_WRITE,
            OperationType.FILE_IO_SYNC,
            OperationType.FILE_IO_CLOSE,
            OperationType.FILE_IO_OPEN,
            OperationType.PROCESS_START);
    ERRNO_OPS.put(ChaosEffect.Errno.ENOSPC, noSpace);
    ERRNO_OPS.put(ChaosEffect.Errno.EDQUOT, noSpace);
    ERRNO_OPS.put(
        ChaosEffect.Errno.EROFS,
        EnumSet.of(
            OperationType.FILE_IO_OPEN,
            OperationType.FILE_IO_DELETE,
            OperationType.FILE_IO_MOVE,
            OperationType.FILE_IO_MOVE_TARGET,
            OperationType.PROCESS_START));
    ERRNO_OPS.put(
        ChaosEffect.Errno.ENOENT,
        EnumSet.of(
            OperationType.FILE_IO_OPEN,
            OperationType.FILE_IO_DELETE,
            OperationType.FILE_IO_MOVE,
            OperationType.FILE_IO_MOVE_TARGET,
            OperationType.PROCESS_START));
    ERRNO_OPS.put(
        ChaosEffect.Errno.EACCES,
        EnumSet.of(
            OperationType.FILE_IO_OPEN,
            OperationType.FILE_IO_DELETE,
            OperationType.FILE_IO_MOVE,
            OperationType.FILE_IO_MOVE_TARGET,
            // a mapping wider than the descriptor's open mode
            OperationType.FILE_IO_MAP,
            // binding a privileged port
            OperationType.SOCKET_BIND,
            OperationType.PROCESS_START));
    ERRNO_OPS.put(
        ChaosEffect.Errno.EIO,
        EnumSet.of(
            OperationType.FILE_IO_READ,
            OperationType.FILE_IO_WRITE,
            OperationType.FILE_IO_SYNC,
            OperationType.FILE_IO_TRUNCATE,
            OperationType.FILE_IO_CLOSE,
            OperationType.FILE_IO_DELETE,
            OperationType.FILE_IO_MOVE,
            OperationType.FILE_IO_MOVE_TARGET,
            OperationType.PROCESS_START));
    // pthread_create of a platform thread, fork of a process
    ERRNO_OPS.put(
        ChaosEffect.Errno.EAGAIN,
        EnumSet.of(OperationType.THREAD_START, OperationType.PROCESS_START));
    // and mmap of a file region
    ERRNO_OPS.put(
        ChaosEffect.Errno.ENOMEM,
        EnumSet.of(
            OperationType.THREAD_START, OperationType.FILE_IO_MAP, OperationType.PROCESS_START));
  }

  /**
   * Operations whose interception point can carry out a {@link ChaosEffect.SuppressEffect}: it
   * skips the call, substitutes a no-op or returns an empty result, or — where skipping cannot be
   * expressed (thread start, exit) — fails the call with the documented exception. Every other
   * interception point can only delay or throw, so SUPPRESS is rejected there instead of being
   * silently ignored.
   */
  private static final Set<OperationType> SUPPRESS_OPS =
      EnumSet.of(
          OperationType.THREAD_START,
          OperationType.VIRTUAL_THREAD_START,
          OperationType.THREAD_SLEEP,
          OperationType.EXECUTOR_SUBMIT,
          OperationType.SCHEDULE_SUBMIT,
          OperationType.SCHEDULE_TICK,
          OperationType.QUEUE_OFFER,
          OperationType.QUEUE_POLL,
          OperationType.ASYNC_COMPLETE,
          OperationType.ASYNC_COMPLETE_EXCEPTIONALLY,
          OperationType.ASYNC_CANCEL,
          OperationType.RESOURCE_LOAD,
          OperationType.SYSTEM_GC_REQUEST,
          OperationType.SYSTEM_EXIT_REQUEST,
          OperationType.NIO_SELECTOR_SELECT,
          OperationType.THREAD_LOCAL_GET,
          OperationType.THREAD_LOCAL_SET,
          // a dropped datagram: the send reports success, nothing goes on the wire
          OperationType.DATAGRAM_SEND);

  /**
   * HTTP client and JDBC operations, which took a suppress in 1.x that threw an agent exception no
   * real client throws (ADR 0005). A reject fails them with the client's own exception.
   */
  private static final Set<OperationType> REJECT_INSTEAD_OF_SUPPRESS_OPS =
      EnumSet.of(
          OperationType.HTTP_CLIENT_SEND,
          OperationType.HTTP_CLIENT_SEND_ASYNC,
          OperationType.JDBC_CONNECTION_ACQUIRE,
          OperationType.JDBC_STATEMENT_EXECUTE,
          OperationType.JDBC_PREPARED_STATEMENT,
          OperationType.JDBC_TRANSACTION_COMMIT,
          OperationType.JDBC_TRANSACTION_ROLLBACK);

  /** Clock reads: they only return a (possibly skewed) time and cannot be delayed or failed. */
  private static final Set<OperationType> CLOCK_READ_OPS =
      EnumSet.of(
          OperationType.INSTANT_NOW,
          OperationType.CLOCK_READ,
          OperationType.LOCAL_DATE_TIME_NOW,
          OperationType.ZONED_DATE_TIME_NOW,
          OperationType.DATE_NEW,
          OperationType.SYSTEM_CLOCK_MILLIS,
          OperationType.SYSTEM_CLOCK_NANOS);

  /**
   * Why a reject is refused on an operation, for each operation no real call of which fails that
   * way: the failure would be one the caller never sees from the real call (ADR 0005). Each reason
   * names what to use instead.
   */
  private static final java.util.Map<OperationType, String> REJECT_REFUSALS =
      new java.util.EnumMap<>(OperationType.class);

  static {
    // The read loop runs on the event loop outside the pipeline's exception handling: a throwable
    // there reaches the event loop, not the channel's handlers.
    REJECT_REFUSALS.put(
        OperationType.NETTY_CHANNEL_READ,
        "the read runs outside the pipeline's exception handling; delay or gate the read, or"
            + " reject NETTY_CHANNEL_WRITE, which fails through the pipeline");
    // A worker evaluates the run before the pool records the task's outcome: a throwable there
    // kills the worker and leaves the task's future incomplete for good.
    final String workerRun =
        "the pool cannot fail the task there (its future never completes and the worker dies);"
            + " delay or gate the run, or reject EXECUTOR_SUBMIT";
    REJECT_REFUSALS.put(OperationType.EXECUTOR_WORKER_RUN, workerRun);
    REJECT_REFUSALS.put(OperationType.FORK_JOIN_TASK_RUN, workerRun);
    // take() and poll() fail only by interruption: no consumer handles another exception, and a
    // thread pool's worker dies on it and is replaced, over and over.
    final String queueRead =
        "the JDK's take() and poll() do not fail that way (a pool worker dies on it); delay or"
            + " gate them, suppress QUEUE_POLL, or reject QUEUE_PUT or QUEUE_OFFER on a bounded"
            + " queue";
    REJECT_REFUSALS.put(OperationType.QUEUE_TAKE, queueRead);
    REJECT_REFUSALS.put(OperationType.QUEUE_POLL, queueRead);
    REJECT_REFUSALS.put(
        OperationType.THREAD_PARK, "LockSupport.park never fails; delay or gate the park");
    REJECT_REFUSALS.put(
        OperationType.MONITOR_ENTER,
        "entering a monitor never fails; delay or gate it, or use a monitorContention stressor");
    REJECT_REFUSALS.put(
        OperationType.SYSTEM_GC_REQUEST, "System.gc() never fails; suppress or delay the request");
    final String threadLocal = "ThreadLocal.get() and set() never fail; suppress or delay them";
    REJECT_REFUSALS.put(OperationType.THREAD_LOCAL_GET, threadLocal);
    REJECT_REFUSALS.put(OperationType.THREAD_LOCAL_SET, threadLocal);
    REJECT_REFUSALS.put(
        OperationType.ASYNC_CANCEL,
        "CompletableFuture.cancel never fails; suppress or delay it, or fail the future with"
            + " exceptionalCompletion");
    REJECT_REFUSALS.put(
        OperationType.ZIP_DEFLATE,
        "Deflater.deflate never fails; delay it, or reject ZIP_INFLATE, which fails with"
            + " DataFormatException");
    REJECT_REFUSALS.put(
        OperationType.SYSTEM_EXIT_REQUEST,
        "System.exit and Runtime.halt never fail; suppress the exit (it is refused with the"
            + " documented exception) or delay it");
    REJECT_REFUSALS.put(
        OperationType.EXECUTOR_SHUTDOWN,
        "ExecutorService.shutdown() and shutdownNow() never fail; delay or gate them");
  }

  /**
   * Operations a reject applies to: every operation whose real call can fail, so the reject raises
   * what the call raises ({@code FailureFactory}) or returns what a refused call returns. Clock
   * reads take only a skew, and the stressors' lifecycle is not selectable.
   */
  private static final Set<OperationType> REJECT_OPS = rejectOps();

  private static Set<OperationType> rejectOps() {
    final Set<OperationType> operations = EnumSet.complementOf(EnumSet.of(OperationType.LIFECYCLE));
    operations.removeAll(REJECT_REFUSALS.keySet());
    operations.removeAll(CLOCK_READ_OPS);
    return operations;
  }

  /**
   * Rejects effects the selected operations cannot carry out: any effect other than a clock skew on
   * clock reads and a clock skew on operations that are not clock reads, SUPPRESS on an operation
   * that does not honour it, and a reject where no real call fails. These combinations validated
   * before but had no effect, or one no real call has.
   */
  private static void validateEffectOperationSupport(
      final ChaosEffect effect, final ChaosSelector selector) {
    final Set<OperationType> operations = SelectorOperations.of(selector);
    validateClockReads(effect, operations);
    if (effect instanceof ChaosEffect.SuppressEffect) {
      for (final OperationType operation : operations) {
        if (REJECT_INSTEAD_OF_SUPPRESS_OPS.contains(operation)) {
          throw new ChaosValidationException(
              "SuppressEffect is not supported for "
                  + operation
                  + ": no real client skips the call without failing it; use reject, which fails"
                  + " it with the client's own exception (IOException for HTTP, SQLException for"
                  + " JDBC)");
        }
        if (!SUPPRESS_OPS.contains(operation)) {
          throw new ChaosValidationException(
              "SuppressEffect is not supported for "
                  + operation
                  + "; this call cannot be skipped (use "
                  + (REJECT_OPS.contains(operation) ? "delay or reject" : "delay")
                  + "). Operations supporting suppress: "
                  + SUPPRESS_OPS);
        }
      }
    }
    final Set<OperationType> deliveryOps = DELIVERY_EFFECT_OPS.get(effect.getClass());
    if (deliveryOps != null && !deliveryOps.containsAll(operations)) {
      throw new ChaosValidationException(
          effect.getClass().getSimpleName()
              + " applies only to "
              + deliveryOps
              + ", not "
              + operations);
    }
    if (effect instanceof ChaosEffect.ErrnoEffect errnoEffect) {
      final Set<OperationType> allowed = ERRNO_OPS.get(errnoEffect.errno());
      for (final OperationType operation : operations) {
        if (allowed == null || !allowed.contains(operation)) {
          throw new ChaosValidationException(
              errnoEffect.errno()
                  + " is a "
                  + errnoEffect.errno().family().name().toLowerCase(java.util.Locale.ROOT)
                  + " error and does not apply to "
                  + operation
                  + ", where the kernel never reports it; it applies to "
                  + allowed);
        }
      }
    }
    if (effect instanceof ChaosEffect.RejectEffect) {
      for (final OperationType operation : operations) {
        if (!REJECT_OPS.contains(operation)) {
          throw new ChaosValidationException(
              "RejectEffect is not supported for "
                  + operation
                  + ": "
                  + REJECT_REFUSALS.getOrDefault(operation, "no real call of it fails"));
        }
      }
      if (operations.contains(OperationType.RPC_CALL)) {
        validateGrpcFailingStream();
      }
    }
  }

  private static final String GRPC_FAILING_STREAM = "io.grpc.internal.FailingClientStream";

  /**
   * The {@code FailingClientStream} constructors the gRPC reject can build; {@code
   * ClientCallExtractor} in the instrumentation builds the stream against the same list, a copy the
   * core cannot share ({@code GrpcFailingStreamShapesTest} keeps the two equal).
   */
  private static final List<List<String>> GRPC_FAILING_STREAM_SHAPES =
      List.of(
          List.of("io.grpc.Status"),
          List.of("io.grpc.Status", "io.grpc.internal.ClientStreamListener$RpcProgress"),
          List.of("io.grpc.Status", "[Lio.grpc.ClientStreamTracer;"),
          List.of(
              "io.grpc.Status",
              "io.grpc.internal.ClientStreamListener$RpcProgress",
              "[Lio.grpc.ClientStreamTracer;"));

  /**
   * A gRPC reject fails the call through gRPC's own {@code FailingClientStream} (a throw where the
   * stream is created would panic the channel for a call started before its name resolved). The
   * class is looked up through the activating thread's context class loader, then the system class
   * loader (the loader of the call path is not known before a call): not visible, the reject is
   * accepted (a version without a known constructor then fails the call by a throw and is named in
   * the hook failures); visible without a known constructor, it is refused.
   */
  private static void validateGrpcFailingStream() {
    final Class<?> failing = visibleGrpcFailingStream();
    if (failing == null) {
      return;
    }
    try {
      for (final Constructor<?> constructor : failing.getConstructors()) {
        final List<String> parameters = new ArrayList<>();
        for (final Class<?> parameter : constructor.getParameterTypes()) {
          parameters.add(parameter.getName());
        }
        if (GRPC_FAILING_STREAM_SHAPES.contains(parameters)) {
          return;
        }
      }
    } catch (final LinkageError | SecurityException unreadable) {
      // Unreadable constructors: refused below, as no known one was found.
    }
    throw new ChaosValidationException(
        "RejectEffect is not supported for RPC_CALL with this gRPC version: the reject fails the"
            + " call through "
            + GRPC_FAILING_STREAM
            + ", and its constructors are none of "
            + GRPC_FAILING_STREAM_SHAPES
            + "; delay the call instead");
  }

  private static @Nullable Class<?> visibleGrpcFailingStream() {
    for (final ClassLoader loader :
        Arrays.asList(
            Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader())) {
      if (loader == null) {
        continue;
      }
      try {
        return Class.forName(GRPC_FAILING_STREAM, false, loader);
      } catch (final ClassNotFoundException | LinkageError notVisible) {
        // the next loader
      }
    }
    return null;
  }

  /**
   * Clock reads only return a (possibly skewed) time: they take a clock skew and nothing else, and
   * a clock skew applies to nothing else. Checked first, so no other rule recommends an effect a
   * clock read refuses.
   */
  private static void validateClockReads(
      final ChaosEffect effect, final Set<OperationType> operations) {
    if (effect instanceof ChaosEffect.ClockSkewEffect) {
      for (final OperationType operation : operations) {
        if (!CLOCK_READ_OPS.contains(operation)) {
          throw new ChaosValidationException(
              "ClockSkewEffect only applies to clock reads "
                  + CLOCK_READ_OPS
                  + ", not "
                  + operation);
        }
      }
    } else if (containsAny(operations, CLOCK_READ_OPS)) {
      throw new ChaosValidationException(
          "clock reads "
              + CLOCK_READ_OPS
              + " only support ClockSkewEffect; "
              + effect.getClass().getSimpleName()
              + " would never apply to them");
    }
  }

  private static boolean containsAny(
      final Set<OperationType> haystack, final Set<OperationType> needles) {
    for (final OperationType op : needles) {
      if (haystack.contains(op)) {
        return true;
      }
    }
    return false;
  }
}

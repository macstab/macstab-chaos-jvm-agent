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
import java.util.EnumSet;
import java.util.Set;

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
 *   <li>Scope / selector compatibility (e.g. session-scoped scenarios must not use selectors that
 *       are meaningless in a session context).
 *   <li>Stress-effect binding (e.g. {@code HEAP_PRESSURE} effect requires sufficient available
 *       heap).
 *   <li>Interceptor availability (e.g. virtual-thread selectors require JDK 21+).
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is stateless; all methods are static and may be called concurrently.
 */
final class CompatibilityValidator {
  private CompatibilityValidator() {}

  /**
   * Validates {@code scenario} against {@code featureSet} and the internal chaos model invariants.
   *
   * <p>Validation is fail-fast: the method throws on the first constraint violation it encounters.
   * The following checks are performed in order:
   *
   * <ol>
   *   <li><b>Session-scope exclusions</b> — {@link ChaosScenario.ScenarioScope#SESSION} scenarios
   *       may not use JVM-global selectors ({@code ThreadSelector}, {@code ShutdownSelector},
   *       {@code ClassLoadingSelector}, {@code StressSelector}).
   *   <li><b>Virtual-thread availability</b> — a {@code ThreadSelector} with {@link
   *       ChaosSelector.ThreadKind#VIRTUAL} requires JDK 21+ at runtime.
   *   <li><b>Stress-scope constraint</b> — stress scenarios must use {@link
   *       ChaosScenario.ScenarioScope#JVM} scope.
   *   <li><b>Stress-effect binding</b> — each {@link ChaosSelector.StressTarget} value must be
   *       paired with its corresponding {@link ChaosEffect} subtype.
   *   <li><b>Direct memory limit</b> — direct-buffer pressure must fit under {@code
   *       -XX:MaxDirectMemorySize} ({@link ChaosUnsupportedFeatureException}).
   *   <li><b>Interceptor constraints</b> — effect/selector combinations must be semantically
   *       compatible (e.g. {@code ExceptionInjectionEffect} requires {@code MethodSelector} with
   *       {@code METHOD_ENTER} only; {@code GateEffect} is not valid with {@code StressSelector}).
   * </ol>
   *
   * @param scenario the scenario to validate; must not be {@code null}
   * @param featureSet the runtime feature flags describing the active JVM capabilities; must not be
   *     {@code null}
   * @throws com.macstab.chaos.jvm.api.ChaosValidationException if any structural or semantic
   *     constraint is violated
   * @throws com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException if the scenario requires a
   *     JVM capability (e.g. virtual threads) that is not available at runtime
   */
  static void validate(final ChaosScenario scenario, final FeatureSet featureSet) {
    if (scenario.scope() == ChaosScenario.ScenarioScope.SESSION) {
      if (scenario.selector() instanceof ChaosSelector.ThreadSelector
          || scenario.selector() instanceof ChaosSelector.ShutdownSelector
          || scenario.selector() instanceof ChaosSelector.ClassLoadingSelector
          || scenario.selector() instanceof ChaosSelector.StressSelector) {
        throw new ChaosValidationException(
            "selector "
                + scenario.selector().getClass().getSimpleName()
                + " is JVM-global and cannot be session scoped");
      }
    }
    if (scenario.selector() instanceof ChaosSelector.StressSelector stressSelector) {
      if (scenario.scope() != ChaosScenario.ScenarioScope.JVM) {
        throw new ChaosValidationException("stress scenarios must be JVM scoped");
      }
      validateStressBinding(stressSelector.target(), scenario.effect());
      validateStressActivationPolicy(scenario.activationPolicy());
      validateDirectMemoryLimit(scenario.effect(), featureSet);
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
   *   <li>{@link ChaosEffect.GateEffect} is incompatible with {@link ChaosSelector.StressSelector}
   *       because stressors activate on lifecycle events, not per-invocation gates.
   *   <li>{@link ChaosEffect.ExceptionalCompletionEffect} requires {@link
   *       ChaosSelector.AsyncSelector}.
   *   <li>{@link ChaosEffect.ExceptionInjectionEffect} requires {@link
   *       ChaosSelector.MethodSelector} with {@code METHOD_ENTER} operations only.
   *   <li>{@link ChaosEffect.ReturnValueCorruptionEffect} requires {@link
   *       ChaosSelector.MethodSelector} with {@code METHOD_EXIT} operations only.
   *   <li>{@link ChaosEffect.ClockSkewEffect} requires {@link ChaosSelector.JvmRuntimeSelector}.
   *   <li>{@link ChaosEffect.SpuriousWakeupEffect} requires {@link ChaosSelector.NioSelector}
   *       containing {@code NIO_SELECTOR_SELECT}.
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
    validateGateEffectNotWithStressSelector(effect, selector);
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
      throw new ChaosActivationException(
          effect.getClass().getSimpleName()
              + " creates non-recoverable JVM state (deadlocked or permanently-parked threads "
              + "that cannot be terminated without JVM restart). "
              + "Activate only with ActivationPolicy.withDestructiveEffects() or "
              + "allowDestructiveEffects=true in your JSON plan.");
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
   * Rejects {@link ChaosEffect.GateEffect} paired with {@link ChaosSelector.StressSelector} because
   * stressors activate on lifecycle events, not per-invocation gates.
   */
  private static void validateGateEffectNotWithStressSelector(
      final ChaosEffect effect, final ChaosSelector selector) {
    if (effect instanceof ChaosEffect.GateEffect
        && selector instanceof ChaosSelector.StressSelector) {
      throw new ChaosValidationException(
          "GateEffect is not valid with StressSelector — stressors activate independently of the"
              + " gate mechanism");
    }
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
    if (!(effect instanceof ChaosEffect.ExceptionInjectionEffect)) {
      return;
    }
    if (!(selector instanceof ChaosSelector.MethodSelector)) {
      throw new ChaosValidationException("ExceptionInjectionEffect requires MethodSelector");
    }
    // METHOD_EXIT is semantically wrong for injection: the method body has already run.
    if (selector instanceof ChaosSelector.MethodSelector methodSelector
        && methodSelector.operations().contains(OperationType.METHOD_EXIT)) {
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
    if (!(selector instanceof ChaosSelector.MethodSelector)) {
      throw new ChaosValidationException("ReturnValueCorruptionEffect requires MethodSelector");
    }
    // METHOD_ENTER is wrong: the return value does not exist yet at entry.
    if (selector instanceof ChaosSelector.MethodSelector methodSelector
        && methodSelector.operations().contains(OperationType.METHOD_ENTER)) {
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
    if (!(selector instanceof ChaosSelector.NioSelector)) {
      throw new ChaosValidationException("SpuriousWakeupEffect requires NioSelector");
    }
    if (selector instanceof ChaosSelector.NioSelector nioSelector
        && !nioSelector.operations().contains(OperationType.NIO_SELECTOR_SELECT)) {
      throw new ChaosValidationException(
          "SpuriousWakeupEffect requires NioSelector with NIO_SELECTOR_SELECT operation");
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
   * Rejects effects the selected operations cannot carry out: SUPPRESS on an operation that does
   * not honour it, any effect other than a clock skew on clock reads, and a clock skew on
   * operations that are not clock reads. These combinations validated before but had no effect.
   */
  private static void validateEffectOperationSupport(
      final ChaosEffect effect, final ChaosSelector selector) {
    final Set<OperationType> operations = SelectorOperations.of(selector);
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
                  + "; this interception point can only delay or throw (use delay or reject)."
                  + " Operations supporting suppress: "
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
    if (effect instanceof ChaosEffect.RejectEffect
        && operations.contains(OperationType.NETTY_CHANNEL_READ)) {
      // The read loop runs on the event loop outside the pipeline's exception handling: a
      // throwable there reaches the event loop, not the channel's handlers.
      throw new ChaosValidationException(
          "RejectEffect is not supported for NETTY_CHANNEL_READ; delay or gate the read, or"
              + " reject NETTY_CHANNEL_WRITE, which fails through the pipeline");
    }
    if (effect instanceof ChaosEffect.RejectEffect
        && (operations.contains(OperationType.EXECUTOR_WORKER_RUN)
            || operations.contains(OperationType.FORK_JOIN_TASK_RUN))) {
      // A worker evaluates the run before the pool records the task's outcome: a throwable there
      // kills the worker and leaves the task's future incomplete for good.
      throw new ChaosValidationException(
          "RejectEffect is not supported for EXECUTOR_WORKER_RUN or FORK_JOIN_TASK_RUN: the pool"
              + " cannot fail the task there (its future never completes and the worker dies);"
              + " delay or gate the run, or reject EXECUTOR_SUBMIT");
    }
    if (effect instanceof ChaosEffect.RejectEffect
        && (operations.contains(OperationType.QUEUE_TAKE)
            || operations.contains(OperationType.QUEUE_POLL))) {
      // take() and poll() fail only by interruption: no consumer handles another exception, and a
      // thread pool's worker dies on it and is replaced, over and over.
      throw new ChaosValidationException(
          "RejectEffect is not supported for QUEUE_TAKE or QUEUE_POLL: the JDK's take() and poll()"
              + " do not fail that way (a pool worker dies on it); delay or gate them, suppress"
              + " QUEUE_POLL, or reject QUEUE_PUT or QUEUE_OFFER on a bounded queue");
    }
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

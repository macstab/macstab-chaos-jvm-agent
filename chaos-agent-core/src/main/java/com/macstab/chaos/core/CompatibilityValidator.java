package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosActivationException;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.api.ChaosValidationException;
import com.macstab.chaos.api.OperationType;
import java.util.Set;

/**
 * Validates that a {@link ChaosScenario} is consistent with the current {@link FeatureSet} and with
 * internal invariants of the chaos model.
 *
 * <p>Called by {@code ChaosRuntime} during scenario registration before any {@link
 * ScenarioController} is created. Throws {@link com.macstab.chaos.api.ChaosValidationException} on
 * the first constraint violation found.
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
   *   <li><b>Interceptor constraints</b> — effect/selector combinations must be semantically
   *       compatible (e.g. {@code ExceptionInjectionEffect} requires {@code MethodSelector} with
   *       {@code METHOD_ENTER} only; {@code GateEffect} is not valid with {@code StressSelector}).
   * </ol>
   *
   * @param scenario the scenario to validate; must not be {@code null}
   * @param featureSet the runtime feature flags describing the active JVM capabilities; must not be
   *     {@code null}
   * @throws com.macstab.chaos.api.ChaosValidationException if any structural or semantic constraint
   *     is violated
   * @throws com.macstab.chaos.api.ChaosUnsupportedFeatureException if the scenario requires a JVM
   *     capability (e.g. virtual threads) that is not available at runtime
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
    if (scenario.selector() instanceof ChaosSelector.ThreadSelector threadSelector
        && threadSelector.kind() == ChaosSelector.ThreadKind.VIRTUAL
        && !featureSet.supportsVirtualThreads()) {
      throw new ChaosUnsupportedFeatureException(
          "virtual-thread chaos requires JDK 21+ at runtime");
    }
    if (scenario.selector() instanceof ChaosSelector.StressSelector stressSelector) {
      if (scenario.scope() != ChaosScenario.ScenarioScope.JVM) {
        throw new ChaosValidationException("stress scenarios must be JVM scoped");
      }
      validateStressBinding(stressSelector.target(), scenario.effect());
      if (stressSelector.target() == ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING
          && !featureSet.supportsVirtualThreads()) {
        throw new ChaosUnsupportedFeatureException(
            "VIRTUAL_THREAD_CARRIER_PINNING requires JDK 21+ at runtime");
      }
    }
    validateInterceptorConstraints(scenario);
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
   * @throws com.macstab.chaos.api.ChaosValidationException if {@code effect} is not the required
   *     type for {@code target}
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
        // The enum-switch was previously non-exhaustive — a user-supplied pairing like
        // (VIRTUAL_THREAD_CARRIER_PINNING, HeapPressureEffect) slipped through and failed
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
   * @throws com.macstab.chaos.api.ChaosValidationException if any interceptor constraint is
   *     violated
   */
  private static void validateInterceptorConstraints(final ChaosScenario scenario) {
    final ChaosEffect effect = scenario.effect();
    final ChaosSelector selector = scenario.selector();

    // Destructive-effect guard: DeadlockEffect and ThreadLeakEffect create non-recoverable
    // JVM state (deadlocked/parked threads that cannot be interrupted). Require explicit
    // opt-in via ActivationPolicy.allowDestructiveEffects() to prevent accidental activation
    // in long-lived processes. Fail at registration time, not at effect application time.
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

    // Stressor effects require StressSelector — not valid with any interception selector.
    if (effect instanceof ChaosEffect.HeapPressureEffect
        || effect instanceof ChaosEffect.MetaspacePressureEffect
        || effect instanceof ChaosEffect.DirectBufferPressureEffect
        || effect instanceof ChaosEffect.GcPressureEffect
        || effect instanceof ChaosEffect.FinalizerBacklogEffect
        || effect instanceof ChaosEffect.KeepAliveEffect
        || effect instanceof ChaosEffect.ThreadLeakEffect
        || effect instanceof ChaosEffect.ThreadLocalLeakEffect
        || effect instanceof ChaosEffect.DeadlockEffect
        || effect instanceof ChaosEffect.MonitorContentionEffect
        || effect instanceof ChaosEffect.CodeCachePressureEffect
        || effect instanceof ChaosEffect.SafepointStormEffect
        || effect instanceof ChaosEffect.StringInternPressureEffect
        || effect instanceof ChaosEffect.ReferenceQueueFloodEffect) {
      if (!(selector instanceof ChaosSelector.StressSelector)) {
        throw new ChaosValidationException(
            effect.getClass().getSimpleName()
                + " is a stressor effect and requires StressSelector");
      }
    }

    // Task 8: GateEffect is not meaningful with StressSelector — stressors activate
    // immediately on start() and are not gated on a per-invocation basis.
    if (effect instanceof ChaosEffect.GateEffect
        && selector instanceof ChaosSelector.StressSelector) {
      throw new ChaosValidationException(
          "GateEffect is not valid with StressSelector — stressors activate independently of the"
              + " gate mechanism");
    }

    // ExceptionalCompletionEffect is CompletableFuture-specific.
    if (effect instanceof ChaosEffect.ExceptionalCompletionEffect
        && !(selector instanceof ChaosSelector.AsyncSelector)) {
      throw new ChaosValidationException(
          "ExceptionalCompletionEffect is only valid with AsyncSelector");
    }

    // ExceptionInjectionEffect instruments method entry — requires MethodSelector.
    if (effect instanceof ChaosEffect.ExceptionInjectionEffect
        && !(selector instanceof ChaosSelector.MethodSelector)) {
      throw new ChaosValidationException("ExceptionInjectionEffect requires MethodSelector");
    }

    // Task 9: ExceptionInjectionEffect fires at method entry; METHOD_EXIT is semantically
    // wrong (the method body has already run) and would never be evaluated.
    if (effect instanceof ChaosEffect.ExceptionInjectionEffect
        && selector instanceof ChaosSelector.MethodSelector methodSelector) {
      if (methodSelector.operations().contains(OperationType.METHOD_EXIT)) {
        throw new ChaosValidationException(
            "ExceptionInjectionEffect requires METHOD_ENTER operations only;"
                + " found METHOD_EXIT in the selector");
      }
    }

    // ReturnValueCorruptionEffect instruments method exit — requires MethodSelector.
    if (effect instanceof ChaosEffect.ReturnValueCorruptionEffect
        && !(selector instanceof ChaosSelector.MethodSelector)) {
      throw new ChaosValidationException("ReturnValueCorruptionEffect requires MethodSelector");
    }

    // Task 9: ReturnValueCorruptionEffect fires at method exit; METHOD_ENTER is wrong
    // because the return value does not yet exist at entry.
    if (effect instanceof ChaosEffect.ReturnValueCorruptionEffect
        && selector instanceof ChaosSelector.MethodSelector methodSelector) {
      if (methodSelector.operations().contains(OperationType.METHOD_ENTER)) {
        throw new ChaosValidationException(
            "ReturnValueCorruptionEffect requires METHOD_EXIT operations only;"
                + " found METHOD_ENTER in the selector");
      }
    }

    // ClockSkewEffect intercepts JVM clock calls — requires JvmRuntimeSelector.
    if (effect instanceof ChaosEffect.ClockSkewEffect
        && !(selector instanceof ChaosSelector.JvmRuntimeSelector)) {
      throw new ChaosValidationException("ClockSkewEffect requires JvmRuntimeSelector");
    }

    // SpuriousWakeupEffect simulates a Selector.select() returning 0 — requires NioSelector
    // with at least NIO_SELECTOR_SELECT in the operation set.
    if (effect instanceof ChaosEffect.SpuriousWakeupEffect) {
      if (!(selector instanceof ChaosSelector.NioSelector)) {
        throw new ChaosValidationException("SpuriousWakeupEffect requires NioSelector");
      }
      if (selector instanceof ChaosSelector.NioSelector nioSelector
          && !nioSelector.operations().contains(OperationType.NIO_SELECTOR_SELECT)) {
        throw new ChaosValidationException(
            "SpuriousWakeupEffect requires NioSelector with NIO_SELECTOR_SELECT operation");
      }
    }

    // NioSelector operations must be confined to NIO operation types.
    if (selector instanceof ChaosSelector.NioSelector nioSelector) {
      final Set<OperationType> validNioOps =
          Set.of(
              OperationType.NIO_SELECTOR_SELECT,
              OperationType.NIO_CHANNEL_READ,
              OperationType.NIO_CHANNEL_WRITE,
              OperationType.NIO_CHANNEL_CONNECT,
              OperationType.NIO_CHANNEL_ACCEPT);
      for (final OperationType op : nioSelector.operations()) {
        if (!validNioOps.contains(op)) {
          throw new ChaosValidationException(
              "NioSelector operation " + op + " is not a NIO operation; valid ops: " + validNioOps);
        }
      }
    }

    // NetworkSelector operations must be confined to socket operation types.
    if (selector instanceof ChaosSelector.NetworkSelector networkSelector) {
      final Set<OperationType> validNetworkOps =
          Set.of(
              OperationType.SOCKET_CONNECT,
              OperationType.SOCKET_ACCEPT,
              OperationType.SOCKET_READ,
              OperationType.SOCKET_WRITE,
              OperationType.SOCKET_CLOSE);
      for (final OperationType op : networkSelector.operations()) {
        if (!validNetworkOps.contains(op)) {
          throw new ChaosValidationException(
              "NetworkSelector operation "
                  + op
                  + " is not a socket operation; valid ops: "
                  + validNetworkOps);
        }
      }
    }

    // ThreadLocalSelector operations must be confined to THREAD_LOCAL_GET / THREAD_LOCAL_SET.
    if (selector instanceof ChaosSelector.ThreadLocalSelector threadLocalSelector) {
      final Set<OperationType> validThreadLocalOps =
          Set.of(OperationType.THREAD_LOCAL_GET, OperationType.THREAD_LOCAL_SET);
      for (final OperationType op : threadLocalSelector.operations()) {
        if (!validThreadLocalOps.contains(op)) {
          throw new ChaosValidationException(
              "ThreadLocalSelector operation "
                  + op
                  + " is not valid; valid ops: "
                  + validThreadLocalOps);
        }
      }
    }

    // HttpClientSelector operations must be confined to HTTP_CLIENT_SEND / HTTP_CLIENT_SEND_ASYNC.
    if (selector instanceof ChaosSelector.HttpClientSelector httpClientSelector) {
      final Set<OperationType> validHttpClientOps =
          Set.of(OperationType.HTTP_CLIENT_SEND, OperationType.HTTP_CLIENT_SEND_ASYNC);
      for (final OperationType op : httpClientSelector.operations()) {
        if (!validHttpClientOps.contains(op)) {
          throw new ChaosValidationException(
              "HttpClientSelector operation "
                  + op
                  + " is not valid; valid ops: "
                  + validHttpClientOps);
        }
      }
    }

    // HTTP_CLIENT_SEND / HTTP_CLIENT_SEND_ASYNC require HttpClientSelector.
    if (selector instanceof ChaosSelector.HttpClientSelector) {
      // handled above
    } else {
      final boolean hasHttpOp = selectorContainsAny(selector, HTTP_OPS);
      if (hasHttpOp) {
        throw new ChaosValidationException(
            "HTTP_CLIENT_SEND / HTTP_CLIENT_SEND_ASYNC operations require HttpClientSelector");
      }
    }

    // JdbcSelector operations must be confined to the JDBC operation set.
    if (selector instanceof ChaosSelector.JdbcSelector jdbcSelector) {
      for (final OperationType op : jdbcSelector.operations()) {
        if (!JDBC_OPS.contains(op)) {
          throw new ChaosValidationException(
              "JdbcSelector operation " + op + " is not valid; valid ops: " + JDBC_OPS);
        }
      }
    } else {
      final boolean hasJdbcOp = selectorContainsAny(selector, JDBC_OPS);
      if (hasJdbcOp) {
        throw new ChaosValidationException("JDBC_* operations require JdbcSelector");
      }
    }
  }

  private static final Set<OperationType> HTTP_OPS =
      Set.of(OperationType.HTTP_CLIENT_SEND, OperationType.HTTP_CLIENT_SEND_ASYNC);

  private static final Set<OperationType> JDBC_OPS =
      Set.of(
          OperationType.JDBC_CONNECTION_ACQUIRE,
          OperationType.JDBC_STATEMENT_EXECUTE,
          OperationType.JDBC_PREPARED_STATEMENT,
          OperationType.JDBC_TRANSACTION_COMMIT,
          OperationType.JDBC_TRANSACTION_ROLLBACK);

  private static boolean selectorContainsAny(
      final ChaosSelector selector, final Set<OperationType> needles) {
    return switch (selector) {
      case ChaosSelector.ThreadSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.ExecutorSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.QueueSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.AsyncSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.SchedulingSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.ShutdownSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.ClassLoadingSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.MethodSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.MonitorSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.JvmRuntimeSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.NioSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.NetworkSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.ThreadLocalSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.HttpClientSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.JdbcSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.DnsSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.SslSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.FileIoSelector s -> containsAny(s.operations(), needles);
      case ChaosSelector.StressSelector ignored -> false;
    };
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

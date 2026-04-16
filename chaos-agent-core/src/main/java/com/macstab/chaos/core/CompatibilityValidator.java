package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.api.ChaosValidationException;
import com.macstab.chaos.api.OperationType;
import java.util.Set;

final class CompatibilityValidator {
  private CompatibilityValidator() {}

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
    }
    validateInterceptorConstraints(scenario);
  }

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
    }
  }

  private static void validateInterceptorConstraints(final ChaosScenario scenario) {
    final ChaosEffect effect = scenario.effect();
    final ChaosSelector selector = scenario.selector();

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
  }
}

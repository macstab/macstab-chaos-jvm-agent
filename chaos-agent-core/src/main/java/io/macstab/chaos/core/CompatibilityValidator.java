package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.ChaosUnsupportedFeatureException;
import io.macstab.chaos.api.ChaosValidationException;
import io.macstab.chaos.api.OperationType;

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
        || effect instanceof ChaosEffect.MonitorContentionEffect) {
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
  }
}

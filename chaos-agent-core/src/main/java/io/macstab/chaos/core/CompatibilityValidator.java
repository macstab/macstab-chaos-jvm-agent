package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.ChaosUnsupportedFeatureException;
import io.macstab.chaos.api.ChaosValidationException;

final class CompatibilityValidator {
  private CompatibilityValidator() {}

  static void validate(ChaosScenario scenario, FeatureSet featureSet) {
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
      ChaosSelector.StressTarget target, ChaosEffect effect) {
    switch (target) {
      case HEAP -> {
        if (!(effect instanceof ChaosEffect.HeapPressureEffect)) {
          throw new ChaosValidationException(
              "StressTarget.HEAP requires HeapPressureEffect");
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
          throw new ChaosValidationException(
              "StressTarget.GC_PRESSURE requires GcPressureEffect");
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
          throw new ChaosValidationException(
              "StressTarget.KEEPALIVE requires KeepAliveEffect");
        }
      }
      case THREAD_LEAK -> {
        if (!(effect instanceof ChaosEffect.ThreadLeakEffect)) {
          throw new ChaosValidationException(
              "StressTarget.THREAD_LEAK requires ThreadLeakEffect");
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
          throw new ChaosValidationException(
              "StressTarget.DEADLOCK requires DeadlockEffect");
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

  private static void validateInterceptorConstraints(ChaosScenario scenario) {
    ChaosEffect effect = scenario.effect();
    ChaosSelector selector = scenario.selector();

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
            effect.getClass().getSimpleName() + " is a stressor effect and requires StressSelector");
      }
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
      throw new ChaosValidationException(
          "ExceptionInjectionEffect requires MethodSelector");
    }

    // ReturnValueCorruptionEffect instruments method exit — requires MethodSelector.
    if (effect instanceof ChaosEffect.ReturnValueCorruptionEffect
        && !(selector instanceof ChaosSelector.MethodSelector)) {
      throw new ChaosValidationException(
          "ReturnValueCorruptionEffect requires MethodSelector");
    }

    // ClockSkewEffect intercepts JVM clock calls — requires JvmRuntimeSelector.
    if (effect instanceof ChaosEffect.ClockSkewEffect
        && !(selector instanceof ChaosSelector.JvmRuntimeSelector)) {
      throw new ChaosValidationException(
          "ClockSkewEffect requires JvmRuntimeSelector");
    }
  }
}

package io.macstab.chaos.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.ChaosValidationException;
import io.macstab.chaos.api.NamePattern;
import io.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompatibilityValidatorTest {

  private final FeatureSet featureSet = new FeatureSet();

  // ── SESSION scope with global selectors throws ─────────────────────────────

  @Test
  void sessionScopeWithThreadSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(
                ChaosSelector.thread(
                    Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void sessionScopeWithShutdownSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void sessionScopeWithClassLoadingSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(
                ChaosSelector.classLoading(
                    Set.of(OperationType.CLASS_LOAD), NamePattern.any()))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void sessionScopeWithStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
            .effect(new ChaosEffect.HeapPressureEffect(1024L, 512))
            .activationPolicy(ActivationPolicy.always())
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── SESSION scope with allowed selectors succeeds ─────────────────────────

  @Test
  void sessionScopeWithExecutorSelectorSucceeds() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(10)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void sessionScopeWithQueueSelectorSucceeds() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(10)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── Virtual thread on JDK 25 succeeds ─────────────────────────────────────

  @Test
  void virtualThreadKindOnCurrentJvmSucceeds() {
    // JDK 25 has isVirtual(), so supportsVirtualThreads() == true
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.thread(
                    Set.of(OperationType.VIRTUAL_THREAD_START),
                    ChaosSelector.ThreadKind.VIRTUAL))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void jvmScopedVirtualThreadKindWithAnyThreadKindSucceeds() {
    // ANY kind does not require virtual-thread support check — succeeds on any JDK
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.thread(
                    Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── StressTarget ↔ effect bindings ────────────────────────────────────────

  @Test
  void heapTargetWithHeapEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.HEAP, new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void heapTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.HEAP, new ChaosEffect.KeepAliveEffect("t", true, Duration.ofSeconds(1)));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void metaspaceTargetWithMetaspaceEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.METASPACE, new ChaosEffect.MetaspacePressureEffect(5, 0, false));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void metaspaceTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.METASPACE, new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void directBufferTargetWithDirectBufferEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.DIRECT_BUFFER,
        new ChaosEffect.DirectBufferPressureEffect(1024L, 512, false));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void directBufferTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.DIRECT_BUFFER, new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void gcPressureTargetWithGcPressureEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.GC_PRESSURE,
        new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ofSeconds(5)));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void gcPressureTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.GC_PRESSURE, new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void finalizerBacklogTargetWithFinalizerBacklogEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.FINALIZER_BACKLOG,
        new ChaosEffect.FinalizerBacklogEffect(100, Duration.ofMillis(50)));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void finalizerBacklogTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.FINALIZER_BACKLOG,
        new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void keepAliveTargetWithKeepAliveEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.KEEPALIVE,
        new ChaosEffect.KeepAliveEffect("t", true, Duration.ofSeconds(1)));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void keepAliveTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.KEEPALIVE, new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void threadLeakTargetWithThreadLeakEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.THREAD_LEAK,
        new ChaosEffect.ThreadLeakEffect(2, "leak-", true, null));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void threadLeakTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.THREAD_LEAK, new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void threadLocalLeakTargetWithThreadLocalLeakEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.THREAD_LOCAL_LEAK,
        new ChaosEffect.ThreadLocalLeakEffect(5, 1024));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void threadLocalLeakTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.THREAD_LOCAL_LEAK,
        new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void deadlockTargetWithDeadlockEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.DEADLOCK,
        new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(100)));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void deadlockTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.DEADLOCK, new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void monitorContentionTargetWithMonitorContentionEffectSucceeds() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.MONITOR_CONTENTION,
        new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 2, false));
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void monitorContentionTargetWithWrongEffectThrows() {
    ChaosScenario scenario = stressScenario(
        ChaosSelector.StressTarget.MONITOR_CONTENTION,
        new ChaosEffect.HeapPressureEffect(1024L, 512));
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── Stressor effects with non-StressSelector throw ────────────────────────

  @Test
  void heapPressureEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.HeapPressureEffect(1024L, 512))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void metaspacePressureEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.MetaspacePressureEffect(5, 0, false))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void directBufferPressureEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.DirectBufferPressureEffect(1024L, 512, false))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void gcPressureEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(
                new ChaosEffect.GcPressureEffect(
                    1_000_000L, 1024, false, Duration.ofSeconds(5)))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void finalizerBacklogEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.FinalizerBacklogEffect(10, Duration.ofMillis(50)))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void keepAliveEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.KeepAliveEffect("t", true, Duration.ofSeconds(1)))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void threadLeakEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.ThreadLeakEffect(1, "leak-", true, null))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void threadLocalLeakEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.ThreadLocalLeakEffect(5, 1024))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void deadlockEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(100)))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void monitorContentionEffectWithNonStressSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 2, false))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── ExceptionalCompletion requires AsyncSelector ──────────────────────────

  @Test
  void exceptionalCompletionWithNonAsyncSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(
                new ChaosEffect.ExceptionalCompletionEffect(
                    ChaosEffect.FailureKind.TIMEOUT, "timeout"))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void exceptionalCompletionWithAsyncSelectorSucceeds() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)))
            .effect(
                new ChaosEffect.ExceptionalCompletionEffect(
                    ChaosEffect.FailureKind.IO, "io error"))
            .build();
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── ExceptionInjection requires MethodSelector ────────────────────────────

  @Test
  void exceptionInjectionWithNonMethodSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(
                new ChaosEffect.ExceptionInjectionEffect(
                    "java.io.IOException", "io error", true))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void exceptionInjectionWithMethodSelectorSucceeds() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.METHOD_ENTER),
                    NamePattern.exact("com.example.Dao"),
                    NamePattern.any()))
            .effect(
                new ChaosEffect.ExceptionInjectionEffect(
                    "java.io.IOException", "io error", true))
            .build();
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── ReturnValueCorruption requires MethodSelector ─────────────────────────

  @Test
  void returnValueCorruptionWithNonMethodSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(
                new ChaosEffect.ReturnValueCorruptionEffect(
                    ChaosEffect.ReturnValueStrategy.NULL))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void returnValueCorruptionWithMethodSelectorSucceeds() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.METHOD_EXIT),
                    NamePattern.exact("com.example.Service"),
                    NamePattern.any()))
            .effect(
                new ChaosEffect.ReturnValueCorruptionEffect(
                    ChaosEffect.ReturnValueStrategy.ZERO))
            .build();
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── ClockSkew requires JvmRuntimeSelector ─────────────────────────────────

  @Test
  void clockSkewWithNonJvmRuntimeSelectorThrows() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(
                new ChaosEffect.ClockSkewEffect(
                    Duration.ofSeconds(30), ChaosEffect.ClockSkewMode.FIXED))
            .build();
    assertThrows(
        ChaosValidationException.class,
        () -> CompatibilityValidator.validate(scenario, featureSet));
  }

  @Test
  void clockSkewWithJvmRuntimeSelectorSucceeds() {
    ChaosScenario scenario =
        ChaosScenario.builder("s")
            .selector(
                ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
            .effect(
                new ChaosEffect.ClockSkewEffect(
                    Duration.ofSeconds(30), ChaosEffect.ClockSkewMode.FIXED))
            .build();
    assertDoesNotThrow(() -> CompatibilityValidator.validate(scenario, featureSet));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static ChaosScenario stressScenario(
      ChaosSelector.StressTarget target, ChaosEffect effect) {
    return ChaosScenario.builder("stress-test")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.stress(target))
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}

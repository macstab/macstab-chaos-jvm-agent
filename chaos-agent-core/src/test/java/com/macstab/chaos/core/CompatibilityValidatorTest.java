package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationException;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.ChaosValidationException;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CompatibilityValidator")
class CompatibilityValidatorTest {

  private final FeatureSet featureSet = new FeatureSet();

  @Nested
  @DisplayName("SESSION scope with global selectors throws")
  class SessionScopeGlobalSelectors {

    @Test
    @DisplayName("ThreadSelector throws")
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
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("ShutdownSelector throws")
    void sessionScopeWithShutdownSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("ClassLoadingSelector throws")
    void sessionScopeWithClassLoadingSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(
                  ChaosSelector.classLoading(Set.of(OperationType.CLASS_LOAD), NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("StressSelector throws")
    void sessionScopeWithStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
              .effect(new ChaosEffect.HeapPressureEffect(1024L, 512))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }
  }

  @Nested
  @DisplayName("SESSION scope with allowed selectors succeeds")
  class SessionScopeAllowedSelectors {

    @Test
    @DisplayName("ExecutorSelector succeeds")
    void sessionScopeWithExecutorSelectorSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(10)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("QueueSelector succeeds")
    void sessionScopeWithQueueSelectorSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(10)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("Virtual thread on JDK 25 succeeds")
  class VirtualThreadSupport {

    @Test
    @DisplayName("VIRTUAL kind on current JVM succeeds")
    void virtualThreadKindOnCurrentJvmSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.thread(
                      Set.of(OperationType.VIRTUAL_THREAD_START), ChaosSelector.ThreadKind.VIRTUAL))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ANY kind succeeds on any JDK")
    void jvmScopedAnyThreadKindSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.thread(
                      Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("StressTarget ↔ effect bindings")
  class StressTargetEffectBindings {

    @Test
    @DisplayName("HEAP target with HeapPressureEffect succeeds")
    void heapTargetWithHeapEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.HEAP,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("HEAP target with wrong effect throws")
    void heapTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.HEAP,
                          new ChaosEffect.KeepAliveEffect("t", true, Duration.ofSeconds(1))),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("METASPACE target with MetaspacePressureEffect succeeds")
    void metaspaceTargetWithMetaspaceEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.METASPACE,
                          new ChaosEffect.MetaspacePressureEffect(5, 0, false)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("METASPACE target with wrong effect throws")
    void metaspaceTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.METASPACE,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("DIRECT_BUFFER target with DirectBufferPressureEffect succeeds")
    void directBufferTargetWithDirectBufferEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.DIRECT_BUFFER,
                          new ChaosEffect.DirectBufferPressureEffect(1024L, 512, false)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DIRECT_BUFFER target with wrong effect throws")
    void directBufferTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.DIRECT_BUFFER,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("GC_PRESSURE target with GcPressureEffect succeeds")
    void gcPressureTargetWithGcPressureEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.GC_PRESSURE,
                          new ChaosEffect.GcPressureEffect(
                              1_000_000L, 1024, false, Duration.ofSeconds(5))),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GC_PRESSURE target with wrong effect throws")
    void gcPressureTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.GC_PRESSURE,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("FINALIZER_BACKLOG target with FinalizerBacklogEffect succeeds")
    void finalizerBacklogTargetWithFinalizerBacklogEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.FINALIZER_BACKLOG,
                          new ChaosEffect.FinalizerBacklogEffect(100, Duration.ofMillis(50))),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FINALIZER_BACKLOG target with wrong effect throws")
    void finalizerBacklogTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.FINALIZER_BACKLOG,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("KEEPALIVE target with KeepAliveEffect succeeds")
    void keepAliveTargetWithKeepAliveEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.KEEPALIVE,
                          new ChaosEffect.KeepAliveEffect("t", true, Duration.ofSeconds(1))),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("KEEPALIVE target with wrong effect throws")
    void keepAliveTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.KEEPALIVE,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName(
        "THREAD_LEAK target with ThreadLeakEffect succeeds when allowDestructiveEffects=true")
    void threadLeakTargetWithThreadLeakEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      destructiveStressScenario(
                          ChaosSelector.StressTarget.THREAD_LEAK,
                          new ChaosEffect.ThreadLeakEffect(2, "leak-", true, null)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("THREAD_LEAK target with wrong effect throws")
    void threadLeakTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.THREAD_LEAK,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("THREAD_LOCAL_LEAK target with ThreadLocalLeakEffect succeeds")
    void threadLocalLeakTargetWithThreadLocalLeakEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.THREAD_LOCAL_LEAK,
                          new ChaosEffect.ThreadLocalLeakEffect(5, 1024)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("THREAD_LOCAL_LEAK target with wrong effect throws")
    void threadLocalLeakTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.THREAD_LOCAL_LEAK,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("DEADLOCK target with DeadlockEffect succeeds when allowDestructiveEffects=true")
    void deadlockTargetWithDeadlockEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      destructiveStressScenario(
                          ChaosSelector.StressTarget.DEADLOCK,
                          new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(100))),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DEADLOCK target with wrong effect throws")
    void deadlockTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.DEADLOCK,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("MONITOR_CONTENTION target with MonitorContentionEffect succeeds")
    void monitorContentionTargetWithMonitorContentionEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.MONITOR_CONTENTION,
                          new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 2, false)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MONITOR_CONTENTION target with wrong effect throws")
    void monitorContentionTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.MONITOR_CONTENTION,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("CODE_CACHE_PRESSURE target with CodeCachePressureEffect succeeds")
    void codeCachePressureTargetWithCodeCachePressureEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.CODE_CACHE_PRESSURE,
                          new ChaosEffect.CodeCachePressureEffect(10, 5)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CODE_CACHE_PRESSURE target with wrong effect throws")
    void codeCachePressureTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.CODE_CACHE_PRESSURE,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("CODE_CACHE_PRESSURE");
    }

    @Test
    @DisplayName("SAFEPOINT_STORM target with SafepointStormEffect succeeds")
    void safepointStormTargetWithSafepointStormEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.SAFEPOINT_STORM,
                          new ChaosEffect.SafepointStormEffect(Duration.ofMillis(50), 0)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SAFEPOINT_STORM target with wrong effect throws")
    void safepointStormTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.SAFEPOINT_STORM,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("SAFEPOINT_STORM");
    }

    @Test
    @DisplayName("STRING_INTERN_PRESSURE target with StringInternPressureEffect succeeds")
    void stringInternPressureTargetWithStringInternPressureEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.STRING_INTERN_PRESSURE,
                          new ChaosEffect.StringInternPressureEffect(1000, 32)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("STRING_INTERN_PRESSURE target with wrong effect throws")
    void stringInternPressureTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.STRING_INTERN_PRESSURE,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("STRING_INTERN_PRESSURE");
    }

    @Test
    @DisplayName("REFERENCE_QUEUE_FLOOD target with ReferenceQueueFloodEffect succeeds")
    void referenceQueueFloodTargetWithReferenceQueueFloodEffectSucceeds() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD,
                          new ChaosEffect.ReferenceQueueFloodEffect(500, Duration.ofMillis(100))),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("REFERENCE_QUEUE_FLOOD target with wrong effect throws")
    void referenceQueueFloodTargetWithWrongEffectThrows() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD,
                          new ChaosEffect.HeapPressureEffect(1024L, 512)),
                      featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("REFERENCE_QUEUE_FLOOD");
    }
  }

  @Nested
  @DisplayName("Stressor effects with non-StressSelector throw")
  class StressorEffectsWithNonStressSelector {

    @Test
    @DisplayName("HeapPressureEffect throws")
    void heapPressureEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.HeapPressureEffect(1024L, 512))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("MetaspacePressureEffect throws")
    void metaspacePressureEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.MetaspacePressureEffect(5, 0, false))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("DirectBufferPressureEffect throws")
    void directBufferPressureEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.DirectBufferPressureEffect(1024L, 512, false))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("GcPressureEffect throws")
    void gcPressureEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(
                  new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ofSeconds(5)))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("FinalizerBacklogEffect throws")
    void finalizerBacklogEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.FinalizerBacklogEffect(10, Duration.ofMillis(50)))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("KeepAliveEffect throws")
    void keepAliveEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.KeepAliveEffect("t", true, Duration.ofSeconds(1)))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("ThreadLeakEffect without allowDestructiveEffects throws ChaosActivationException")
    void threadLeakEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.ThreadLeakEffect(1, "leak-", true, null))
              .build();
      // Destructive-effect guard fires first (before stressor-requires-StressSelector check)
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosActivationException.class)
          .hasMessageContaining("non-recoverable");
    }

    @Test
    @DisplayName("ThreadLocalLeakEffect throws")
    void threadLocalLeakEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.ThreadLocalLeakEffect(5, 1024))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("DeadlockEffect without allowDestructiveEffects throws ChaosActivationException")
    void deadlockEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(100)))
              .build();
      // Destructive-effect guard fires first (before stressor-requires-StressSelector check)
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosActivationException.class)
          .hasMessageContaining("non-recoverable");
    }

    @Test
    @DisplayName("MonitorContentionEffect throws")
    void monitorContentionEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 2, false))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }
  }

  @Nested
  @DisplayName("ExceptionalCompletion requires AsyncSelector")
  class ExceptionalCompletionSelector {

    @Test
    @DisplayName("with non-AsyncSelector throws")
    void exceptionalCompletionWithNonAsyncSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(
                  new ChaosEffect.ExceptionalCompletionEffect(
                      ChaosEffect.FailureKind.TIMEOUT, "timeout"))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("with AsyncSelector succeeds")
    void exceptionalCompletionWithAsyncSelectorSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)))
              .effect(
                  new ChaosEffect.ExceptionalCompletionEffect(
                      ChaosEffect.FailureKind.IO, "io error"))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("ExceptionInjection requires MethodSelector")
  class ExceptionInjectionSelector {

    @Test
    @DisplayName("with non-MethodSelector throws")
    void exceptionInjectionWithNonMethodSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(
                  new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "io error", true))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("with MethodSelector succeeds")
    void exceptionInjectionWithMethodSelectorSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(
                  ChaosSelector.method(
                      Set.of(OperationType.METHOD_ENTER),
                      NamePattern.exact("com.example.Dao"),
                      NamePattern.any()))
              .effect(
                  new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "io error", true))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("ReturnValueCorruption requires MethodSelector")
  class ReturnValueCorruptionSelector {

    @Test
    @DisplayName("with non-MethodSelector throws")
    void returnValueCorruptionWithNonMethodSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(
                  new ChaosEffect.ReturnValueCorruptionEffect(ChaosEffect.ReturnValueStrategy.NULL))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("with MethodSelector succeeds")
    void returnValueCorruptionWithMethodSelectorSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(
                  ChaosSelector.method(
                      Set.of(OperationType.METHOD_EXIT),
                      NamePattern.exact("com.example.Service"),
                      NamePattern.any()))
              .effect(
                  new ChaosEffect.ReturnValueCorruptionEffect(ChaosEffect.ReturnValueStrategy.ZERO))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("ClockSkew requires JvmRuntimeSelector")
  class ClockSkewSelector {

    @Test
    @DisplayName("with non-JvmRuntimeSelector throws")
    void clockSkewWithNonJvmRuntimeSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(
                  new ChaosEffect.ClockSkewEffect(
                      Duration.ofSeconds(30), ChaosEffect.ClockSkewMode.FIXED))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }

    @Test
    @DisplayName("with JvmRuntimeSelector succeeds")
    void clockSkewWithJvmRuntimeSelectorSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
              .effect(
                  new ChaosEffect.ClockSkewEffect(
                      Duration.ofSeconds(30), ChaosEffect.ClockSkewMode.FIXED))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("GateEffect with StressSelector throws")
  class GateEffectWithStressSelector {

    @Test
    @DisplayName("GateEffect + StressSelector is rejected")
    void gateEffectWithStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("gate-stress")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
              .effect(ChaosEffect.gate(Duration.ofSeconds(5)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      // GateEffect is structurally incompatible with StressSelector; the validator rejects it
      // (either via the stress-binding check or the explicit GateEffect+StressSelector guard).
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class);
    }
  }

  @Nested
  @DisplayName("ExceptionInjectionEffect operation type constraints")
  class ExceptionInjectionOperationConstraints {

    @Test
    @DisplayName("METHOD_EXIT in selector throws")
    void exceptionInjectionWithMethodExitThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("inject-exit")
              .selector(
                  ChaosSelector.method(
                      Set.of(OperationType.METHOD_EXIT),
                      NamePattern.exact("com.example.Dao"),
                      NamePattern.any()))
              .effect(new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "msg", true))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("METHOD_EXIT");
    }

    @Test
    @DisplayName("METHOD_ENTER only in selector succeeds")
    void exceptionInjectionWithMethodEnterOnlySucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("inject-enter")
              .selector(
                  ChaosSelector.method(
                      Set.of(OperationType.METHOD_ENTER),
                      NamePattern.exact("com.example.Dao"),
                      NamePattern.any()))
              .effect(new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "msg", true))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("ReturnValueCorruptionEffect operation type constraints")
  class ReturnValueCorruptionOperationConstraints {

    @Test
    @DisplayName("METHOD_ENTER in selector throws")
    void returnValueCorruptionWithMethodEnterThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("corrupt-enter")
              .selector(
                  ChaosSelector.method(
                      Set.of(OperationType.METHOD_ENTER),
                      NamePattern.exact("com.example.Service"),
                      NamePattern.any()))
              .effect(
                  new ChaosEffect.ReturnValueCorruptionEffect(ChaosEffect.ReturnValueStrategy.NULL))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("METHOD_ENTER");
    }

    @Test
    @DisplayName("METHOD_EXIT only in selector succeeds")
    void returnValueCorruptionWithMethodExitOnlySucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("corrupt-exit")
              .selector(
                  ChaosSelector.method(
                      Set.of(OperationType.METHOD_EXIT),
                      NamePattern.exact("com.example.Service"),
                      NamePattern.any()))
              .effect(
                  new ChaosEffect.ReturnValueCorruptionEffect(ChaosEffect.ReturnValueStrategy.ZERO))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("SpuriousWakeupEffect requires NioSelector with NIO_SELECTOR_SELECT")
  class SpuriousWakeupEffectValidation {

    @Test
    @DisplayName("with non-NioSelector throws")
    void spuriousWakeupWithNonNioSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("spurious-wrong")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.SpuriousWakeupEffect())
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("SpuriousWakeupEffect requires NioSelector");
    }

    @Test
    @DisplayName("with NioSelector missing NIO_SELECTOR_SELECT throws")
    void spuriousWakeupWithNioSelectorMissingSelectThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("spurious-no-select")
              .selector(
                  new ChaosSelector.NioSelector(
                      Set.of(OperationType.NIO_CHANNEL_READ), NamePattern.any()))
              .effect(new ChaosEffect.SpuriousWakeupEffect())
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("NIO_SELECTOR_SELECT");
    }

    @Test
    @DisplayName("with NioSelector containing NIO_SELECTOR_SELECT succeeds")
    void spuriousWakeupWithValidNioSelectorSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("spurious-ok")
              .selector(
                  new ChaosSelector.NioSelector(
                      Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any()))
              .effect(new ChaosEffect.SpuriousWakeupEffect())
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("NioSelector operation constraints")
  class NioSelectorOperationValidation {

    @Test
    @DisplayName("non-NIO operation in NioSelector throws")
    void nioSelectorWithNonNioOperationThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("nio-bad-op")
              .selector(
                  new ChaosSelector.NioSelector(
                      Set.of(OperationType.SOCKET_CONNECT), NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("NioSelector operation");
    }

    @Test
    @DisplayName("NIO operations in NioSelector succeed")
    void nioSelectorWithValidNioOperationsSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("nio-ok")
              .selector(
                  new ChaosSelector.NioSelector(
                      Set.of(OperationType.NIO_SELECTOR_SELECT, OperationType.NIO_CHANNEL_READ),
                      NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("NetworkSelector operation constraints")
  class NetworkSelectorOperationValidation {

    @Test
    @DisplayName("non-socket operation in NetworkSelector throws")
    void networkSelectorWithNonSocketOperationThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("net-bad-op")
              .selector(
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("NetworkSelector operation");
    }

    @Test
    @DisplayName("socket operations in NetworkSelector succeed")
    void networkSelectorWithValidSocketOperationsSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("net-ok")
              .selector(
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.SOCKET_CONNECT, OperationType.SOCKET_CLOSE),
                      NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("ThreadLocalSelector operation constraints")
  class ThreadLocalSelectorOperationValidation {

    @Test
    @DisplayName("non-thread-local operation in ThreadLocalSelector throws")
    void threadLocalSelectorWithNonThreadLocalOperationThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("tl-bad-op")
              .selector(
                  new ChaosSelector.ThreadLocalSelector(
                      Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("ThreadLocalSelector operation");
    }

    @Test
    @DisplayName("THREAD_LOCAL_GET and THREAD_LOCAL_SET in ThreadLocalSelector succeed")
    void threadLocalSelectorWithValidOperationsSucceeds() {
      ChaosScenario scenario =
          ChaosScenario.builder("tl-ok")
              .selector(
                  new ChaosSelector.ThreadLocalSelector(
                      Set.of(OperationType.THREAD_LOCAL_GET, OperationType.THREAD_LOCAL_SET),
                      NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }
  }

  private static ChaosScenario stressScenario(
      ChaosSelector.StressTarget target, ChaosEffect effect) {
    return ChaosScenario.builder("stress-test")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.stress(target))
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  /** Builds a stress scenario with explicit destructive-effect opt-in. */
  private static ChaosScenario destructiveStressScenario(
      ChaosSelector.StressTarget target, ChaosEffect effect) {
    return ChaosScenario.builder("stress-test")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.stress(target))
        .effect(effect)
        .activationPolicy(ActivationPolicy.withDestructiveEffects())
        .build();
  }
}

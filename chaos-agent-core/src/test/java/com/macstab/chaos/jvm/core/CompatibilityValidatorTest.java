package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("CompatibilityValidator")
class CompatibilityValidatorTest {

  private final FeatureSet featureSet = new FeatureSet();

  @Nested
  @DisplayName("SESSION scope: refused only for operations that never run in a session")
  class SessionScope {

    private ChaosScenario session(final ChaosSelector selector, final ChaosEffect effect) {
      return ChaosScenario.builder("s")
          .scope(ChaosScenario.ScenarioScope.SESSION)
          .selector(selector)
          .effect(effect)
          .activationPolicy(ActivationPolicy.always())
          .build();
    }

    @Test
    @DisplayName(
        "a ThreadSelector on THREAD_SLEEP is accepted: a sleep runs on the session's thread")
    void threadSleepInSessionIsAccepted() {
      final ChaosScenario scenario =
          session(
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.delay(Duration.ofMillis(1)));

      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
        "a ShutdownSelector on EXECUTOR_SHUTDOWN is accepted: it runs on the caller's thread")
    void executorShutdownInSessionIsAccepted() {
      final ChaosScenario scenario =
          session(
              ChaosSelector.shutdown(Set.of(OperationType.EXECUTOR_SHUTDOWN)),
              ChaosEffect.delay(Duration.ofMillis(1)));

      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SHUTDOWN_HOOK_REGISTER is refused: a hook registration carries no session")
    void shutdownHookInSessionIsRefused() {
      final ChaosScenario scenario =
          session(
              ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
              ChaosEffect.delay(Duration.ofMillis(1)));

      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isExactlyInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("SHUTDOWN_HOOK_REGISTER");
    }

    @Test
    @DisplayName("class loading is refused: a class load carries no session")
    void classLoadingInSessionIsRefused() {
      final ChaosScenario scenario =
          session(
              ChaosSelector.classLoading(Set.of(OperationType.CLASS_LOAD), NamePattern.any()),
              ChaosEffect.delay(Duration.ofMillis(1)));

      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isExactlyInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("CLASS_LOAD");
    }

    @Test
    @DisplayName("a StressSelector is refused: stress scenarios must be JVM scoped")
    void stressInSessionIsRefused() {
      final ChaosScenario scenario =
          session(
              ChaosSelector.stress(ChaosSelector.StressTarget.HEAP),
              new ChaosEffect.HeapPressureEffect(1024L, 512));

      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isExactlyInstanceOf(ChaosValidationException.class)
          .hasMessage("stress scenarios must be JVM scoped");
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
    @DisplayName("ThreadLeakEffect without allowDestructiveEffects throws ChaosValidationException")
    void threadLeakEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.ThreadLeakEffect(1, "leak-", true, null))
              .build();
      // Destructive-effect guard fires first (before stressor-requires-StressSelector check)
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isExactlyInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("until the scenario is stopped");
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
    @DisplayName("DeadlockEffect without allowDestructiveEffects throws ChaosValidationException")
    void deadlockEffectWithNonStressSelectorThrows() {
      ChaosScenario scenario =
          ChaosScenario.builder("s")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(100)))
              .build();
      // Destructive-effect guard fires first (before stressor-requires-StressSelector check)
      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isExactlyInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("until the scenario is stopped");
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
    @DisplayName("non-NIO operation in NioSelector is rejected by the selector constructor")
    void nioSelectorWithNonNioOperationThrows() {
      // The NioSelector canonical constructor enforces its operation set — the invalid op never
      // reaches CompatibilityValidator. This is strictly stronger than a runtime-validator check:
      // invalid scenarios cannot be constructed at all.
      assertThatThrownBy(
              () ->
                  new ChaosSelector.NioSelector(
                      Set.of(OperationType.SOCKET_CONNECT), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("NioSelector")
          .hasMessageContaining("SOCKET_CONNECT");
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
    @DisplayName("non-socket operation in NetworkSelector is rejected by the selector constructor")
    void networkSelectorWithNonSocketOperationThrows() {
      // See NioSelectorOperationValidation: record-level enforcement is stronger than
      // validator-level enforcement.
      assertThatThrownBy(
              () ->
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("NetworkSelector")
          .hasMessageContaining("NIO_SELECTOR_SELECT");
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
    @DisplayName(
        "non-thread-local operation in ThreadLocalSelector is rejected by the selector constructor")
    void threadLocalSelectorWithNonThreadLocalOperationThrows() {
      // See NioSelectorOperationValidation: record-level enforcement is stronger than
      // validator-level enforcement.
      assertThatThrownBy(
              () ->
                  new ChaosSelector.ThreadLocalSelector(
                      Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ThreadLocalSelector")
          .hasMessageContaining("EXECUTOR_SUBMIT");
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

  @Nested
  @DisplayName("effects the selected operations cannot carry out")
  class EffectOperationSupport {

    @Test
    @DisplayName(
        "SUPPRESS on socket reads is rejected: the interception point can only delay or throw")
    void suppressOnSocketReadIsRejected() {
      final ChaosScenario scenario =
          jvm(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)), ChaosEffect.suppress());

      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("SOCKET_READ");
    }

    @Test
    @DisplayName(
        "SUPPRESS on HTTP client sends and JDBC statements is rejected: no real client skips the"
            + " call without failing it, so use reject")
    void suppressOnHttpAndJdbcIsRejected() {
      final ChaosScenario http =
          jvm(
              ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
              ChaosEffect.suppress());
      final ChaosScenario jdbc =
          jvm(ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE), ChaosEffect.suppress());

      assertThatThrownBy(() -> CompatibilityValidator.validate(http, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("HTTP_CLIENT_SEND")
          .hasMessageContaining("use reject");
      assertThatThrownBy(() -> CompatibilityValidator.validate(jdbc, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("JDBC_STATEMENT_EXECUTE")
          .hasMessageContaining("use reject");
    }

    @Test
    @DisplayName("SUPPRESS on Thread.sleep is accepted")
    void suppressOnSleepIsAccepted() {
      final ChaosScenario scenario =
          jvm(
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.suppress());

      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a delay on clock reads is rejected: clock reads only take a skew")
    void delayOnClockReadIsRejected() {
      final ChaosScenario scenario =
          jvm(
              ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)),
              ChaosEffect.delay(Duration.ofMillis(5)));

      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("ClockSkewEffect");
    }

    @Test
    @DisplayName("a clock skew on a non-clock operation is rejected")
    void skewOnNonClockOperationIsRejected() {
      final ChaosScenario scenario =
          jvm(
              ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)),
              ChaosEffect.skewClock(Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.FIXED));

      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("SYSTEM_GC_REQUEST");
    }

    @Test
    @DisplayName("ENOENT on a file read is refused: a read of an open descriptor never reports it")
    void enoentOnReadIsRefused() {
      final ChaosScenario scenario =
          jvm(
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
              ChaosEffect.errno(ChaosEffect.Errno.ENOENT));

      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("FILE_IO_READ");
    }

    @Test
    @DisplayName("a reject on a method entry or exit is accepted: a method may throw anything")
    void rejectOnMethodIsAccepted() {
      final ChaosScenario scenario =
          jvm(
              ChaosSelector.method(
                  Set.of(OperationType.METHOD_ENTER, OperationType.METHOD_EXIT),
                  NamePattern.exact("com.example.Service"),
                  NamePattern.any()),
              ChaosEffect.reject("down"));

      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    private ChaosScenario jvm(final ChaosSelector selector, final ChaosEffect effect) {
      return ChaosScenario.builder("support")
          .scope(ChaosScenario.ScenarioScope.JVM)
          .selector(selector)
          .effect(effect)
          .activationPolicy(ActivationPolicy.always())
          .build();
    }
  }

  @Nested
  @DisplayName("reject is accepted exactly where the real call can fail")
  class RejectTable {

    /** The operations no real call of which fails: a reject there is refused at activation. */
    private static final Set<OperationType> NO_REAL_FAILURE =
        EnumSet.of(
            OperationType.NETTY_CHANNEL_READ,
            OperationType.EXECUTOR_WORKER_RUN,
            OperationType.FORK_JOIN_TASK_RUN,
            OperationType.QUEUE_TAKE,
            OperationType.QUEUE_POLL,
            OperationType.THREAD_PARK,
            OperationType.MONITOR_ENTER,
            OperationType.SYSTEM_GC_REQUEST,
            OperationType.THREAD_LOCAL_GET,
            OperationType.THREAD_LOCAL_SET,
            OperationType.ASYNC_CANCEL,
            OperationType.ZIP_DEFLATE,
            OperationType.SYSTEM_EXIT_REQUEST,
            OperationType.EXECUTOR_SHUTDOWN);

    /** Clock reads take only a skew, and the stressors' lifecycle is not selectable. */
    private static final Set<OperationType> NOT_REJECTABLE =
        EnumSet.of(
            OperationType.SYSTEM_CLOCK_MILLIS,
            OperationType.SYSTEM_CLOCK_NANOS,
            OperationType.INSTANT_NOW,
            OperationType.CLOCK_READ,
            OperationType.LOCAL_DATE_TIME_NOW,
            OperationType.ZONED_DATE_TIME_NOW,
            OperationType.DATE_NEW,
            OperationType.LIFECYCLE);

    @ParameterizedTest(name = "{0}")
    @EnumSource(OperationType.class)
    @DisplayName("a reject on each operation")
    void rejectOnEachOperation(final OperationType operation) {
      if (operation == OperationType.LIFECYCLE) {
        assertThat(selectorOf(operation)).as("no selector selects LIFECYCLE").isNull();
        return;
      }
      final ChaosScenario scenario = jvm(selectorOf(operation), ChaosEffect.reject("down"));

      if (NO_REAL_FAILURE.contains(operation)) {
        assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
            .isExactlyInstanceOf(ChaosValidationException.class)
            .hasMessageContaining("RejectEffect is not supported for")
            .hasMessageContaining(operation.name());
      } else if (NOT_REJECTABLE.contains(operation)) {
        assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
            .isExactlyInstanceOf(ChaosValidationException.class);
      } else {
        assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
            .as("METHOD_ENTER, METHOD_EXIT and SCHEDULE_TICK included")
            .doesNotThrowAnyException();
      }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OperationType.class)
    @DisplayName("the suppress hint names no effect the operation refuses")
    void suppressHintNamesNoRefusedEffect(final OperationType operation) {
      final ChaosSelector selector = selectorOf(operation);
      if (selector == null) {
        return;
      }
      final String hint;
      try {
        CompatibilityValidator.validate(jvm(selector, ChaosEffect.suppress()), featureSet);
        return;
      } catch (final ChaosValidationException refused) {
        hint = refused.getMessage();
      }
      final int use = hint.indexOf("use ");
      if (use < 0) {
        return;
      }
      final String named = hint.substring(use);
      if (named.contains("delay")) {
        assertThatCode(
                () ->
                    CompatibilityValidator.validate(
                        jvm(selector, ChaosEffect.delay(Duration.ofMillis(1))), featureSet))
            .as("hint %s", hint)
            .doesNotThrowAnyException();
      }
      if (named.contains("reject")) {
        assertThatCode(
                () ->
                    CompatibilityValidator.validate(
                        jvm(selector, ChaosEffect.reject("down")), featureSet))
            .as("hint %s", hint)
            .doesNotThrowAnyException();
      }
    }

    /** A selector of {@code operation} alone, or {@code null} when no selector takes it. */
    private static @Nullable ChaosSelector selectorOf(final OperationType operation) {
      final List<ChaosSelector> selectors =
          EffectReachesTheCallTest.selectorsByOperation().get(operation);
      return selectors == null ? null : selectors.get(0);
    }

    private ChaosScenario jvm(final @Nullable ChaosSelector selector, final ChaosEffect effect) {
      return ChaosScenario.builder("reject-table")
          .scope(ChaosScenario.ScenarioScope.JVM)
          .selector(java.util.Objects.requireNonNull(selector))
          .effect(effect)
          .activationPolicy(ActivationPolicy.always())
          .build();
    }
  }

  @Nested
  @DisplayName("stressor parameters are refused, not clamped")
  class StressorLimits {

    @Test
    @DisplayName("heap pressure above the free heap is refused as an unsupported runtime")
    void heapAboveFreeHeapIsRefused() {
      final Runtime jvm = Runtime.getRuntime();
      final long free = jvm.maxMemory() - (jvm.totalMemory() - jvm.freeMemory());
      org.junit.jupiter.api.Assumptions.assumeTrue(
          free < ChaosEffect.HeapPressureEffect.MAX_BYTES, "the heap is larger than the maximum");
      final ChaosScenario scenario =
          stressScenario(
              ChaosSelector.StressTarget.HEAP,
              new ChaosEffect.HeapPressureEffect(
                  ChaosEffect.HeapPressureEffect.MAX_BYTES, 1024 * 1024));

      assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
          .isExactlyInstanceOf(ChaosUnsupportedFeatureException.class)
          .hasMessageContaining("heap");
    }

    @Test
    @DisplayName("more strings than the length can make distinct are refused")
    void moreStringsThanDistinctValuesAreRefused() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.STRING_INTERN_PRESSURE,
                          new ChaosEffect.StringInternPressureEffect(257, 1)),
                      featureSet))
          .isExactlyInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("256");
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.STRING_INTERN_PRESSURE,
                          new ChaosEffect.StringInternPressureEffect(256, 1)),
                      featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("more than one million strings, or strings over 1 MiB, are refused")
    void stringInternCapsAreRefused() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.STRING_INTERN_PRESSURE,
                          new ChaosEffect.StringInternPressureEffect(1_000_001, 8)),
                      featureSet))
          .isExactlyInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("1000000");
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stressScenario(
                          ChaosSelector.StressTarget.STRING_INTERN_PRESSURE,
                          new ChaosEffect.StringInternPressureEffect(1, (1 << 20) + 1)),
                      featureSet))
          .isExactlyInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("1048576");
    }

    @Test
    @DisplayName("a stressor interval below one millisecond is refused; one millisecond is not")
    void subMillisecondIntervalsAreRefused() {
      final Duration below = Duration.ofNanos(500_000L);
      final Duration oneMilli = Duration.ofMillis(1);
      for (final Duration interval : java.util.List.of(below, oneMilli)) {
        final java.util.List<ChaosScenario> scenarios =
            java.util.List.of(
                stressScenario(
                    ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD,
                    new ChaosEffect.ReferenceQueueFloodEffect(10, interval)),
                stressScenario(
                    ChaosSelector.StressTarget.SAFEPOINT_STORM,
                    new ChaosEffect.SafepointStormEffect(interval, 0)),
                stressScenario(
                    ChaosSelector.StressTarget.KEEPALIVE,
                    new ChaosEffect.KeepAliveEffect("keep", true, interval)));
        for (final ChaosScenario scenario : scenarios) {
          if (interval.equals(below)) {
            assertThatThrownBy(() -> CompatibilityValidator.validate(scenario, featureSet))
                .as("%s", scenario.effect())
                .isExactlyInstanceOf(ChaosValidationException.class)
                .hasMessageContaining("1 ms");
          } else {
            assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
                .as("%s", scenario.effect())
                .doesNotThrowAnyException();
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("direct-buffer pressure and the direct memory limit")
  class DirectMemoryLimit {

    private static final long MIB = 1024L * 1024L;

    private ChaosScenario directBuffer(final long totalBytes) {
      return ChaosScenario.builder("direct")
          .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
          .effect(new ChaosEffect.DirectBufferPressureEffect(totalBytes, (int) MIB, false))
          .activationPolicy(ActivationPolicy.always())
          .build();
    }

    @Test
    @DisplayName("more than the limit is refused as an unsupported runtime")
    void aboveTheLimitIsRefused() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      directBuffer(64 * MIB),
                      new FeatureSet(21, true, true, FeatureSet.UNKNOWN_GC, true, 8 * MIB)))
          .isInstanceOf(ChaosUnsupportedFeatureException.class)
          .hasMessageContaining("MaxDirectMemorySize");
    }

    @Test
    @DisplayName("up to the limit is accepted")
    void upToTheLimitIsAccepted() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      directBuffer(8 * MIB),
                      new FeatureSet(21, true, true, FeatureSet.UNKNOWN_GC, true, 8 * MIB)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unknown limit allows any size")
    void unknownLimitAllows() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      directBuffer(64 * MIB),
                      new FeatureSet(21, true, true, FeatureSet.UNKNOWN_GC, true, -1L)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the default limit (MaxDirectMemorySize=0) is the maximum heap")
    void defaultLimitIsTheMaximumHeap() {
      org.assertj.core.api.Assertions.assertThat(new FeatureSet().directMemoryLimit())
          .isEqualTo(Runtime.getRuntime().maxMemory());
    }
  }

  @Nested
  @DisplayName("stressors this JVM's flags leave without load (U-36)")
  class RuntimeFlagRefusals {

    private FeatureSet features(final boolean explicitGc, final boolean finalization) {
      return new FeatureSet(21, true, explicitGc, "G1", finalization, -1L);
    }

    private ChaosScenario stress(
        final ChaosSelector.StressTarget target, final ChaosEffect effect) {
      return ChaosScenario.builder("flags")
          .selector(ChaosSelector.stress(target))
          .effect(effect)
          .activationPolicy(ActivationPolicy.always())
          .build();
    }

    private ChaosScenario safepointStorm(final int retransformClassCount) {
      return stress(
          ChaosSelector.StressTarget.SAFEPOINT_STORM,
          new ChaosEffect.SafepointStormEffect(Duration.ofMillis(100), retransformClassCount));
    }

    @Test
    @DisplayName("SafepointStorm without retransforms under DisableExplicitGC is refused")
    void safepointStormWithoutRetransformsIsRefused() {
      assertThatThrownBy(
              () -> CompatibilityValidator.validate(safepointStorm(0), features(false, true)))
          .isInstanceOf(ChaosUnsupportedFeatureException.class)
          .hasMessageContaining("DisableExplicitGC")
          .hasMessageContaining("retransformClassCount");
    }

    @Test
    @DisplayName("SafepointStorm with retransforms under DisableExplicitGC is accepted")
    void safepointStormWithRetransformsIsAccepted() {
      assertThatCode(
              () -> CompatibilityValidator.validate(safepointStorm(5), features(false, true)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SafepointStorm without retransforms is accepted where System.gc() collects")
    void safepointStormWithExplicitGcIsAccepted() {
      assertThatCode(() -> CompatibilityValidator.validate(safepointStorm(0), features(true, true)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FinalizerBacklog under --finalization=disabled is refused")
    void finalizerBacklogWithoutFinalizationIsRefused() {
      assertThatThrownBy(
              () ->
                  CompatibilityValidator.validate(
                      stress(
                          ChaosSelector.StressTarget.FINALIZER_BACKLOG,
                          new ChaosEffect.FinalizerBacklogEffect(10, Duration.ofMillis(1))),
                      features(true, false)))
          .isInstanceOf(ChaosUnsupportedFeatureException.class)
          .hasMessageContaining("--finalization=disabled");
    }

    @Test
    @DisplayName("FinalizerBacklog with finalization enabled is accepted")
    void finalizerBacklogWithFinalizationIsAccepted() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stress(
                          ChaosSelector.StressTarget.FINALIZER_BACKLOG,
                          new ChaosEffect.FinalizerBacklogEffect(10, Duration.ofMillis(1))),
                      features(true, true)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ReferenceQueueFlood under DisableExplicitGC is accepted (degraded, OPEN-1)")
    void referenceQueueFloodUnderDisableExplicitGcIsAccepted() {
      assertThatCode(
              () ->
                  CompatibilityValidator.validate(
                      stress(
                          ChaosSelector.StressTarget.REFERENCE_QUEUE_FLOOD,
                          new ChaosEffect.ReferenceQueueFloodEffect(10, Duration.ofMillis(10))),
                      features(false, false)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
        "a JVM run with -XX:+DisableExplicitGC and --finalization=disabled refuses SafepointStorm"
            + " without retransforms and FinalizerBacklog, and accepts SafepointStorm with them")
    void childJvmRefusesUnderItsFlags() throws Exception {
      final String output = runProbe("-XX:+DisableExplicitGC", "--finalization=disabled");

      assertThat(output)
          .contains("storm-no-retransform=refused")
          .contains("storm-retransform=activated")
          .contains("finalizer-backlog=refused");
    }

    @Test
    @DisplayName("a JVM run without those flags accepts all three")
    void childJvmWithoutTheFlagsAccepts() throws Exception {
      final String output = runProbe("-XX:-DisableExplicitGC", "--finalization=enabled");

      assertThat(output)
          .contains("storm-no-retransform=activated")
          .contains("storm-retransform=activated")
          .contains("finalizer-backlog=activated");
    }

    private String runProbe(final String... flags) throws Exception {
      final List<String> command = new java.util.ArrayList<>();
      command.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.addAll(List.of(flags));
      command.addAll(
          List.of(
              "-cp", System.getProperty("java.class.path"), RuntimeFlagsProbeMain.class.getName()));
      final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      try {
        final String output =
            new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return output;
      } finally {
        process.destroyForcibly();
      }
    }

    @Test
    @DisplayName("--finalization: the last option wins; without one finalization is enabled")
    void finalizationOption() {
      assertThat(FeatureSet.finalizationEnabled(List.of("-Xmx1g"))).isTrue();
      assertThat(FeatureSet.finalizationEnabled(List.of("--finalization=disabled"))).isFalse();
      assertThat(
              FeatureSet.finalizationEnabled(
                  List.of("--finalization=disabled", "-Xss1m", "--finalization=enabled")))
          .isTrue();
    }

    @Test
    @DisplayName(
        "the collector is the one whose Use…GC flag is set; a flag the JVM lacks is skipped")
    void collectorName() {
      assertThat(
              FeatureSet.gcName(
                  flag -> {
                    if (flag.equals("UseShenandoahGC")) {
                      throw new IllegalArgumentException("VM option does not exist");
                    }
                    return String.valueOf(flag.equals("UseEpsilonGC"));
                  }))
          .isEqualTo("Epsilon");
      assertThat(FeatureSet.gcName(flag -> String.valueOf(flag.equals("UseZGC")))).isEqualTo("ZGC");
      assertThat(FeatureSet.gcName(flag -> "false")).isEqualTo(FeatureSet.UNKNOWN_GC);
    }

    @Test
    @DisplayName(
        "this JVM's own probes: System.gc() collects, finalization runs, a known collector")
    void ownProbes() {
      final FeatureSet probed = new FeatureSet();
      assertThat(probed.explicitGc()).isTrue();
      assertThat(probed.finalization()).isTrue();
      assertThat(probed.gcName()).isNotEqualTo(FeatureSet.UNKNOWN_GC);
    }
  }
}

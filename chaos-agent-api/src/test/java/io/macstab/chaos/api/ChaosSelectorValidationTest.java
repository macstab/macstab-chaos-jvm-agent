package io.macstab.chaos.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ChaosSelectorValidationTest {

  // ── MethodSelector both-ANY throws ───────────────────────────────────────

  @Test
  void methodSelectorBothAnyThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosSelector.MethodSelector(
                Set.of(OperationType.METHOD_ENTER),
                NamePattern.any(),
                NamePattern.any(),
                null));
  }

  @Test
  void methodSelectorNonAnyClassPatternIsValid() {
    assertDoesNotThrow(
        () ->
            new ChaosSelector.MethodSelector(
                Set.of(OperationType.METHOD_ENTER),
                NamePattern.exact("com.example.Foo"),
                NamePattern.any(),
                null));
  }

  @Test
  void methodSelectorNonAnyMethodPatternIsValid() {
    assertDoesNotThrow(
        () ->
            new ChaosSelector.MethodSelector(
                Set.of(OperationType.METHOD_EXIT),
                NamePattern.any(),
                NamePattern.exact("connect"),
                null));
  }

  @Test
  void methodSelectorBothNonAnyIsValid() {
    assertDoesNotThrow(
        () ->
            new ChaosSelector.MethodSelector(
                Set.of(OperationType.METHOD_ENTER),
                NamePattern.prefix("com.example"),
                NamePattern.prefix("get"),
                null));
  }

  // ── StressSelector null target throws ────────────────────────────────────

  @Test
  void stressSelectorNullTargetThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosSelector.StressSelector(null));
  }

  @Test
  void stressSelectorValidTargetIsValid() {
    assertDoesNotThrow(() -> new ChaosSelector.StressSelector(ChaosSelector.StressTarget.HEAP));
  }

  // ── operations null throws ────────────────────────────────────────────────

  @Test
  void threadSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosSelector.ThreadSelector(
                null, ChaosSelector.ThreadKind.ANY, NamePattern.any(), null));
  }

  @Test
  void executorSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosSelector.ExecutorSelector(
                null, NamePattern.any(), NamePattern.any(), null));
  }

  @Test
  void queueSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosSelector.QueueSelector(null, NamePattern.any()));
  }

  @Test
  void asyncSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosSelector.AsyncSelector(null));
  }

  @Test
  void schedulingSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosSelector.SchedulingSelector(null, NamePattern.any(), null));
  }

  @Test
  void shutdownSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosSelector.ShutdownSelector(null, NamePattern.any()));
  }

  @Test
  void classLoadingSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosSelector.ClassLoadingSelector(
                null, NamePattern.any(), NamePattern.any()));
  }

  @Test
  void methodSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosSelector.MethodSelector(
                null, NamePattern.exact("Foo"), NamePattern.any(), null));
  }

  @Test
  void monitorSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosSelector.MonitorSelector(null, NamePattern.any()));
  }

  @Test
  void jvmRuntimeSelectorNullOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosSelector.JvmRuntimeSelector(null));
  }

  // ── operations empty throws ───────────────────────────────────────────────

  @Test
  void threadSelectorEmptyOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosSelector.ThreadSelector(
                Set.of(), ChaosSelector.ThreadKind.ANY, NamePattern.any(), null));
  }

  @Test
  void executorSelectorEmptyOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosSelector.ExecutorSelector(
                Set.of(), NamePattern.any(), NamePattern.any(), null));
  }

  @Test
  void asyncSelectorEmptyOperationsThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosSelector.AsyncSelector(Set.of()));
  }

  // ── factory methods produce the right type ────────────────────────────────

  @Test
  void threadFactoryProducesThreadSelector() {
    assertInstanceOf(
        ChaosSelector.ThreadSelector.class,
        ChaosSelector.thread(
            Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY));
  }

  @Test
  void executorFactoryProducesExecutorSelector() {
    assertInstanceOf(
        ChaosSelector.ExecutorSelector.class,
        ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)));
  }

  @Test
  void queueFactoryProducesQueueSelector() {
    assertInstanceOf(
        ChaosSelector.QueueSelector.class,
        ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)));
  }

  @Test
  void asyncFactoryProducesAsyncSelector() {
    assertInstanceOf(
        ChaosSelector.AsyncSelector.class,
        ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)));
  }

  @Test
  void schedulingFactoryProducesSchedulingSelector() {
    assertInstanceOf(
        ChaosSelector.SchedulingSelector.class,
        ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)));
  }

  @Test
  void shutdownFactoryProducesShutdownSelector() {
    assertInstanceOf(
        ChaosSelector.ShutdownSelector.class,
        ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)));
  }

  @Test
  void classLoadingFactoryProducesClassLoadingSelector() {
    assertInstanceOf(
        ChaosSelector.ClassLoadingSelector.class,
        ChaosSelector.classLoading(
            Set.of(OperationType.CLASS_LOAD), NamePattern.any()));
  }

  @Test
  void methodFactoryProducesMethodSelector() {
    assertInstanceOf(
        ChaosSelector.MethodSelector.class,
        ChaosSelector.method(
            Set.of(OperationType.METHOD_ENTER),
            NamePattern.exact("com.example.Foo"),
            NamePattern.any()));
  }

  @Test
  void monitorFactoryProducesMonitorSelector() {
    assertInstanceOf(
        ChaosSelector.MonitorSelector.class,
        ChaosSelector.monitor(Set.of(OperationType.MONITOR_ENTER)));
  }

  @Test
  void jvmRuntimeFactoryProducesJvmRuntimeSelector() {
    assertInstanceOf(
        ChaosSelector.JvmRuntimeSelector.class,
        ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)));
  }

  @Test
  void stressFactoryProducesStressSelector() {
    assertInstanceOf(
        ChaosSelector.StressSelector.class,
        ChaosSelector.stress(ChaosSelector.StressTarget.HEAP));
  }
}

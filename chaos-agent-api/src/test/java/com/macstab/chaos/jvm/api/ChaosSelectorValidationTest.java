package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosSelector")
class ChaosSelectorValidationTest {

  @Nested
  @DisplayName("MethodSelector both-ANY throws")
  class MethodSelectorBothAny {

    @Test
    @DisplayName("both ANY throws")
    void methodSelectorBothAnyThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.METHOD_ENTER),
                      NamePattern.any(),
                      NamePattern.any(),
                      null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("non-ANY class pattern is valid")
    void nonAnyClassPatternIsValid() {
      assertThatCode(
              () ->
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.METHOD_ENTER),
                      NamePattern.exact("com.example.Foo"),
                      NamePattern.any(),
                      null))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-ANY method pattern is valid")
    void nonAnyMethodPatternIsValid() {
      assertThatCode(
              () ->
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.METHOD_EXIT),
                      NamePattern.any(),
                      NamePattern.exact("connect"),
                      null))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("both non-ANY is valid")
    void bothNonAnyIsValid() {
      assertThatCode(
              () ->
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.METHOD_ENTER),
                      NamePattern.prefix("com.example"),
                      NamePattern.prefix("get"),
                      null))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("StressSelector null target throws")
  class StressSelectorNullTarget {

    @Test
    @DisplayName("null target throws")
    void nullTargetThrows() {
      assertThatThrownBy(() -> new ChaosSelector.StressSelector(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("valid target is valid")
    void validTargetIsValid() {
      assertThatCode(() -> new ChaosSelector.StressSelector(ChaosSelector.StressTarget.HEAP))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("null operations throws")
  class NullOperations {

    @Test
    @DisplayName("ThreadSelector null operations throws")
    void threadSelectorNullOperationsThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.ThreadSelector(
                      null, ChaosSelector.ThreadKind.ANY, NamePattern.any(), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ExecutorSelector null operations throws")
    void executorSelectorNullOperationsThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.ExecutorSelector(
                      null, NamePattern.any(), NamePattern.any(), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("QueueSelector null operations throws")
    void queueSelectorNullOperationsThrows() {
      assertThatThrownBy(() -> new ChaosSelector.QueueSelector(null, NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AsyncSelector null operations throws")
    void asyncSelectorNullOperationsThrows() {
      assertThatThrownBy(() -> new ChaosSelector.AsyncSelector(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SchedulingSelector null operations throws")
    void schedulingSelectorNullOperationsThrows() {
      assertThatThrownBy(() -> new ChaosSelector.SchedulingSelector(null, NamePattern.any(), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ShutdownSelector null operations throws")
    void shutdownSelectorNullOperationsThrows() {
      assertThatThrownBy(() -> new ChaosSelector.ShutdownSelector(null, NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ClassLoadingSelector null operations throws")
    void classLoadingSelectorNullOperationsThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.ClassLoadingSelector(
                      null, NamePattern.any(), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MethodSelector null operations throws")
    void methodSelectorNullOperationsThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.MethodSelector(
                      null, NamePattern.exact("Foo"), NamePattern.any(), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MonitorSelector null operations throws")
    void monitorSelectorNullOperationsThrows() {
      assertThatThrownBy(() -> new ChaosSelector.MonitorSelector(null, NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("JvmRuntimeSelector null operations throws")
    void jvmRuntimeSelectorNullOperationsThrows() {
      assertThatThrownBy(() -> new ChaosSelector.JvmRuntimeSelector(null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("empty operations throws")
  class EmptyOperations {

    @Test
    @DisplayName("ThreadSelector empty operations throws")
    void threadSelectorEmptyOperationsThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.ThreadSelector(
                      Set.of(), ChaosSelector.ThreadKind.ANY, NamePattern.any(), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ExecutorSelector empty operations throws")
    void executorSelectorEmptyOperationsThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.ExecutorSelector(
                      Set.of(), NamePattern.any(), NamePattern.any(), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AsyncSelector empty operations throws")
    void asyncSelectorEmptyOperationsThrows() {
      assertThatThrownBy(() -> new ChaosSelector.AsyncSelector(Set.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("cross-selector operation rejection")
  class CrossSelectorOperationRejection {

    @Test
    @DisplayName("MethodSelector rejects JvmRuntime operation")
    void methodSelectorRejectsJvmRuntimeOperation() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.OBJECT_DESERIALIZE),
                      NamePattern.exact("java.io.ObjectInputStream"),
                      NamePattern.any(),
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("MethodSelector")
          .hasMessageContaining("OBJECT_DESERIALIZE");
    }

    @Test
    @DisplayName("QueueSelector rejects THREAD_START")
    void queueSelectorRejectsThreadStart() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.QueueSelector(
                      Set.of(OperationType.THREAD_START), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("QueueSelector")
          .hasMessageContaining("THREAD_START");
    }

    @Test
    @DisplayName("JvmRuntimeSelector rejects HTTP_CLIENT_SEND")
    void jvmRuntimeSelectorRejectsHttpClientSend() {
      assertThatThrownBy(
              () -> new ChaosSelector.JvmRuntimeSelector(Set.of(OperationType.HTTP_CLIENT_SEND)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("JvmRuntimeSelector")
          .hasMessageContaining("HTTP_CLIENT_SEND");
    }

    @Test
    @DisplayName("NetworkSelector rejects NIO_CHANNEL_READ (belongs to NioSelector)")
    void networkSelectorRejectsNioChannelRead() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.NIO_CHANNEL_READ), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("NetworkSelector");
    }

    @Test
    @DisplayName("AsyncSelector accepts ASYNC_CANCEL")
    void asyncSelectorAcceptsAsyncCancel() {
      assertThatCode(() -> new ChaosSelector.AsyncSelector(Set.of(OperationType.ASYNC_CANCEL)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ThreadSelector accepts THREAD_SLEEP")
    void threadSelectorAcceptsThreadSleep() {
      assertThatCode(
              () ->
                  new ChaosSelector.ThreadSelector(
                      Set.of(OperationType.THREAD_SLEEP),
                      ChaosSelector.ThreadKind.ANY,
                      NamePattern.any(),
                      null))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("error message lists valid operations for diagnostics")
    void errorMessageListsValidOperations() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.DnsSelector(
                      Set.of(OperationType.SSL_HANDSHAKE), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("DNS_RESOLVE");
    }
  }

  @Nested
  @DisplayName("factory methods produce the right type")
  class FactoryMethods {

    @Test
    @DisplayName("thread() produces ThreadSelector")
    void threadFactoryProducesThreadSelector() {
      assertThat(
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY))
          .isInstanceOf(ChaosSelector.ThreadSelector.class);
    }

    @Test
    @DisplayName("executor() produces ExecutorSelector")
    void executorFactoryProducesExecutorSelector() {
      assertThat(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
          .isInstanceOf(ChaosSelector.ExecutorSelector.class);
    }

    @Test
    @DisplayName("queue() produces QueueSelector")
    void queueFactoryProducesQueueSelector() {
      assertThat(ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)))
          .isInstanceOf(ChaosSelector.QueueSelector.class);
    }

    @Test
    @DisplayName("async() produces AsyncSelector")
    void asyncFactoryProducesAsyncSelector() {
      assertThat(ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)))
          .isInstanceOf(ChaosSelector.AsyncSelector.class);
    }

    @Test
    @DisplayName("scheduling() produces SchedulingSelector")
    void schedulingFactoryProducesSchedulingSelector() {
      assertThat(ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)))
          .isInstanceOf(ChaosSelector.SchedulingSelector.class);
    }

    @Test
    @DisplayName("shutdown() produces ShutdownSelector")
    void shutdownFactoryProducesShutdownSelector() {
      assertThat(ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)))
          .isInstanceOf(ChaosSelector.ShutdownSelector.class);
    }

    @Test
    @DisplayName("classLoading() produces ClassLoadingSelector")
    void classLoadingFactoryProducesClassLoadingSelector() {
      assertThat(ChaosSelector.classLoading(Set.of(OperationType.CLASS_LOAD), NamePattern.any()))
          .isInstanceOf(ChaosSelector.ClassLoadingSelector.class);
    }

    @Test
    @DisplayName("method() produces MethodSelector")
    void methodFactoryProducesMethodSelector() {
      assertThat(
              ChaosSelector.method(
                  Set.of(OperationType.METHOD_ENTER),
                  NamePattern.exact("com.example.Foo"),
                  NamePattern.any()))
          .isInstanceOf(ChaosSelector.MethodSelector.class);
    }

    @Test
    @DisplayName("monitor() produces MonitorSelector")
    void monitorFactoryProducesMonitorSelector() {
      assertThat(ChaosSelector.monitor(Set.of(OperationType.MONITOR_ENTER)))
          .isInstanceOf(ChaosSelector.MonitorSelector.class);
    }

    @Test
    @DisplayName("jvmRuntime() produces JvmRuntimeSelector")
    void jvmRuntimeFactoryProducesJvmRuntimeSelector() {
      assertThat(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
          .isInstanceOf(ChaosSelector.JvmRuntimeSelector.class);
    }

    @Test
    @DisplayName("stress() produces StressSelector")
    void stressFactoryProducesStressSelector() {
      assertThat(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
          .isInstanceOf(ChaosSelector.StressSelector.class);
    }
  }
}

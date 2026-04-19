package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SelectorMatcher")
class SelectorMatcherTest {

  @Nested
  @DisplayName("ThreadSelector")
  class ThreadSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.ANY,
              NamePattern.any(),
              null);
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_START,
              "java.lang.Thread",
              null,
              "my-thread",
              false,
              false,
              false,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.ANY,
              NamePattern.any(),
              null);
      InvocationContext context =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT,
              "java.lang.Thread",
              null,
              "my-thread",
              false,
              false,
              false,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("PLATFORM kind matches non-virtual thread")
    void platformKindMatchesNonVirtualThread() {
      ChaosSelector selector =
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.PLATFORM,
              NamePattern.any(),
              null);
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_START,
              "java.lang.Thread",
              null,
              "worker",
              false,
              false,
              false,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("PLATFORM kind does not match virtual thread")
    void platformKindDoesNotMatchVirtualThread() {
      ChaosSelector selector =
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.PLATFORM,
              NamePattern.any(),
              null);
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_START,
              "java.lang.Thread",
              null,
              "vt-worker",
              false,
              false,
              true,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("VIRTUAL kind matches virtual thread")
    void virtualKindMatchesVirtualThread() {
      ChaosSelector selector =
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.VIRTUAL_THREAD_START),
              ChaosSelector.ThreadKind.VIRTUAL,
              NamePattern.any(),
              null);
      InvocationContext context =
          new InvocationContext(
              OperationType.VIRTUAL_THREAD_START,
              "java.lang.Thread",
              null,
              "vt",
              false,
              false,
              true,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("daemon filter matches daemon thread")
    void daemonFilterMatchesDaemonThread() {
      ChaosSelector selector =
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.ANY,
              NamePattern.any(),
              true);
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_START,
              "java.lang.Thread",
              null,
              "daemon-worker",
              false,
              true,
              false,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("daemon filter does not match non-daemon thread")
    void daemonFilterDoesNotMatchNonDaemonThread() {
      ChaosSelector selector =
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.ANY,
              NamePattern.any(),
              true);
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_START,
              "java.lang.Thread",
              null,
              "user-thread",
              false,
              false,
              false,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("name pattern filters by thread name")
    void namePatternFiltersByThreadName() {
      ChaosSelector selector =
          new ChaosSelector.ThreadSelector(
              Set.of(OperationType.THREAD_START),
              ChaosSelector.ThreadKind.ANY,
              NamePattern.prefix("worker-"),
              null);
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.THREAD_START,
              "java.lang.Thread",
              null,
              "worker-1",
              false,
              false,
              false,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.THREAD_START,
              "java.lang.Thread",
              null,
              "main",
              false,
              false,
              false,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }
  }

  @Nested
  @DisplayName("ExecutorSelector")
  class ExecutorSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.ExecutorSelector(
              Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any(), NamePattern.any(), null);
      InvocationContext context =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT,
              "java.util.concurrent.ThreadPoolExecutor",
              "com.example.Task",
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.ExecutorSelector(
              Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any(), NamePattern.any(), null);
      InvocationContext context =
          new InvocationContext(
              OperationType.QUEUE_PUT,
              "java.util.concurrent.ThreadPoolExecutor",
              "com.example.Task",
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("filters on executor class name")
    void filtersOnExecutorClassName() {
      ChaosSelector selector =
          new ChaosSelector.ExecutorSelector(
              Set.of(OperationType.EXECUTOR_SUBMIT),
              NamePattern.exact("java.util.concurrent.ThreadPoolExecutor"),
              NamePattern.any(),
              null);
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT,
              "java.util.concurrent.ThreadPoolExecutor",
              null,
              null,
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT,
              "com.example.CustomExecutor",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }

    @Test
    @DisplayName("filters on task class name")
    void filtersOnTaskClassName() {
      ChaosSelector selector =
          new ChaosSelector.ExecutorSelector(
              Set.of(OperationType.EXECUTOR_SUBMIT),
              NamePattern.any(),
              NamePattern.prefix("com.example"),
              null);
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT,
              "java.util.concurrent.ThreadPoolExecutor",
              "com.example.SlowTask",
              null,
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT,
              "java.util.concurrent.ThreadPoolExecutor",
              "org.other.FastTask",
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }
  }

  @Nested
  @DisplayName("QueueSelector")
  class QueueSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.QueueSelector(Set.of(OperationType.QUEUE_PUT), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.QUEUE_PUT,
              "java.util.concurrent.LinkedBlockingQueue",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.QueueSelector(Set.of(OperationType.QUEUE_PUT), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.QUEUE_OFFER,
              "java.util.concurrent.LinkedBlockingQueue",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("filters on queue class name")
    void filtersOnQueueClassName() {
      ChaosSelector selector =
          new ChaosSelector.QueueSelector(
              Set.of(OperationType.QUEUE_TAKE),
              NamePattern.exact("java.util.concurrent.ArrayBlockingQueue"));
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.QUEUE_TAKE,
              "java.util.concurrent.ArrayBlockingQueue",
              null,
              null,
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.QUEUE_TAKE,
              "java.util.concurrent.LinkedBlockingQueue",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }
  }

  @Nested
  @DisplayName("AsyncSelector")
  class AsyncSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.AsyncSelector(Set.of(OperationType.ASYNC_COMPLETE));
      InvocationContext context =
          new InvocationContext(
              OperationType.ASYNC_COMPLETE,
              "java.util.concurrent.CompletableFuture",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.AsyncSelector(Set.of(OperationType.ASYNC_COMPLETE));
      InvocationContext context =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT, null, null, null, false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("SchedulingSelector")
  class SchedulingSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.SchedulingSelector(
              Set.of(OperationType.SCHEDULE_SUBMIT), NamePattern.any(), null);
      InvocationContext context =
          new InvocationContext(
              OperationType.SCHEDULE_SUBMIT,
              "java.util.concurrent.ScheduledThreadPoolExecutor",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("periodicOnly=true matches periodic, not one-shot")
    void periodicOnlyTrueMatchesPeriodicOnly() {
      ChaosSelector selector =
          new ChaosSelector.SchedulingSelector(
              Set.of(OperationType.SCHEDULE_TICK), NamePattern.any(), true);
      InvocationContext periodicContext =
          new InvocationContext(
              OperationType.SCHEDULE_TICK,
              "java.util.concurrent.ScheduledThreadPoolExecutor",
              null,
              null,
              true,
              null,
              null,
              null);
      InvocationContext nonPeriodicContext =
          new InvocationContext(
              OperationType.SCHEDULE_TICK,
              "java.util.concurrent.ScheduledThreadPoolExecutor",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, periodicContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, nonPeriodicContext)).isFalse();
    }

    @Test
    @DisplayName("periodicOnly=false matches both periodic and one-shot")
    void periodicOnlyFalseMatchesBoth() {
      ChaosSelector selector =
          new ChaosSelector.SchedulingSelector(
              Set.of(OperationType.SCHEDULE_TICK), NamePattern.any(), false);
      InvocationContext periodicContext =
          new InvocationContext(
              OperationType.SCHEDULE_TICK,
              "java.util.concurrent.ScheduledThreadPoolExecutor",
              null,
              null,
              true,
              null,
              null,
              null);
      InvocationContext nonPeriodicContext =
          new InvocationContext(
              OperationType.SCHEDULE_TICK,
              "java.util.concurrent.ScheduledThreadPoolExecutor",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, periodicContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, nonPeriodicContext)).isTrue();
    }
  }

  @Nested
  @DisplayName("ShutdownSelector")
  class ShutdownSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.ShutdownSelector(
              Set.of(OperationType.SHUTDOWN_HOOK_REGISTER), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.SHUTDOWN_HOOK_REGISTER,
              "java.lang.Thread",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.ShutdownSelector(
              Set.of(OperationType.SHUTDOWN_HOOK_REGISTER), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.EXECUTOR_SHUTDOWN,
              "java.lang.Thread",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("filters on target class name")
    void filtersOnTargetClassName() {
      ChaosSelector selector =
          new ChaosSelector.ShutdownSelector(
              Set.of(OperationType.EXECUTOR_SHUTDOWN), NamePattern.prefix("com.example"));
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.EXECUTOR_SHUTDOWN,
              "com.example.ManagedExecutor",
              null,
              null,
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.EXECUTOR_SHUTDOWN,
              "java.util.concurrent.ThreadPoolExecutor",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }
  }

  @Nested
  @DisplayName("ClassLoadingSelector")
  class ClassLoadingSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.ClassLoadingSelector(
              Set.of(OperationType.CLASS_LOAD), NamePattern.any(), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.CLASS_LOAD,
              "java.net.URLClassLoader",
              null,
              "com.example.MyClass",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("filters on target class name")
    void filtersOnTargetClassName() {
      ChaosSelector selector =
          new ChaosSelector.ClassLoadingSelector(
              Set.of(OperationType.CLASS_LOAD),
              NamePattern.prefix("com.example"),
              NamePattern.any());
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.CLASS_LOAD,
              "java.net.URLClassLoader",
              null,
              "com.example.SomeClass",
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.CLASS_LOAD,
              "java.net.URLClassLoader",
              null,
              "org.other.Class",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }

    @Test
    @DisplayName("filters on loader class name")
    void filtersOnLoaderClassName() {
      ChaosSelector selector =
          new ChaosSelector.ClassLoadingSelector(
              Set.of(OperationType.CLASS_LOAD),
              NamePattern.any(),
              NamePattern.exact("java.net.URLClassLoader"));
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.CLASS_LOAD,
              "java.net.URLClassLoader",
              null,
              "any.Class",
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.CLASS_LOAD,
              "com.example.AppClassLoader",
              null,
              "any.Class",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }
  }

  @Nested
  @DisplayName("MethodSelector")
  class MethodSelectorTests {

    @Test
    @DisplayName("matches correct operation and class")
    void matchesCorrectOperationAndClass() {
      ChaosSelector selector =
          new ChaosSelector.MethodSelector(
              Set.of(OperationType.METHOD_ENTER),
              NamePattern.exact("com.example.Dao"),
              NamePattern.any(),
              null);
      InvocationContext context =
          new InvocationContext(
              OperationType.METHOD_ENTER,
              "com.example.Dao",
              null,
              "findById",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong class")
    void doesNotMatchWrongClass() {
      ChaosSelector selector =
          new ChaosSelector.MethodSelector(
              Set.of(OperationType.METHOD_ENTER),
              NamePattern.exact("com.example.Dao"),
              NamePattern.any(),
              null);
      InvocationContext context =
          new InvocationContext(
              OperationType.METHOD_ENTER,
              "com.example.Service",
              null,
              "findById",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("filters on method name")
    void filtersOnMethodName() {
      ChaosSelector selector =
          new ChaosSelector.MethodSelector(
              Set.of(OperationType.METHOD_ENTER),
              NamePattern.any(),
              NamePattern.exact("connect"),
              null);
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.METHOD_ENTER,
              "com.example.Client",
              null,
              "connect",
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.METHOD_ENTER,
              "com.example.Client",
              null,
              "disconnect",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }

    @Test
    @DisplayName("signature pattern matches on descriptor")
    void signaturePatternMatchesOnDescriptor() {
      ChaosSelector selector =
          new ChaosSelector.MethodSelector(
              Set.of(OperationType.METHOD_ENTER),
              NamePattern.exact("com.example.Dao"),
              NamePattern.any(),
              NamePattern.exact("(Ljava/lang/String;)V"));
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.METHOD_ENTER,
              "com.example.Dao",
              "(Ljava/lang/String;)V",
              "save",
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.METHOD_ENTER,
              "com.example.Dao",
              "(I)V",
              "save",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }

    @Test
    @DisplayName("null signature pattern matches any descriptor")
    void nullSignaturePatternMatchesAnyDescriptor() {
      ChaosSelector selector =
          new ChaosSelector.MethodSelector(
              Set.of(OperationType.METHOD_ENTER),
              NamePattern.exact("com.example.Dao"),
              NamePattern.any(),
              null);
      InvocationContext context =
          new InvocationContext(
              OperationType.METHOD_ENTER,
              "com.example.Dao",
              "(I)V",
              "save",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }
  }

  @Nested
  @DisplayName("MonitorSelector")
  class MonitorSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.MonitorSelector(Set.of(OperationType.MONITOR_ENTER), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.MONITOR_ENTER,
              "com.example.SharedResource",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.MonitorSelector(Set.of(OperationType.MONITOR_ENTER), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_PARK,
              "com.example.SharedResource",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("filters on monitor class name")
    void filtersOnMonitorClassName() {
      ChaosSelector selector =
          new ChaosSelector.MonitorSelector(
              Set.of(OperationType.MONITOR_ENTER), NamePattern.exact("com.example.Lock"));
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.MONITOR_ENTER, "com.example.Lock", null, null, false, null, null, null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.MONITOR_ENTER,
              "com.example.Other",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }
  }

  @Nested
  @DisplayName("JvmRuntimeSelector")
  class JvmRuntimeSelectorTests {

    @Test
    @DisplayName("matches correct operation")
    void matchesCorrectOperation() {
      ChaosSelector selector =
          new ChaosSelector.JvmRuntimeSelector(Set.of(OperationType.SYSTEM_CLOCK_MILLIS));
      InvocationContext context =
          new InvocationContext(
              OperationType.SYSTEM_CLOCK_MILLIS, null, null, null, false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.JvmRuntimeSelector(Set.of(OperationType.SYSTEM_CLOCK_MILLIS));
      InvocationContext context =
          new InvocationContext(
              OperationType.SYSTEM_CLOCK_NANOS, null, null, null, false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("StressSelector")
  class StressSelectorTests {

    @Test
    @DisplayName("matches LIFECYCLE operation")
    void matchesLifecycleOperation() {
      ChaosSelector selector = new ChaosSelector.StressSelector(ChaosSelector.StressTarget.HEAP);
      InvocationContext context =
          new InvocationContext(OperationType.LIFECYCLE, null, null, null, false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match non-LIFECYCLE operation")
    void doesNotMatchNonLifecycleOperation() {
      ChaosSelector selector =
          new ChaosSelector.StressSelector(ChaosSelector.StressTarget.DEADLOCK);
      InvocationContext context =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT, null, null, null, false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("NioSelector")
  class NioSelectorTests {

    @Test
    @DisplayName("matches correct operation and channel class")
    void matchesCorrectOperationAndChannelClass() {
      ChaosSelector selector =
          new ChaosSelector.NioSelector(
              Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.NIO_SELECTOR_SELECT,
              "sun.nio.ch.SelectorImpl",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.NioSelector(
              Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.NIO_CHANNEL_READ,
              "sun.nio.ch.SelectorImpl",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("does not match when channel class pattern does not match")
    void doesNotMatchWhenChannelClassPatternMismatch() {
      ChaosSelector selector =
          new ChaosSelector.NioSelector(
              Set.of(OperationType.NIO_SELECTOR_SELECT),
              NamePattern.exact("sun.nio.ch.SelectorImpl"));
      InvocationContext context =
          new InvocationContext(
              OperationType.NIO_SELECTOR_SELECT,
              "java.nio.channels.SocketChannel",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("NetworkSelector")
  class NetworkSelectorTests {

    @Test
    @DisplayName("matches correct operation and remote host")
    void matchesCorrectOperationAndRemoteHost() {
      ChaosSelector selector =
          new ChaosSelector.NetworkSelector(
              Set.of(OperationType.SOCKET_CONNECT), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.SOCKET_CONNECT, null, null, "example.com", false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.NetworkSelector(
              Set.of(OperationType.SOCKET_CONNECT), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.SOCKET_READ, null, null, "example.com", false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("does not match when remote host pattern does not match")
    void doesNotMatchWhenHostPatternMismatch() {
      ChaosSelector selector =
          new ChaosSelector.NetworkSelector(
              Set.of(OperationType.SOCKET_CONNECT), NamePattern.exact("example.com"));
      InvocationContext context =
          new InvocationContext(
              OperationType.SOCKET_CONNECT, null, null, "other.host", false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("matches multiple socket operations")
    void matchesMultipleSocketOperations() {
      ChaosSelector selector =
          new ChaosSelector.NetworkSelector(
              Set.of(
                  OperationType.SOCKET_CONNECT,
                  OperationType.SOCKET_WRITE,
                  OperationType.SOCKET_READ),
              NamePattern.any());
      for (OperationType op :
          new OperationType[] {
            OperationType.SOCKET_CONNECT, OperationType.SOCKET_WRITE, OperationType.SOCKET_READ
          }) {
        InvocationContext context =
            new InvocationContext(op, null, null, "db.internal", false, null, null, null);
        assertThat(SelectorMatcher.matches(selector, context)).isTrue();
      }
    }
  }

  @Nested
  @DisplayName("ThreadLocalSelector")
  class ThreadLocalSelectorTests {

    @Test
    @DisplayName("matches THREAD_LOCAL_GET with matching class pattern")
    void matchesGetWithMatchingClassPattern() {
      ChaosSelector selector =
          new ChaosSelector.ThreadLocalSelector(
              Set.of(OperationType.THREAD_LOCAL_GET), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_LOCAL_GET,
              "java.lang.ThreadLocal",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("matches THREAD_LOCAL_SET with matching class pattern")
    void matchesSetWithMatchingClassPattern() {
      ChaosSelector selector =
          new ChaosSelector.ThreadLocalSelector(
              Set.of(OperationType.THREAD_LOCAL_SET), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_LOCAL_SET,
              "java.lang.InheritableThreadLocal",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.ThreadLocalSelector(
              Set.of(OperationType.THREAD_LOCAL_GET), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_LOCAL_SET,
              "java.lang.ThreadLocal",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("does not match when thread-local class pattern does not match")
    void doesNotMatchWhenClassPatternMismatch() {
      ChaosSelector selector =
          new ChaosSelector.ThreadLocalSelector(
              Set.of(OperationType.THREAD_LOCAL_GET), NamePattern.exact("com.app.MyThreadLocal"));
      InvocationContext context =
          new InvocationContext(
              OperationType.THREAD_LOCAL_GET,
              "java.lang.ThreadLocal",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("DnsSelector")
  class DnsSelectorTests {

    @Test
    @DisplayName("matches DNS_RESOLVE operation")
    void matchesDnsResolveOperation() {
      ChaosSelector selector =
          new ChaosSelector.DnsSelector(Set.of(OperationType.DNS_RESOLVE), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.DNS_RESOLVE,
              "java.net.InetAddress",
              null,
              "db.internal",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.DnsSelector(Set.of(OperationType.DNS_RESOLVE), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.SOCKET_CONNECT, null, null, "db.internal", false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("hostname pattern matches by prefix")
    void hostnamePatternMatchesByPrefix() {
      ChaosSelector selector =
          new ChaosSelector.DnsSelector(
              Set.of(OperationType.DNS_RESOLVE), NamePattern.prefix("db."));
      InvocationContext matchContext =
          new InvocationContext(
              OperationType.DNS_RESOLVE,
              "java.net.InetAddress",
              null,
              "db.internal",
              false,
              null,
              null,
              null);
      InvocationContext noMatchContext =
          new InvocationContext(
              OperationType.DNS_RESOLVE,
              "java.net.InetAddress",
              null,
              "api.external.com",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, matchContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, noMatchContext)).isFalse();
    }

    @Test
    @DisplayName("any hostname pattern matches null hostname (getLocalHost)")
    void anyPatternMatchesNullHostname() {
      ChaosSelector selector =
          new ChaosSelector.DnsSelector(Set.of(OperationType.DNS_RESOLVE), NamePattern.any());
      InvocationContext context =
          new InvocationContext(
              OperationType.DNS_RESOLVE,
              "java.net.InetAddress",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }
  }

  @Nested
  @DisplayName("SslSelector")
  class SslSelectorTests {

    @Test
    @DisplayName("matches SSL_HANDSHAKE operation")
    void matchesSslHandshakeOperation() {
      ChaosSelector selector = new ChaosSelector.SslSelector(Set.of(OperationType.SSL_HANDSHAKE));
      InvocationContext context =
          new InvocationContext(
              OperationType.SSL_HANDSHAKE,
              "javax.net.ssl.SSLSocket",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector = new ChaosSelector.SslSelector(Set.of(OperationType.SSL_HANDSHAKE));
      InvocationContext context =
          new InvocationContext(
              OperationType.SOCKET_CONNECT, null, null, null, false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("matches SSLEngine class name")
    void matchesSslEngineClassName() {
      ChaosSelector selector = new ChaosSelector.SslSelector(Set.of(OperationType.SSL_HANDSHAKE));
      InvocationContext context =
          new InvocationContext(
              OperationType.SSL_HANDSHAKE,
              "javax.net.ssl.SSLEngine",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }
  }

  @Nested
  @DisplayName("FileIoSelector")
  class FileIoSelectorTests {

    @Test
    @DisplayName("matches FILE_IO_READ operation")
    void matchesFileIoReadOperation() {
      ChaosSelector selector = new ChaosSelector.FileIoSelector(Set.of(OperationType.FILE_IO_READ));
      InvocationContext context =
          new InvocationContext(
              OperationType.FILE_IO_READ,
              "java.io.FileInputStream",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("matches FILE_IO_WRITE operation")
    void matchesFileIoWriteOperation() {
      ChaosSelector selector =
          new ChaosSelector.FileIoSelector(Set.of(OperationType.FILE_IO_WRITE));
      InvocationContext context =
          new InvocationContext(
              OperationType.FILE_IO_WRITE,
              "java.io.FileOutputStream",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("matches both read and write when both are in operations set")
    void matchesBothReadAndWrite() {
      ChaosSelector selector =
          new ChaosSelector.FileIoSelector(
              Set.of(OperationType.FILE_IO_READ, OperationType.FILE_IO_WRITE));
      InvocationContext readContext =
          new InvocationContext(
              OperationType.FILE_IO_READ,
              "java.io.FileInputStream",
              null,
              null,
              false,
              null,
              null,
              null);
      InvocationContext writeContext =
          new InvocationContext(
              OperationType.FILE_IO_WRITE,
              "java.io.FileOutputStream",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, readContext)).isTrue();
      assertThat(SelectorMatcher.matches(selector, writeContext)).isTrue();
    }

    @Test
    @DisplayName("READ-only selector does not match WRITE operation")
    void readOnlySelectorDoesNotMatchWrite() {
      ChaosSelector selector = new ChaosSelector.FileIoSelector(Set.of(OperationType.FILE_IO_READ));
      InvocationContext context =
          new InvocationContext(
              OperationType.FILE_IO_WRITE,
              "java.io.FileOutputStream",
              null,
              null,
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("does not match wrong operation")
    void doesNotMatchWrongOperation() {
      ChaosSelector selector =
          new ChaosSelector.FileIoSelector(
              Set.of(OperationType.FILE_IO_READ, OperationType.FILE_IO_WRITE));
      InvocationContext context =
          new InvocationContext(
              OperationType.NIO_CHANNEL_READ, null, null, null, false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }
  }
}

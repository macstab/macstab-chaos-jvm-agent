package io.macstab.chaos.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.NamePattern;
import io.macstab.chaos.api.OperationType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelectorMatcherTest {

  // ── ThreadSelector ────────────────────────────────────────────────────────

  @Test
  void threadSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.THREAD_START),
            ChaosSelector.ThreadKind.ANY,
            NamePattern.any(),
            null);
    InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_START, "java.lang.Thread", null, "my-thread", false,
            false, false, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void threadSelectorDoesNotMatchWrongOperation() {
    ChaosSelector selector =
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.THREAD_START),
            ChaosSelector.ThreadKind.ANY,
            NamePattern.any(),
            null);
    InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT, "java.lang.Thread", null, "my-thread", false,
            false, false, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  @Test
  void threadSelectorPlatformKindMatchesNonVirtualThread() {
    ChaosSelector selector =
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.THREAD_START),
            ChaosSelector.ThreadKind.PLATFORM,
            NamePattern.any(),
            null);
    InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_START, "java.lang.Thread", null, "worker", false,
            false, false, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void threadSelectorPlatformKindDoesNotMatchVirtualThread() {
    ChaosSelector selector =
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.THREAD_START),
            ChaosSelector.ThreadKind.PLATFORM,
            NamePattern.any(),
            null);
    InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_START, "java.lang.Thread", null, "vt-worker", false,
            false, true, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  @Test
  void threadSelectorVirtualKindMatchesVirtualThread() {
    ChaosSelector selector =
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.VIRTUAL_THREAD_START),
            ChaosSelector.ThreadKind.VIRTUAL,
            NamePattern.any(),
            null);
    InvocationContext context =
        new InvocationContext(
            OperationType.VIRTUAL_THREAD_START, "java.lang.Thread", null, "vt", false,
            false, true, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void threadSelectorDaemonFilterMatchesDaemonTrue() {
    ChaosSelector selector =
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.THREAD_START),
            ChaosSelector.ThreadKind.ANY,
            NamePattern.any(),
            true);
    InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_START, "java.lang.Thread", null, "daemon-worker", false,
            true, false, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void threadSelectorDaemonFilterDoesNotMatchNonDaemon() {
    ChaosSelector selector =
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.THREAD_START),
            ChaosSelector.ThreadKind.ANY,
            NamePattern.any(),
            true);
    InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_START, "java.lang.Thread", null, "user-thread", false,
            false, false, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  @Test
  void threadSelectorNamePatternFiltersThreadName() {
    ChaosSelector selector =
        new ChaosSelector.ThreadSelector(
            Set.of(OperationType.THREAD_START),
            ChaosSelector.ThreadKind.ANY,
            NamePattern.prefix("worker-"),
            null);
    InvocationContext matchContext =
        new InvocationContext(
            OperationType.THREAD_START, "java.lang.Thread", null, "worker-1", false,
            false, false, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.THREAD_START, "java.lang.Thread", null, "main", false,
            false, false, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  // ── ExecutorSelector ─────────────────────────────────────────────────────

  @Test
  void executorSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.ExecutorSelector(
            Set.of(OperationType.EXECUTOR_SUBMIT),
            NamePattern.any(),
            NamePattern.any(),
            null);
    InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT,
            "java.util.concurrent.ThreadPoolExecutor",
            "com.example.Task",
            null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void executorSelectorDoesNotMatchWrongOperation() {
    ChaosSelector selector =
        new ChaosSelector.ExecutorSelector(
            Set.of(OperationType.EXECUTOR_SUBMIT),
            NamePattern.any(),
            NamePattern.any(),
            null);
    InvocationContext context =
        new InvocationContext(
            OperationType.QUEUE_PUT,
            "java.util.concurrent.ThreadPoolExecutor",
            "com.example.Task",
            null, false, null, null, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  @Test
  void executorSelectorFiltersOnExecutorClassName() {
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
            null, null, false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT,
            "com.example.CustomExecutor",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  @Test
  void executorSelectorFiltersOnTaskClassName() {
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
            null, false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT,
            "java.util.concurrent.ThreadPoolExecutor",
            "org.other.FastTask",
            null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  // ── QueueSelector ─────────────────────────────────────────────────────────

  @Test
  void queueSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.QueueSelector(
            Set.of(OperationType.QUEUE_PUT), NamePattern.any());
    InvocationContext context =
        new InvocationContext(
            OperationType.QUEUE_PUT,
            "java.util.concurrent.LinkedBlockingQueue",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void queueSelectorDoesNotMatchWrongOperation() {
    ChaosSelector selector =
        new ChaosSelector.QueueSelector(
            Set.of(OperationType.QUEUE_PUT), NamePattern.any());
    InvocationContext context =
        new InvocationContext(
            OperationType.QUEUE_OFFER,
            "java.util.concurrent.LinkedBlockingQueue",
            null, null, false, null, null, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  @Test
  void queueSelectorFiltersOnQueueClassName() {
    ChaosSelector selector =
        new ChaosSelector.QueueSelector(
            Set.of(OperationType.QUEUE_TAKE),
            NamePattern.exact("java.util.concurrent.ArrayBlockingQueue"));
    InvocationContext matchContext =
        new InvocationContext(
            OperationType.QUEUE_TAKE,
            "java.util.concurrent.ArrayBlockingQueue",
            null, null, false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.QUEUE_TAKE,
            "java.util.concurrent.LinkedBlockingQueue",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  // ── AsyncSelector ─────────────────────────────────────────────────────────

  @Test
  void asyncSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.AsyncSelector(Set.of(OperationType.ASYNC_COMPLETE));
    InvocationContext context =
        new InvocationContext(
            OperationType.ASYNC_COMPLETE,
            "java.util.concurrent.CompletableFuture",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void asyncSelectorDoesNotMatchWrongOperation() {
    ChaosSelector selector =
        new ChaosSelector.AsyncSelector(Set.of(OperationType.ASYNC_COMPLETE));
    InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT,
            null, null, null, false, null, null, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  // ── SchedulingSelector ────────────────────────────────────────────────────

  @Test
  void schedulingSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.SchedulingSelector(
            Set.of(OperationType.SCHEDULE_SUBMIT), NamePattern.any(), null);
    InvocationContext context =
        new InvocationContext(
            OperationType.SCHEDULE_SUBMIT,
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void schedulingSelectorPeriodicOnlyFilterMatchesPeriodic() {
    ChaosSelector selector =
        new ChaosSelector.SchedulingSelector(
            Set.of(OperationType.SCHEDULE_TICK), NamePattern.any(), true);
    InvocationContext periodicContext =
        new InvocationContext(
            OperationType.SCHEDULE_TICK,
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            null, null, true, null, null, null);
    InvocationContext nonPeriodicContext =
        new InvocationContext(
            OperationType.SCHEDULE_TICK,
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, periodicContext));
    assertFalse(SelectorMatcher.matches(selector, nonPeriodicContext));
  }

  @Test
  void schedulingSelectorPeriodicOnlyFalseMatchesBoth() {
    ChaosSelector selector =
        new ChaosSelector.SchedulingSelector(
            Set.of(OperationType.SCHEDULE_TICK), NamePattern.any(), false);
    InvocationContext periodicContext =
        new InvocationContext(
            OperationType.SCHEDULE_TICK,
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            null, null, true, null, null, null);
    InvocationContext nonPeriodicContext =
        new InvocationContext(
            OperationType.SCHEDULE_TICK,
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, periodicContext));
    assertTrue(SelectorMatcher.matches(selector, nonPeriodicContext));
  }

  // ── ShutdownSelector ──────────────────────────────────────────────────────

  @Test
  void shutdownSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.ShutdownSelector(
            Set.of(OperationType.SHUTDOWN_HOOK_REGISTER), NamePattern.any());
    InvocationContext context =
        new InvocationContext(
            OperationType.SHUTDOWN_HOOK_REGISTER,
            "java.lang.Thread",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void shutdownSelectorDoesNotMatchWrongOperation() {
    ChaosSelector selector =
        new ChaosSelector.ShutdownSelector(
            Set.of(OperationType.SHUTDOWN_HOOK_REGISTER), NamePattern.any());
    InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SHUTDOWN,
            "java.lang.Thread",
            null, null, false, null, null, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  @Test
  void shutdownSelectorFiltersOnTargetClassName() {
    ChaosSelector selector =
        new ChaosSelector.ShutdownSelector(
            Set.of(OperationType.EXECUTOR_SHUTDOWN),
            NamePattern.prefix("com.example"));
    InvocationContext matchContext =
        new InvocationContext(
            OperationType.EXECUTOR_SHUTDOWN,
            "com.example.ManagedExecutor",
            null, null, false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.EXECUTOR_SHUTDOWN,
            "java.util.concurrent.ThreadPoolExecutor",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  // ── ClassLoadingSelector ─────────────────────────────────────────────────

  @Test
  void classLoadingSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.ClassLoadingSelector(
            Set.of(OperationType.CLASS_LOAD), NamePattern.any(), NamePattern.any());
    InvocationContext context =
        new InvocationContext(
            OperationType.CLASS_LOAD,
            "java.net.URLClassLoader",
            null, "com.example.MyClass", false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void classLoadingSelectorFiltersOnTargetName() {
    ChaosSelector selector =
        new ChaosSelector.ClassLoadingSelector(
            Set.of(OperationType.CLASS_LOAD),
            NamePattern.prefix("com.example"),
            NamePattern.any());
    InvocationContext matchContext =
        new InvocationContext(
            OperationType.CLASS_LOAD,
            "java.net.URLClassLoader",
            null, "com.example.SomeClass", false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.CLASS_LOAD,
            "java.net.URLClassLoader",
            null, "org.other.Class", false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  @Test
  void classLoadingSelectorFiltersOnLoaderClassName() {
    ChaosSelector selector =
        new ChaosSelector.ClassLoadingSelector(
            Set.of(OperationType.CLASS_LOAD),
            NamePattern.any(),
            NamePattern.exact("java.net.URLClassLoader"));
    InvocationContext matchContext =
        new InvocationContext(
            OperationType.CLASS_LOAD,
            "java.net.URLClassLoader",
            null, "any.Class", false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.CLASS_LOAD,
            "com.example.AppClassLoader",
            null, "any.Class", false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  // ── MethodSelector ────────────────────────────────────────────────────────

  @Test
  void methodSelectorMatchesCorrectOperationAndClass() {
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
            null, "findById", false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void methodSelectorDoesNotMatchWrongClass() {
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
            null, "findById", false, null, null, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  @Test
  void methodSelectorFiltersOnMethodName() {
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
            null, "connect", false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.METHOD_ENTER,
            "com.example.Client",
            null, "disconnect", false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  @Test
  void methodSelectorSignaturePatternMatchesSubjectClassName() {
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
            "save", false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.METHOD_ENTER,
            "com.example.Dao",
            "(I)V",
            "save", false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  @Test
  void methodSelectorNullSignaturePatternMatchesAnything() {
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
            "save", false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  // ── MonitorSelector ───────────────────────────────────────────────────────

  @Test
  void monitorSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.MonitorSelector(
            Set.of(OperationType.MONITOR_ENTER), NamePattern.any());
    InvocationContext context =
        new InvocationContext(
            OperationType.MONITOR_ENTER,
            "com.example.SharedResource",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void monitorSelectorDoesNotMatchWrongOperation() {
    ChaosSelector selector =
        new ChaosSelector.MonitorSelector(
            Set.of(OperationType.MONITOR_ENTER), NamePattern.any());
    InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_PARK,
            "com.example.SharedResource",
            null, null, false, null, null, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  @Test
  void monitorSelectorFiltersOnMonitorClassName() {
    ChaosSelector selector =
        new ChaosSelector.MonitorSelector(
            Set.of(OperationType.MONITOR_ENTER),
            NamePattern.exact("com.example.Lock"));
    InvocationContext matchContext =
        new InvocationContext(
            OperationType.MONITOR_ENTER,
            "com.example.Lock",
            null, null, false, null, null, null);
    InvocationContext noMatchContext =
        new InvocationContext(
            OperationType.MONITOR_ENTER,
            "com.example.Other",
            null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, matchContext));
    assertFalse(SelectorMatcher.matches(selector, noMatchContext));
  }

  // ── JvmRuntimeSelector ────────────────────────────────────────────────────

  @Test
  void jvmRuntimeSelectorMatchesCorrectOperation() {
    ChaosSelector selector =
        new ChaosSelector.JvmRuntimeSelector(Set.of(OperationType.SYSTEM_CLOCK_MILLIS));
    InvocationContext context =
        new InvocationContext(
            OperationType.SYSTEM_CLOCK_MILLIS,
            null, null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void jvmRuntimeSelectorDoesNotMatchWrongOperation() {
    ChaosSelector selector =
        new ChaosSelector.JvmRuntimeSelector(Set.of(OperationType.SYSTEM_CLOCK_MILLIS));
    InvocationContext context =
        new InvocationContext(
            OperationType.SYSTEM_CLOCK_NANOS,
            null, null, null, false, null, null, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

  // ── StressSelector ────────────────────────────────────────────────────────

  @Test
  void stressSelectorMatchesLifecycleOperation() {
    ChaosSelector selector = new ChaosSelector.StressSelector(ChaosSelector.StressTarget.HEAP);
    InvocationContext context =
        new InvocationContext(
            OperationType.LIFECYCLE,
            null, null, null, false, null, null, null);
    assertTrue(SelectorMatcher.matches(selector, context));
  }

  @Test
  void stressSelectorDoesNotMatchNonLifecycleOperation() {
    ChaosSelector selector = new ChaosSelector.StressSelector(ChaosSelector.StressTarget.DEADLOCK);
    InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT,
            null, null, null, false, null, null, null);
    assertFalse(SelectorMatcher.matches(selector, context));
  }

}

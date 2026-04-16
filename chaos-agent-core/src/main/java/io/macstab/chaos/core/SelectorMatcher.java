package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.OperationType;

final class SelectorMatcher {
  private SelectorMatcher() {}

  static boolean matches(final ChaosSelector selector, final InvocationContext context) {
    return switch (selector) {
      case ChaosSelector.ThreadSelector threadSelector ->
          threadSelector.operations().contains(context.operationType())
              && threadSelector.threadNamePattern().matches(context.targetName())
              && matchesThreadKind(threadSelector, context)
              && matchesDaemon(threadSelector, context);
      case ChaosSelector.ExecutorSelector executorSelector ->
          executorSelector.operations().contains(context.operationType())
              && executorSelector.executorClassPattern().matches(context.targetClassName())
              && executorSelector.taskClassPattern().matches(context.subjectClassName());
      case ChaosSelector.QueueSelector queueSelector ->
          queueSelector.operations().contains(context.operationType())
              && queueSelector.queueClassPattern().matches(context.targetClassName());
      case ChaosSelector.AsyncSelector asyncSelector ->
          asyncSelector.operations().contains(context.operationType());
      case ChaosSelector.SchedulingSelector schedulingSelector ->
          schedulingSelector.operations().contains(context.operationType())
              && schedulingSelector.executorClassPattern().matches(context.targetClassName())
              && (schedulingSelector.periodicOnly() == null
                  || !schedulingSelector.periodicOnly()
                  || context.periodic());
      case ChaosSelector.ShutdownSelector shutdownSelector ->
          shutdownSelector.operations().contains(context.operationType())
              && shutdownSelector.targetClassPattern().matches(context.targetClassName());
      case ChaosSelector.ClassLoadingSelector classLoadingSelector ->
          classLoadingSelector.operations().contains(context.operationType())
              && classLoadingSelector.targetNamePattern().matches(context.targetName())
              && classLoadingSelector.loaderClassPattern().matches(context.targetClassName());
      case ChaosSelector.MethodSelector methodSelector ->
          methodSelector.operations().contains(context.operationType())
              && methodSelector.classPattern().matches(context.targetClassName())
              && methodSelector.methodNamePattern().matches(context.targetName())
              && (methodSelector.signaturePattern() == null
                  || methodSelector.signaturePattern().matches(context.subjectClassName()));
      case ChaosSelector.MonitorSelector monitorSelector ->
          monitorSelector.operations().contains(context.operationType())
              && monitorSelector.monitorClassPattern().matches(context.targetClassName());
      case ChaosSelector.JvmRuntimeSelector jvmRuntimeSelector ->
          jvmRuntimeSelector.operations().contains(context.operationType());
      case ChaosSelector.StressSelector stressSelector ->
          context.operationType() == OperationType.LIFECYCLE && stressSelector.target() != null;
    };
  }

  private static boolean matchesThreadKind(
      final ChaosSelector.ThreadSelector selector, final InvocationContext context) {
    return switch (selector.kind()) {
      case ANY -> true;
      case PLATFORM -> !Boolean.TRUE.equals(context.virtualThread());
      case VIRTUAL -> Boolean.TRUE.equals(context.virtualThread());
    };
  }

  private static boolean matchesDaemon(
      final ChaosSelector.ThreadSelector selector, final InvocationContext context) {
    return selector.daemon() == null || selector.daemon().equals(context.daemonThread());
  }
}

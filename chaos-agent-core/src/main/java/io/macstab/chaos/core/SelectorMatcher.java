package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.OperationType;

final class SelectorMatcher {
  private SelectorMatcher() {}

  static boolean matches(ChaosSelector selector, InvocationContext context) {
    if (selector instanceof ChaosSelector.ThreadSelector threadSelector) {
      return threadSelector.operations().contains(context.operationType())
          && threadSelector.threadNamePattern().matches(context.targetName())
          && matchesThreadKind(threadSelector, context)
          && matchesDaemon(threadSelector, context);
    }
    if (selector instanceof ChaosSelector.ExecutorSelector executorSelector) {
      return executorSelector.operations().contains(context.operationType())
          && executorSelector.executorClassPattern().matches(context.targetClassName())
          && executorSelector.taskClassPattern().matches(context.subjectClassName());
    }
    if (selector instanceof ChaosSelector.QueueSelector queueSelector) {
      return queueSelector.operations().contains(context.operationType())
          && queueSelector.queueClassPattern().matches(context.targetClassName());
    }
    if (selector instanceof ChaosSelector.AsyncSelector asyncSelector) {
      return asyncSelector.operations().contains(context.operationType());
    }
    if (selector instanceof ChaosSelector.SchedulingSelector schedulingSelector) {
      return schedulingSelector.operations().contains(context.operationType())
          && schedulingSelector.executorClassPattern().matches(context.targetClassName())
          && (schedulingSelector.periodicOnly() == null
              || !schedulingSelector.periodicOnly()
              || context.periodic());
    }
    if (selector instanceof ChaosSelector.ShutdownSelector shutdownSelector) {
      return shutdownSelector.operations().contains(context.operationType())
          && shutdownSelector.targetClassPattern().matches(context.targetClassName());
    }
    if (selector instanceof ChaosSelector.ClassLoadingSelector classLoadingSelector) {
      return classLoadingSelector.operations().contains(context.operationType())
          && classLoadingSelector.targetNamePattern().matches(context.targetName())
          && classLoadingSelector.loaderClassPattern().matches(context.targetClassName());
    }
    if (selector instanceof ChaosSelector.MethodSelector methodSelector) {
      return methodSelector.operations().contains(context.operationType())
          && methodSelector.classPattern().matches(context.targetClassName())
          && methodSelector.methodNamePattern().matches(context.targetName())
          && (methodSelector.signaturePattern() == null
              || methodSelector.signaturePattern().matches(context.subjectClassName()));
    }
    if (selector instanceof ChaosSelector.MonitorSelector monitorSelector) {
      return monitorSelector.operations().contains(context.operationType())
          && monitorSelector.monitorClassPattern().matches(context.targetClassName());
    }
    if (selector instanceof ChaosSelector.JvmRuntimeSelector jvmRuntimeSelector) {
      return jvmRuntimeSelector.operations().contains(context.operationType());
    }
    if (selector instanceof ChaosSelector.StressSelector stressSelector) {
      return context.operationType() == OperationType.LIFECYCLE && stressSelector.target() != null;
    }
    return false;
  }

  private static boolean matchesThreadKind(
      ChaosSelector.ThreadSelector selector, InvocationContext context) {
    return switch (selector.kind()) {
      case ANY -> true;
      case PLATFORM -> !Boolean.TRUE.equals(context.virtualThread());
      case VIRTUAL -> Boolean.TRUE.equals(context.virtualThread());
    };
  }

  private static boolean matchesDaemon(
      ChaosSelector.ThreadSelector selector, InvocationContext context) {
    return selector.daemon() == null || selector.daemon().equals(context.daemonThread());
  }
}

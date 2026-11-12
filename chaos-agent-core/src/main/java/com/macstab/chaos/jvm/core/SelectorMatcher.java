package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.CidrBlock;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * Stateless utility that evaluates whether a {@link ChaosSelector} matches a given {@link
 * InvocationContext}.
 *
 * <p>The matching logic uses an exhaustive {@code switch} expression over the sealed {@link
 * ChaosSelector} hierarchy. Each selector variant carries its own matching predicate; this class
 * applies it against the fields of the context. See individual cases for semantics.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is stateless; all methods are static and may be called concurrently without
 * synchronization.
 */
final class SelectorMatcher {
  private SelectorMatcher() {}

  /**
   * Returns {@code true} if {@code selector} matches the given {@code context}.
   *
   * <p>The switch is exhaustive over all known {@link ChaosSelector} subtypes. Matching is
   * performed field-by-field against the context: the operation type must be in the selector's
   * declared operation set, and any pattern fields (class name, thread name, host, etc.) must match
   * via their respective {@code matches()} predicates. Optional fields ({@code daemon}, {@code
   * periodicOnly}, {@code signaturePattern}) are treated as wildcards when {@code null}.
   *
   * @param selector the selector configuration from the registered {@link
   *     com.macstab.chaos.jvm.api.ChaosScenario}
   * @param context the runtime context captured at the instrumentation point
   * @return {@code true} if this invocation falls within the selector's scope; {@code false}
   *     otherwise
   */
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
              && executorSelector.taskClassPattern().matches(context.subjectClassName())
              && (executorSelector.scheduledOnly() == null
                  || !executorSelector.scheduledOnly()
                  || context.periodic());
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
              && methodSelector.signaturePattern().matches(context.signature());
      case ChaosSelector.MonitorSelector monitorSelector ->
          monitorSelector.operations().contains(context.operationType())
              && monitorSelector.monitorClassPattern().matches(context.targetClassName())
              && !(isAny(monitorSelector.monitorClassPattern())
                  && JdkOwnUse.isPoolWorkerLock(context.targetClassName()));
      case ChaosSelector.JvmRuntimeSelector jvmRuntimeSelector ->
          jvmRuntimeSelector.operations().contains(context.operationType());
      case ChaosSelector.NioSelector nioSelector ->
          nioSelector.operations().contains(context.operationType())
              && nioSelector.channelClassPattern().matches(context.targetClassName());
      case ChaosSelector.NetworkSelector networkSelector ->
          networkSelector.operations().contains(context.operationType())
              && networkSelector.remoteHostPattern().matches(context.targetName())
              && (networkSelector.remoteCidr() == null
                  || CidrBlock.cached(networkSelector.remoteCidr())
                      .contains(context.remoteAddress()))
              && (networkSelector.remotePort() == null
                  || networkSelector.remotePort() == context.remotePort())
              && inSizeRange(
                  networkSelector.minBytes(), networkSelector.maxBytes(), context.byteCount());
      case ChaosSelector.ThreadLocalSelector threadLocalSelector ->
          threadLocalSelector.operations().contains(context.operationType())
              && threadLocalSelector.threadLocalClassPattern().matches(context.targetClassName())
              && !(isAny(threadLocalSelector.threadLocalClassPattern())
                  && JdkOwnUse.isJdkThreadLocal(context.targetClassName()));
      case ChaosSelector.HttpClientSelector httpClientSelector ->
          httpClientSelector.operations().contains(context.operationType())
              && httpClientSelector.urlPattern().matches(context.targetName());
      case ChaosSelector.JdbcSelector jdbcSelector ->
          jdbcSelector.operations().contains(context.operationType())
              && jdbcSelector.targetPattern().matches(context.targetName());
      case ChaosSelector.DnsSelector dnsSelector ->
          dnsSelector.operations().contains(context.operationType())
              && dnsSelector.hostnamePattern().matches(context.targetName());
      case ChaosSelector.SslSelector sslSelector ->
          sslSelector.operations().contains(context.operationType());
      case ChaosSelector.FileIoSelector fileIoSelector ->
          fileIoSelector.operations().contains(context.operationType())
              && (fileIoSelector.pathPattern() == null
                  ? !JdkOwnUse.isRandomDevice(context.path())
                  : fileIoSelector.pathPattern().matches(context.path()))
              && inSizeRange(
                  fileIoSelector.minBytes(), fileIoSelector.maxBytes(), context.byteCount());
      case ChaosSelector.MessagingSelector messagingSelector ->
          messagingSelector.operations().contains(context.operationType())
              && anyDestinationMatches(
                  messagingSelector.destinationPattern(), context.targetName());
      case ChaosSelector.RpcSelector rpcSelector ->
          rpcSelector.operations().contains(context.operationType())
              && rpcSelector.methodPattern().matches(context.targetName());
      case ChaosSelector.RedisSelector redisSelector ->
          redisSelector.operations().contains(context.operationType())
              && redisSelector.commandPattern().matches(context.targetName());
      case ChaosSelector.AwsSdkSelector awsSdkSelector ->
          awsSdkSelector.operations().contains(context.operationType())
              && awsSdkSelector.servicePattern().matches(context.subjectClassName())
              && awsSdkSelector.operationPattern().matches(context.targetName());
      case ChaosSelector.NettySelector nettySelector ->
          nettySelector.operations().contains(context.operationType())
              && (nettySelector.remoteCidr() == null
                  || CidrBlock.cached(nettySelector.remoteCidr()).contains(context.remoteAddress()))
              && (nettySelector.remotePort() == null
                  || nettySelector.remotePort() == context.remotePort());
      case ChaosSelector.ProcessSelector processSelector ->
          processSelector.operations().contains(context.operationType())
              && processSelector.commandPattern().matches(context.targetName());
      case ChaosSelector.StressSelector stressSelector ->
          context.operationType() == OperationType.LIFECYCLE && stressSelector.target() != null;
    };
  }

  /**
   * Whether {@code pattern} matches every name: the JDK's own uses that L3a lists by class ({@link
   * JdkOwnUse}) are reached only by a pattern that names them.
   */
  private static boolean isAny(final NamePattern pattern) {
    return pattern.mode() == NamePattern.MatchMode.ANY;
  }

  /**
   * Returns {@code true} if the thread kind constraint of {@code selector} is satisfied by {@code
   * context}.
   *
   * <p>{@link ChaosSelector.ThreadKind#ANY} always matches. {@code PLATFORM} matches when {@link
   * InvocationContext#virtualThread()} is not {@link Boolean#TRUE}; {@code VIRTUAL} matches when it
   * is exactly {@link Boolean#TRUE}. A {@code null} virtualThread flag is treated as platform
   * (non-virtual).
   *
   * @param selector the thread selector whose {@code kind} field is evaluated
   * @param context the invocation context carrying the thread-kind flag
   * @return {@code true} if the thread-kind constraint passes
   */
  private static boolean matchesThreadKind(
      final ChaosSelector.ThreadSelector selector, final InvocationContext context) {
    return switch (selector.kind()) {
      case ANY -> true;
      case PLATFORM -> !Boolean.TRUE.equals(context.virtualThread());
      case VIRTUAL -> Boolean.TRUE.equals(context.virtualThread());
    };
  }

  /**
   * Returns {@code true} if the daemon-thread constraint of {@code selector} is satisfied by {@code
   * context}.
   *
   * <p>When {@link ChaosSelector.ThreadSelector#daemon()} is {@code null} the constraint is treated
   * as a wildcard and always passes. Otherwise the selector's value must equal {@link
   * InvocationContext#daemonThread()}.
   *
   * @param selector the thread selector whose {@code daemon} field is evaluated
   * @param context the invocation context carrying the daemon-thread flag
   * @return {@code true} if the daemon constraint passes or is absent
   */
  private static boolean matchesDaemon(
      final ChaosSelector.ThreadSelector selector, final InvocationContext context) {
    return selector.daemon() == null || selector.daemon().equals(context.daemonThread());
  }

  /**
   * A consumer poll reports all subscribed topics, comma-separated (Kafka topic names cannot
   * contain a comma); the pattern matches when it matches any of them.
   */
  private static boolean anyDestinationMatches(
      final NamePattern pattern, final @Nullable String destinations) {
    if (pattern.mode() == NamePattern.MatchMode.ANY) {
      return true;
    }
    if (destinations == null) {
      return false;
    }
    if (destinations.indexOf(',') < 0) {
      return pattern.matches(destinations);
    }
    for (final String destination : destinations.split(",", -1)) {
      if (pattern.matches(destination)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks a byte count against an inclusive range; an unknown count ({@code < 0}) never satisfies
   * a range.
   */
  private static boolean inSizeRange(
      final @Nullable Long minBytes, final @Nullable Long maxBytes, final long bytes) {
    if (minBytes == null && maxBytes == null) {
      return true;
    }
    return bytes >= 0
        && (minBytes == null || bytes >= minBytes)
        && (maxBytes == null || bytes <= maxBytes);
  }
}

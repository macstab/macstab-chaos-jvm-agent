package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetAddress;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Each filter of a selector rejects the call it does not describe, and only that call. */
@DisplayName("SelectorMatcher filters")
class SelectorMatcherFilterTest {

  private static InvocationContext call(
      final OperationType operation,
      final @Nullable String targetClass,
      final @Nullable String subjectClass,
      final @Nullable String targetName,
      final boolean periodic) {
    return new InvocationContext(
        operation, targetClass, subjectClass, targetName, periodic, false, false, null);
  }

  private static InvocationContext peer(
      final OperationType operation,
      final long byteCount,
      final InetAddress address,
      final int port) {
    return new InvocationContext(
        operation,
        "java.net.Socket",
        null,
        "db.internal",
        false,
        false,
        false,
        null,
        byteCount,
        null,
        address,
        port,
        null,
        InvocationContext.UNKNOWN);
  }

  private static InetAddress address(final String literal) throws Exception {
    return InetAddress.getByName(literal);
  }

  @Nested
  @DisplayName("ExecutorSelector scheduledOnly")
  class ScheduledOnly {

    @Test
    @DisplayName("false matches a one-shot task")
    void falseMatchesOneShot() {
      final ChaosSelector selector =
          new ChaosSelector.ExecutorSelector(
              Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any(), NamePattern.any(), false);
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.EXECUTOR_SUBMIT, "Pool", "Task", null, false)))
          .isTrue();
    }

    @Test
    @DisplayName("true matches a periodic task and rejects a one-shot task")
    void trueMatchesOnlyPeriodic() {
      final ChaosSelector selector =
          new ChaosSelector.ExecutorSelector(
              Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any(), NamePattern.any(), true);
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.EXECUTOR_SUBMIT, "Pool", "Task", null, true)))
          .isTrue();
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.EXECUTOR_SUBMIT, "Pool", "Task", null, false)))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("an operation outside the selector's set is rejected")
  class OperationMismatch {

    @Test
    @DisplayName("SchedulingSelector")
    void scheduling() {
      final ChaosSelector selector =
          new ChaosSelector.SchedulingSelector(
              Set.of(OperationType.SCHEDULE_SUBMIT), NamePattern.any(), null);
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.SCHEDULE_TICK, "Pool", null, null, true)))
          .isFalse();
    }

    @Test
    @DisplayName("ClassLoadingSelector")
    void classLoading() {
      final ChaosSelector selector =
          new ChaosSelector.ClassLoadingSelector(
              Set.of(OperationType.CLASS_LOAD), NamePattern.any(), NamePattern.any());
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.RESOURCE_LOAD, "Loader", null, "a.txt", false)))
          .isFalse();
    }

    @Test
    @DisplayName("MethodSelector")
    void method() {
      final ChaosSelector selector =
          new ChaosSelector.MethodSelector(
              Set.of(OperationType.METHOD_ENTER), NamePattern.exact("com.x.A"), null, null);
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.METHOD_EXIT, "com.x.A", null, "run", false)))
          .isFalse();
    }

    @Test
    @DisplayName("MessagingSelector")
    void messaging() {
      final ChaosSelector selector =
          new ChaosSelector.MessagingSelector(
              Set.of(OperationType.MESSAGE_SEND), NamePattern.any());
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.MESSAGE_RECEIVE, null, null, "orders", false)))
          .isFalse();
    }

    @Test
    @DisplayName("RpcSelector")
    void rpc() {
      final ChaosSelector selector =
          new ChaosSelector.RpcSelector(Set.of(OperationType.RPC_CALL), NamePattern.any());
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.REDIS_COMMAND, null, null, "GET", false)))
          .isFalse();
    }

    @Test
    @DisplayName("RedisSelector")
    void redis() {
      final ChaosSelector selector =
          new ChaosSelector.RedisSelector(Set.of(OperationType.REDIS_COMMAND), NamePattern.any());
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.RPC_CALL, null, null, "svc/Get", false)))
          .isFalse();
    }

    @Test
    @DisplayName("ProcessSelector")
    void process() {
      final ChaosSelector selector =
          new ChaosSelector.ProcessSelector(Set.of(OperationType.PROCESS_START), NamePattern.any());
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.PROCESS_WAIT, null, null, "ls", false)))
          .isFalse();
    }

    @Test
    @DisplayName("NettySelector")
    void netty() throws Exception {
      final ChaosSelector selector =
          new ChaosSelector.NettySelector(Set.of(OperationType.NETTY_CHANNEL_WRITE), null, null);
      assertThat(
              SelectorMatcher.matches(
                  selector, peer(OperationType.NETTY_CHANNEL_READ, 1, address("10.0.0.1"), 80)))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("AwsSdkSelector")
  class AwsSdk {

    private final ChaosSelector selector =
        new ChaosSelector.AwsSdkSelector(
            Set.of(OperationType.AWS_SDK_CALL),
            NamePattern.exact("S3"),
            NamePattern.exact("PutObject"));

    @Test
    @DisplayName("matches the service and operation it names")
    void matchesServiceAndOperation() {
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.AWS_SDK_CALL, null, "S3", "PutObject", false)))
          .isTrue();
    }

    @Test
    @DisplayName("rejects a call that is not an AWS SDK call")
    void rejectsOtherOperationType() {
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.RPC_CALL, null, "S3", "PutObject", false)))
          .isFalse();
    }

    @Test
    @DisplayName("rejects another operation of the service")
    void rejectsOtherOperation() {
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.AWS_SDK_CALL, null, "S3", "GetObject", false)))
          .isFalse();
    }

    @Test
    @DisplayName("rejects the operation on another service")
    void rejectsOtherService() {
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.AWS_SDK_CALL, null, "SQS", "PutObject", false)))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("NettySelector peer filters")
  class NettyPeer {

    private final ChaosSelector selector =
        new ChaosSelector.NettySelector(
            Set.of(OperationType.NETTY_CHANNEL_WRITE), "10.0.0.0/8", 443);

    @Test
    @DisplayName("matches a peer inside the CIDR on the port")
    void matchesPeer() throws Exception {
      assertThat(
              SelectorMatcher.matches(
                  selector, peer(OperationType.NETTY_CHANNEL_WRITE, 1, address("10.1.2.3"), 443)))
          .isTrue();
    }

    @Test
    @DisplayName("rejects a peer outside the CIDR")
    void rejectsOutsideCidr() throws Exception {
      assertThat(
              SelectorMatcher.matches(
                  selector,
                  peer(OperationType.NETTY_CHANNEL_WRITE, 1, address("192.168.0.1"), 443)))
          .isFalse();
    }

    @Test
    @DisplayName("rejects a peer on another port")
    void rejectsOtherPort() throws Exception {
      assertThat(
              SelectorMatcher.matches(
                  selector, peer(OperationType.NETTY_CHANNEL_WRITE, 1, address("10.1.2.3"), 80)))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("MessagingSelector destinations")
  class Destinations {

    @Test
    @DisplayName("a named destination never matches a call that reports none")
    void namedPatternRejectsMissingDestination() {
      final ChaosSelector selector =
          new ChaosSelector.MessagingSelector(
              Set.of(OperationType.MESSAGE_SEND), NamePattern.exact("orders"));
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.MESSAGE_SEND, null, null, null, false)))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("StressSelector")
  class Stress {

    @Test
    @DisplayName("matches only the lifecycle operation")
    void matchesOnlyLifecycle() {
      final ChaosSelector selector =
          new ChaosSelector.StressSelector(ChaosSelector.StressTarget.HEAP);
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.LIFECYCLE, null, null, null, false)))
          .isTrue();
      assertThat(
              SelectorMatcher.matches(
                  selector, call(OperationType.THREAD_START, null, null, null, false)))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("byte-count range")
  class SizeRange {

    private ChaosSelector range(final @Nullable Long min, final @Nullable Long max) {
      return new ChaosSelector.NetworkSelector(
          Set.of(OperationType.SOCKET_WRITE), NamePattern.any(), null, null, min, max);
    }

    private boolean matches(final ChaosSelector selector, final long bytes) throws Exception {
      return SelectorMatcher.matches(
          selector, peer(OperationType.SOCKET_WRITE, bytes, address("10.0.0.1"), 5432));
    }

    @Test
    @DisplayName("a minimum alone rejects a smaller write and accepts a larger one")
    void minimumOnly() throws Exception {
      final ChaosSelector selector = range(10L, null);
      assertThat(matches(selector, 5)).isFalse();
      assertThat(matches(selector, 50)).isTrue();
    }

    @Test
    @DisplayName("a maximum alone accepts a smaller write and rejects a larger one")
    void maximumOnly() throws Exception {
      final ChaosSelector selector = range(null, 10L);
      assertThat(matches(selector, 5)).isTrue();
      assertThat(matches(selector, 50)).isFalse();
    }

    @Test
    @DisplayName("an unknown byte count never satisfies a range")
    void unknownCount() throws Exception {
      assertThat(matches(range(null, 10L), InvocationContext.UNKNOWN)).isFalse();
    }
  }
}

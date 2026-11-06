package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The selector factories' results, and the rule every optional pattern follows: a given pattern is
 * kept, a missing one widens to {@link NamePattern#any()}.
 */
@DisplayName("ChaosSelector factories and pattern defaults")
class ChaosSelectorFactoriesTest {

  private static final NamePattern GIVEN = NamePattern.exact("given");
  private static final NamePattern OTHER = NamePattern.prefix("other");
  private static final NamePattern ANY = NamePattern.any();

  @Nested
  @DisplayName("factories")
  class Factories {

    @Test
    @DisplayName("the one-argument forms match any name")
    void oneArgumentFormsMatchAny() {
      assertThat(ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_READ)))
          .isEqualTo(new ChaosSelector.NioSelector(Set.of(OperationType.NIO_CHANNEL_READ), ANY));
      assertThat(ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)))
          .isEqualTo(new ChaosSelector.NetworkSelector(Set.of(OperationType.SOCKET_CONNECT), ANY));
      assertThat(ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_GET)))
          .isEqualTo(
              new ChaosSelector.ThreadLocalSelector(Set.of(OperationType.THREAD_LOCAL_GET), ANY));
      assertThat(ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)))
          .isEqualTo(
              new ChaosSelector.HttpClientSelector(Set.of(OperationType.HTTP_CLIENT_SEND), ANY));
      assertThat(ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)))
          .isEqualTo(new ChaosSelector.DnsSelector(Set.of(OperationType.DNS_RESOLVE), ANY));
    }

    @Test
    @DisplayName("the two-argument forms keep the pattern")
    void twoArgumentFormsKeepThePattern() {
      assertThat(ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_READ), GIVEN))
          .isEqualTo(new ChaosSelector.NioSelector(Set.of(OperationType.NIO_CHANNEL_READ), GIVEN));
      assertThat(ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT), GIVEN))
          .isEqualTo(
              new ChaosSelector.NetworkSelector(Set.of(OperationType.SOCKET_CONNECT), GIVEN));
      assertThat(ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_SET), GIVEN))
          .isEqualTo(
              new ChaosSelector.ThreadLocalSelector(Set.of(OperationType.THREAD_LOCAL_SET), GIVEN));
      assertThat(ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND), GIVEN))
          .isEqualTo(
              new ChaosSelector.HttpClientSelector(Set.of(OperationType.HTTP_CLIENT_SEND), GIVEN));
      assertThat(ChaosSelector.dns(Set.of(OperationType.DNS_REVERSE), GIVEN))
          .isEqualTo(new ChaosSelector.DnsSelector(Set.of(OperationType.DNS_REVERSE), GIVEN));
      assertThat(ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), null).hostnamePattern())
          .as("no pattern matches any host")
          .isEqualTo(NamePattern.any());
      assertThat(ChaosSelector.messaging(Set.of(OperationType.MESSAGE_SEND), GIVEN))
          .isEqualTo(
              new ChaosSelector.MessagingSelector(Set.of(OperationType.MESSAGE_SEND), GIVEN));
    }

    @Test
    @DisplayName("the single-operation selectors take their one operation")
    void singleOperationSelectors() {
      assertThat(ChaosSelector.rpc(GIVEN))
          .isEqualTo(new ChaosSelector.RpcSelector(Set.of(OperationType.RPC_CALL), GIVEN));
      assertThat(ChaosSelector.redis(GIVEN))
          .isEqualTo(new ChaosSelector.RedisSelector(Set.of(OperationType.REDIS_COMMAND), GIVEN));
      assertThat(ChaosSelector.awsSdk(GIVEN, OTHER))
          .isEqualTo(
              new ChaosSelector.AwsSdkSelector(Set.of(OperationType.AWS_SDK_CALL), GIVEN, OTHER));
      assertThat(ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)).operations())
          .containsExactly(OperationType.SSL_HANDSHAKE);
      assertThat(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)))
          .isEqualTo(new ChaosSelector.FileIoSelector(Set.of(OperationType.FILE_IO_READ)));
    }

    @Test
    @DisplayName("jdbc() selects every JDBC operation but R2DBC, against any target")
    void jdbcDefaults() {
      final ChaosSelector.JdbcSelector selector = ChaosSelector.jdbc();

      assertThat(selector.operations())
          .containsExactlyInAnyOrder(
              OperationType.JDBC_CONNECTION_ACQUIRE,
              OperationType.JDBC_STATEMENT_EXECUTE,
              OperationType.JDBC_PREPARED_STATEMENT,
              OperationType.JDBC_TRANSACTION_COMMIT,
              OperationType.JDBC_TRANSACTION_ROLLBACK);
      assertThat(selector.targetPattern()).isEqualTo(ANY);
    }

    @Test
    @DisplayName("jdbc(operations) selects exactly the given operations and needs at least one")
    void jdbcOperations() {
      assertThat(
              ChaosSelector.jdbc(
                      OperationType.JDBC_TRANSACTION_COMMIT, OperationType.R2DBC_STATEMENT_EXECUTE)
                  .operations())
          .containsExactlyInAnyOrder(
              OperationType.JDBC_TRANSACTION_COMMIT, OperationType.R2DBC_STATEMENT_EXECUTE);
      assertThatThrownBy(() -> ChaosSelector.jdbc(new OperationType[0]))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("operations must not be empty");
      assertThatThrownBy(() -> ChaosSelector.jdbc((OperationType[]) null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("operations must not be empty");
    }
  }

  @Nested
  @DisplayName("optional patterns")
  class OptionalPatterns {

    @Test
    @DisplayName("thread: name pattern and kind default, given ones are kept")
    void thread() {
      final Set<OperationType> ops = Set.of(OperationType.THREAD_START);
      final ChaosSelector.ThreadSelector defaults =
          new ChaosSelector.ThreadSelector(ops, null, null, null);
      final ChaosSelector.ThreadSelector given =
          new ChaosSelector.ThreadSelector(ops, ChaosSelector.ThreadKind.VIRTUAL, GIVEN, true);

      assertThat(defaults.kind()).isEqualTo(ChaosSelector.ThreadKind.ANY);
      assertThat(defaults.threadNamePattern()).isEqualTo(ANY);
      assertThat(given.kind()).isEqualTo(ChaosSelector.ThreadKind.VIRTUAL);
      assertThat(given.threadNamePattern()).isEqualTo(GIVEN);
    }

    @Test
    @DisplayName("executor: executor and task patterns")
    void executor() {
      final Set<OperationType> ops = Set.of(OperationType.EXECUTOR_SUBMIT);
      final ChaosSelector.ExecutorSelector defaults =
          new ChaosSelector.ExecutorSelector(ops, null, null, null);
      final ChaosSelector.ExecutorSelector given =
          new ChaosSelector.ExecutorSelector(ops, GIVEN, OTHER, true);

      assertThat(defaults.executorClassPattern()).isEqualTo(ANY);
      assertThat(defaults.taskClassPattern()).isEqualTo(ANY);
      assertThat(given.executorClassPattern()).isEqualTo(GIVEN);
      assertThat(given.taskClassPattern()).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("class loading: target and loader patterns")
    void classLoading() {
      final Set<OperationType> ops = Set.of(OperationType.CLASS_LOAD);
      final ChaosSelector.ClassLoadingSelector defaults =
          new ChaosSelector.ClassLoadingSelector(ops, null, null);
      final ChaosSelector.ClassLoadingSelector given =
          new ChaosSelector.ClassLoadingSelector(ops, GIVEN, OTHER);

      assertThat(defaults.targetNamePattern()).isEqualTo(ANY);
      assertThat(defaults.loaderClassPattern()).isEqualTo(ANY);
      assertThat(given.targetNamePattern()).isEqualTo(GIVEN);
      assertThat(given.loaderClassPattern()).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("method: the signature pattern")
    void method() {
      final Set<OperationType> ops = Set.of(OperationType.METHOD_ENTER);

      assertThat(new ChaosSelector.MethodSelector(ops, GIVEN, null, null).signaturePattern())
          .isEqualTo(ANY);
      assertThat(new ChaosSelector.MethodSelector(ops, GIVEN, null, OTHER).signaturePattern())
          .isEqualTo(OTHER);
    }

    @Test
    @DisplayName("aws sdk: service and operation patterns")
    void awsSdk() {
      final Set<OperationType> ops = Set.of(OperationType.AWS_SDK_CALL);
      final ChaosSelector.AwsSdkSelector defaults =
          new ChaosSelector.AwsSdkSelector(ops, null, null);

      assertThat(defaults.servicePattern()).isEqualTo(ANY);
      assertThat(defaults.operationPattern()).isEqualTo(ANY);
    }

    @Test
    @DisplayName("every single-pattern selector")
    void singlePattern() {
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.QueueSelector(Set.of(OperationType.QUEUE_PUT), p),
          ChaosSelector.QueueSelector::queueClassPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.SchedulingSelector(Set.of(OperationType.SCHEDULE_TICK), p, null),
          ChaosSelector.SchedulingSelector::executorClassPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.ShutdownSelector(Set.of(OperationType.EXECUTOR_SHUTDOWN), p),
          ChaosSelector.ShutdownSelector::targetClassPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.MonitorSelector(Set.of(OperationType.MONITOR_ENTER), p),
          ChaosSelector.MonitorSelector::monitorClassPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.NioSelector(Set.of(OperationType.NIO_SELECTOR_SELECT), p),
          ChaosSelector.NioSelector::channelClassPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.NetworkSelector(Set.of(OperationType.SOCKET_READ), p),
          ChaosSelector.NetworkSelector::remoteHostPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.ThreadLocalSelector(Set.of(OperationType.THREAD_LOCAL_GET), p),
          ChaosSelector.ThreadLocalSelector::threadLocalClassPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.HttpClientSelector(Set.of(OperationType.HTTP_CLIENT_SEND), p),
          ChaosSelector.HttpClientSelector::urlPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.JdbcSelector(Set.of(OperationType.JDBC_STATEMENT_EXECUTE), p),
          ChaosSelector.JdbcSelector::targetPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.DnsSelector(Set.of(OperationType.DNS_ANSWER), p),
          ChaosSelector.DnsSelector::hostnamePattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.MessagingSelector(Set.of(OperationType.MESSAGE_RECEIVE), p),
          ChaosSelector.MessagingSelector::destinationPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.RpcSelector(Set.of(OperationType.RPC_CALL), p),
          ChaosSelector.RpcSelector::methodPattern);
      assertDefaultsAndKeeps(
          p -> new ChaosSelector.RedisSelector(Set.of(OperationType.REDIS_COMMAND), p),
          ChaosSelector.RedisSelector::commandPattern);
    }

    private <S> void assertDefaultsAndKeeps(
        final java.util.function.Function<NamePattern, S> create,
        final java.util.function.Function<S, NamePattern> pattern) {
      assertThat(pattern.apply(create.apply(null))).isEqualTo(ANY);
      assertThat(pattern.apply(create.apply(GIVEN))).isEqualTo(GIVEN);
    }
  }

  @Nested
  @DisplayName("NettySelector")
  class Netty {

    private static final Set<OperationType> READS = Set.of(OperationType.NETTY_CHANNEL_READ);

    @Test
    @DisplayName("ports 0 to 65535 and any peer are accepted")
    void acceptedPeers() {
      assertThatCode(
              () -> {
                new ChaosSelector.NettySelector(READS, null, null);
                new ChaosSelector.NettySelector(READS, "10.0.0.0/8", 0);
                new ChaosSelector.NettySelector(READS, "::1", 65_535);
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a port outside 0..65535 is rejected")
    void portOutOfRange() {
      assertThatThrownBy(() -> new ChaosSelector.NettySelector(READS, null, -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("remotePort must be in [0, 65535], got -1");
      assertThatThrownBy(() -> new ChaosSelector.NettySelector(READS, null, 65_536))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("remotePort must be in [0, 65535], got 65536");
    }

    @Test
    @DisplayName("a peer that is not a CIDR block is rejected")
    void badCidr() {
      assertThatThrownBy(() -> new ChaosSelector.NettySelector(READS, "db.local", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not an IPv4 or IPv6 literal");
    }
  }
}

package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

/**
 * Advice for client libraries: Kafka, gRPC, Jedis, Lettuce, R2DBC, the AWS SDK for Java v2 and
 * Netty. Each reads what its selector matches (see {@link ClientCallExtractor}) and reports the
 * call through {@link BootstrapDispatcher#beforeClientCall}; a reject throws the library's own
 * exception. The libraries are matched by name and never loaded by the agent.
 */
final class ClientCallAdvice {
  private ClientCallAdvice() {}

  /** {@code KafkaProducer.send(ProducerRecord, Callback)}. */
  static final class KafkaSend {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object producer, @Advice.Argument(0) final Object record)
        throws Throwable {
      final String topic;
      BootstrapDispatcher.beginInternalCall();
      try {
        topic = ClientCallExtractor.kafkaTopic(record);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall("MESSAGE_SEND", producer, topic, null, null);
    }
  }

  /** {@code KafkaConsumer.poll(Duration)}. */
  static final class KafkaPoll {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object consumer) throws Throwable {
      final String topics;
      BootstrapDispatcher.beginInternalCall();
      try {
        topics = ClientCallExtractor.kafkaSubscription(consumer);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall("MESSAGE_RECEIVE", consumer, topics, null, null);
    }
  }

  /** {@code io.grpc.internal.ClientCallImpl.start(Listener, Metadata)}. */
  static final class GrpcStart {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object call, @Advice.FieldValue("method") final Object method)
        throws Throwable {
      final String fullMethodName;
      BootstrapDispatcher.beginInternalCall();
      try {
        fullMethodName = ClientCallExtractor.grpcMethod(method);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall("RPC_CALL", call, fullMethodName, null, null);
    }
  }

  /** Jedis {@code Connection.sendCommand(CommandArguments)}: every command funnels through it. */
  static final class JedisSendCommand {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object connection, @Advice.Argument(0) final Object arguments)
        throws Throwable {
      final String command;
      BootstrapDispatcher.beginInternalCall();
      try {
        command = ClientCallExtractor.jedisCommand(arguments);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall("REDIS_COMMAND", connection, command, null, null);
    }
  }

  /** Lettuce {@code RedisChannelHandler.dispatch(RedisCommand)}. */
  static final class LettuceDispatch {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object connection, @Advice.Argument(0) final Object redisCommand)
        throws Throwable {
      final String command;
      BootstrapDispatcher.beginInternalCall();
      try {
        command = ClientCallExtractor.lettuceCommand(redisCommand);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall("REDIS_COMMAND", connection, command, null, null);
    }
  }

  /**
   * {@code io.r2dbc.spi.Statement.execute()} in any driver. Pool and proxy statements delegate to
   * the driver's statement, so only the outermost {@code execute()} is evaluated.
   */
  static final class R2dbcExecute {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object statement) throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLIENT)) {
        return;
      }
      try {
        BootstrapDispatcher.beforeClientCall(
            "R2DBC_STATEMENT_EXECUTE", statement, null, null, null);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLIENT);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLIENT);
    }
  }

  /**
   * AWS SDK v2 {@code BaseSyncClientHandler} / {@code BaseAsyncClientHandler.execute(params...)}.
   */
  static final class AwsExecute {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object handler, @Advice.Argument(0) final Object executionParams)
        throws Throwable {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLIENT)) {
        return;
      }
      try {
        final String operation;
        final String service;
        BootstrapDispatcher.beginInternalCall();
        try {
          operation = ClientCallExtractor.awsOperation(executionParams);
          service = ClientCallExtractor.awsService(executionParams);
        } finally {
          BootstrapDispatcher.endInternalCall();
        }
        BootstrapDispatcher.beforeClientCall("AWS_SDK_CALL", handler, operation, service, null);
      } catch (final Throwable throwable) {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLIENT);
        throw throwable;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLIENT);
    }
  }

  /** Netty {@code AbstractChannel.AbstractUnsafe.flush0()}, every transport. */
  static final class NettyFlush {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object unsafe) throws Throwable {
      final Object remote;
      BootstrapDispatcher.beginInternalCall();
      try {
        remote = ClientCallExtractor.nettyRemoteAddress(unsafe);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall("NETTY_CHANNEL_WRITE", unsafe, null, null, remote);
    }
  }

  /** Netty stream-channel read loops (NIO, epoll, kqueue). */
  static final class NettyRead {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object unsafe) throws Throwable {
      final Object remote;
      BootstrapDispatcher.beginInternalCall();
      try {
        remote = ClientCallExtractor.nettyRemoteAddress(unsafe);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall("NETTY_CHANNEL_READ", unsafe, null, null, remote);
    }
  }
}

package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.jspecify.annotations.Nullable;

/**
 * Advice for client libraries: Kafka, gRPC, Jedis, Lettuce, R2DBC, the AWS SDK for Java v2 and
 * Netty. Each reads what its selector matches (see {@link ClientCallExtractor}) and reports the
 * call through {@link BootstrapDispatcher#beforeClientCall}; a reject throws the library's own
 * exception. The libraries are matched by name and never loaded by the agent.
 */
final class ClientCallAdvice {
  private ClientCallAdvice() {}

  /**
   * {@code KafkaProducer.send(ProducerRecord, Callback)}, scope-only ({@link
   * BootstrapDispatcher#SCOPE_MESSAGE_SEND}): the send is evaluated at its metadata wait ({@link
   * KafkaWaitOnMetadata}), inside {@code doSend}'s own error handling.
   */
  static final class KafkaSend {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_MESSAGE_SEND);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_MESSAGE_SEND);
    }
  }

  /**
   * {@code KafkaProducer.waitOnMetadata(String topic, Integer, long, long)}, evaluated only inside
   * a send ({@link KafkaSend}), not for {@code partitionsFor}. A reject throws Kafka's {@code
   * TimeoutException} where Kafka's own metadata timeout is thrown: {@code doSend} hands it to the
   * callback and the interceptors and returns a failed future, as for a real timeout.
   */
  static final class KafkaWaitOnMetadata {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object producer, @Advice.Argument(0) final String topic)
        throws Throwable {
      if (!BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_MESSAGE_SEND)) {
        return;
      }
      BootstrapDispatcher.beforeClientCall(OperationTags.MESSAGE_SEND, producer, topic, null, null);
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
      BootstrapDispatcher.beforeClientCall(
          OperationTags.MESSAGE_RECEIVE, consumer, topics, null, null);
    }
  }

  /**
   * {@code newStream(MethodDescriptor, CallOptions, Metadata, Context)} of gRPC's {@code
   * ClientCallImpl.ClientStreamProvider} implementations: once per call, when the call is about to
   * open its stream (later, inside the channel's synchronization context, for a call started before
   * the name resolved). A reject skips the real {@code newStream}, so no transport stream is
   * opened, and returns gRPC's own {@code FailingClientStream}: the call fails through its listener
   * with the status. Only when that stream cannot be built is the status thrown from {@code
   * newStream}.
   */
  static final class GrpcNewStream {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Throwable enter(
        @Advice.This final Object provider, @Advice.Argument(0) final Object method) {
      final String fullMethodName;
      BootstrapDispatcher.beginInternalCall();
      try {
        fullMethodName = ClientCallExtractor.grpcMethod(method);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      try {
        BootstrapDispatcher.beforeClientCall(
            OperationTags.RPC_CALL, provider, fullMethodName, null, null);
        return null;
      } catch (final Throwable rejected) {
        return rejected;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final @Nullable Throwable rejected,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object stream)
        throws Throwable {
      if (rejected != null) {
        final Object failing;
        BootstrapDispatcher.beginInternalCall();
        try {
          failing = ClientCallExtractor.grpcFailingStream(rejected);
        } finally {
          BootstrapDispatcher.endInternalCall();
        }
        if (failing == null) {
          throw rejected;
        }
        stream = failing;
      }
    }
  }

  /**
   * Jedis {@code Connection.sendCommand(CommandArguments)}: every command funnels through it. The
   * commands of the client's own set-up and pool validation ({@link JedisSetup}) are skipped.
   */
  static final class JedisSendCommand {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object connection, @Advice.Argument(0) final Object arguments)
        throws Throwable {
      if (BootstrapDispatcher.inScope(BootstrapDispatcher.SCOPE_REDIS_SETUP)) {
        return;
      }
      final String command;
      BootstrapDispatcher.beginInternalCall();
      try {
        command = ClientCallExtractor.jedisCommand(arguments);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall(
          OperationTags.REDIS_COMMAND, connection, command, null, null);
    }
  }

  /**
   * Jedis set-up and pool validation: {@code Connection.initializeFromClientConfig} (both
   * overloads) and {@code ping()}, and {@code validateObject} / {@code activateObject} of {@code
   * ConnectionFactory} and {@code JedisFactory}. Scope-only ({@link
   * BootstrapDispatcher#SCOPE_REDIS_SETUP}): the {@code HELLO}, {@code AUTH}, {@code CLIENT
   * SETINFO}, {@code SELECT} and {@code PING} they send are not the application's commands.
   */
  static final class JedisSetup {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_REDIS_SETUP);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_REDIS_SETUP);
    }
  }

  /**
   * Lettuce {@code RedisChannelHandler.dispatch(RedisCommand)} and {@code dispatch(Collection)} (a
   * batch, evaluated once, by its first command's name). A reject skips the dispatch and completes
   * each command exceptionally, as Lettuce does for a command it cannot write: the asynchronous
   * API's futures fail, the synchronous API throws.
   */
  static final class LettuceDispatch {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Throwable enter(
        @Advice.This final Object connection, @Advice.Argument(0) final Object commands) {
      final String command;
      BootstrapDispatcher.beginInternalCall();
      try {
        command = ClientCallExtractor.lettuceCommand(commands);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      try {
        BootstrapDispatcher.beforeClientCall(
            OperationTags.REDIS_COMMAND, connection, command, null, null);
        return null;
      } catch (final Throwable rejected) {
        return rejected;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final @Nullable Throwable rejected,
        @Advice.Argument(0) final Object commands,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object dispatched) {
      if (rejected != null) {
        BootstrapDispatcher.beginInternalCall();
        try {
          ClientCallExtractor.lettuceFail(commands, rejected);
        } finally {
          BootstrapDispatcher.endInternalCall();
        }
        dispatched = commands;
      }
    }
  }

  /**
   * {@code io.r2dbc.spi.Statement.execute()} in any driver. Pool and proxy statements delegate to
   * the driver's statement, so only the outermost {@code execute()} is evaluated. A reject skips
   * the execution and returns a publisher that signals the exception ({@code Mono.error} or {@code
   * Flux.error}, as the driver's {@code execute()} declares), as a driver fails an execution; only
   * when Reactor is not visible to the statement's class loader is it thrown from {@code
   * execute()}.
   */
  static final class R2dbcExecute {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static @Nullable Throwable enter(@Advice.This final Object statement) {
      if (!BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLIENT)) {
        return null;
      }
      try {
        BootstrapDispatcher.beforeClientCall(
            OperationTags.R2DBC_STATEMENT_EXECUTE, statement, null, null, null);
        return null;
      } catch (final Throwable rejected) {
        return rejected;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final @Nullable Throwable rejected,
        @Advice.This final Object statement,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object results)
        throws Throwable {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLIENT);
      if (rejected != null) {
        final Object failing;
        BootstrapDispatcher.beginInternalCall();
        try {
          failing = ClientCallExtractor.r2dbcFailingPublisher(statement, rejected);
        } finally {
          BootstrapDispatcher.endInternalCall();
        }
        if (failing == null) {
          throw rejected;
        }
        results = failing;
      }
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
        BootstrapDispatcher.beforeClientCall(
            OperationTags.AWS_SDK_CALL, handler, operation, service, null);
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
   * Netty {@code doWrite(ChannelOutboundBuffer)} of every channel type that declares it, every
   * transport. {@code AbstractUnsafe.flush0()} calls it only for a non-empty flushed buffer and
   * catches what it throws into {@code handleWriteError}: a reject fails the flushed writes'
   * futures with the {@code IOException} and closes the channel, as a real write error does. A
   * buffer the socket takes in several rounds ({@code OP_WRITE}) is evaluated once per round.
   */
  static final class NettyWrite {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object channel) throws Throwable {
      final Object remote;
      BootstrapDispatcher.beginInternalCall();
      try {
        remote = ClientCallExtractor.nettyChannelRemoteAddress(channel);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.beforeClientCall(
          OperationTags.NETTY_CHANNEL_WRITE, channel, null, null, remote);
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
      BootstrapDispatcher.beforeClientCall(
          OperationTags.NETTY_CHANNEL_READ, unsafe, null, null, remote);
    }
  }
}

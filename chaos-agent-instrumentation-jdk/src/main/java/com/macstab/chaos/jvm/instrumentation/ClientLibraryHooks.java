package com.macstab.chaos.jvm.instrumentation;

import static com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.advise;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The hook groups for client libraries: Kafka, gRPC, Redis, the AWS SDK and Netty: which methods of
 * which types receive which advice. {@link JdkInstrumentationInstaller.HookGroup} weaves them on
 * demand.
 */
final class ClientLibraryHooks {
  private ClientLibraryHooks() {}

  /**
   * Kafka producer send (the scope of {@code send(record, callback)}, evaluated at its private
   * {@code waitOnMetadata}, 3.9 and 4.x) and consumer poll.
   */
  static AgentBuilder applyKafkaTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("org.apache.kafka.clients.producer.KafkaProducer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ClientCallAdvice.KafkaSend.class,
                            ElementMatchers.named("send").and(ElementMatchers.takesArguments(2))))
                    .visit(
                        advise(
                            ClientCallAdvice.KafkaWaitOnMetadata.class,
                            ElementMatchers.named("waitOnMetadata")
                                .and(ElementMatchers.takesArguments(4))
                                .and(ElementMatchers.takesArgument(0, String.class)))))
        .type(ElementMatchers.named("org.apache.kafka.clients.consumer.KafkaConsumer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.KafkaPoll.class,
                        ElementMatchers.named("poll")
                            .and(ElementMatchers.takesArguments(java.time.Duration.class)))));
  }

  /**
   * gRPC client stream creation, once per call for every stub flavour and transport: {@code
   * newStream} of the {@code ClientCallImpl.ClientStreamProvider} implementations (1.85: {@code
   * ManagedChannelImpl.ChannelStreamProvider}, {@code SubchannelChannel}'s). The provider is an
   * interface, so its implementations are matched; the {@code io.grpc.} prefix keeps the hierarchy
   * walk off every other class.
   */
  static AgentBuilder applyGrpcTransformations(final AgentBuilder builder) {
    return builder
        .type(
            ElementMatchers.nameStartsWith("io.grpc.")
                .and(
                    TypeMatchers.superType(
                        ElementMatchers.named(
                            "io.grpc.internal.ClientCallImpl$ClientStreamProvider")))
                .and(ElementMatchers.not(ElementMatchers.isInterface())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.GrpcNewStream.class,
                        ElementMatchers.named("newStream")
                            .and(ElementMatchers.takesArguments(4))
                            .and(ElementMatchers.not(ElementMatchers.isAbstract())))));
  }

  /** Jedis and Lettuce command dispatch. */
  static AgentBuilder applyRedisTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("redis.clients.jedis.Connection"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ClientCallAdvice.JedisSendCommand.class,
                            ElementMatchers.named("sendCommand")
                                .and(
                                    ElementMatchers.takesArguments(1)
                                        .and(
                                            ElementMatchers.takesArgument(
                                                0,
                                                ElementMatchers.named(
                                                    "redis.clients.jedis.CommandArguments"))))))
                    // The connection's own set-up and liveness check, not application commands.
                    .visit(
                        advise(
                            ClientCallAdvice.JedisSetup.class,
                            ElementMatchers.named("initializeFromClientConfig")
                                .or(
                                    ElementMatchers.named("ping")
                                        .and(ElementMatchers.takesArguments(0))))))
        // Pool validation and activation of a borrowed connection (PING, SELECT).
        .type(
            ElementMatchers.namedOneOf(
                "redis.clients.jedis.ConnectionFactory", "redis.clients.jedis.JedisFactory"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.JedisSetup.class,
                        ElementMatchers.namedOneOf("validateObject", "activateObject")
                            .and(ElementMatchers.takesArguments(1)))))
        .type(ElementMatchers.named("io.lettuce.core.RedisChannelHandler"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.LettuceDispatch.class,
                        ElementMatchers.named("dispatch")
                            .and(ElementMatchers.takesArguments(1))
                            .and(
                                ElementMatchers.takesArgument(
                                        0,
                                        ElementMatchers.named(
                                            "io.lettuce.core.protocol.RedisCommand"))
                                    .or(
                                        ElementMatchers.takesArgument(
                                            0, java.util.Collection.class))))));
  }

  /** AWS SDK v2 sync and async client handlers. */
  static AgentBuilder applyAwsSdkTransformations(final AgentBuilder builder) {
    return builder
        .type(
            ElementMatchers.namedOneOf(
                "software.amazon.awssdk.core.internal.handler.BaseSyncClientHandler",
                "software.amazon.awssdk.core.internal.handler.BaseAsyncClientHandler"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.AwsExecute.class,
                        ElementMatchers.named("execute")
                            .and(ElementMatchers.isPublic())
                            .and(
                                ElementMatchers.takesArgument(
                                    0,
                                    ElementMatchers.named(
                                        "software.amazon.awssdk.core.client.handler"
                                            + ".ClientExecutionParams"))))));
  }

  /**
   * Netty channel writes (every transport) and stream-channel reads (NIO, epoll, kqueue). The write
   * hook is {@code doWrite(ChannelOutboundBuffer)} on every {@code AbstractChannel} subtype that
   * declares it, abstract or not (4.2: {@code AbstractNioByteChannel}, {@code
   * AbstractNioMessageChannel}, {@code NioSocketChannel}, {@code LocalChannel}, ...; {@code
   * NioDatagramChannel} inherits it): no override calls {@code super.doWrite}, so a write is
   * evaluated once. The {@code io.netty.} prefix keeps the superclass walk off every other class.
   * The advice skips a datagram channel at run time, because the NIO and io_uring datagram channels
   * share their {@code doWrite} with other channel types.
   */
  static AgentBuilder applyNettyTransformations(final AgentBuilder builder) {
    return builder
        .type(
            ElementMatchers.nameStartsWith("io.netty.")
                .and(
                    TypeMatchers.superClass(
                        ElementMatchers.named("io.netty.channel.AbstractChannel"))))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.NettyWrite.class,
                        ElementMatchers.named("doWrite")
                            .and(
                                ElementMatchers.takesArguments(1)
                                    .and(
                                        ElementMatchers.takesArgument(
                                            0,
                                            ElementMatchers.named(
                                                "io.netty.channel.ChannelOutboundBuffer"))))
                            .and(ElementMatchers.not(ElementMatchers.isAbstract())))))
        .type(ElementMatchers.named("io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.NettyRead.class,
                        ElementMatchers.named("read").and(ElementMatchers.takesArguments(0)))))
        .type(
            ElementMatchers.named(
                "io.netty.channel.epoll.AbstractEpollStreamChannel$EpollStreamUnsafe"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.NettyRead.class, ElementMatchers.named("epollInReady"))))
        .type(
            ElementMatchers.named(
                "io.netty.channel.kqueue.AbstractKQueueStreamChannel$KQueueStreamUnsafe"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(ClientCallAdvice.NettyRead.class, ElementMatchers.named("readReady"))));
  }
}

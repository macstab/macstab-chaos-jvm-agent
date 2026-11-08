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

  /** Kafka producer send and consumer poll. */
  static AgentBuilder applyKafkaTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("org.apache.kafka.clients.producer.KafkaProducer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.KafkaSend.class,
                        ElementMatchers.named("send").and(ElementMatchers.takesArguments(2)))))
        .type(ElementMatchers.named("org.apache.kafka.clients.consumer.KafkaConsumer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.KafkaPoll.class,
                        ElementMatchers.named("poll")
                            .and(ElementMatchers.takesArguments(java.time.Duration.class)))));
  }

  /** gRPC client call start, once per call for every stub flavour and transport. */
  static AgentBuilder applyGrpcTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("io.grpc.internal.ClientCallImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.GrpcStart.class,
                        ElementMatchers.named("start").and(ElementMatchers.takesArguments(2)))));
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
                            .and(
                                ElementMatchers.takesArgument(
                                    0,
                                    ElementMatchers.named(
                                        "io.lettuce.core.protocol.RedisCommand"))))));
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

  /** Netty channel flush (every transport) and stream-channel reads (NIO, epoll, kqueue). */
  static AgentBuilder applyNettyTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("io.netty.channel.AbstractChannel$AbstractUnsafe"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.NettyFlush.class,
                        ElementMatchers.named("flush0").and(ElementMatchers.takesArguments(0)))))
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

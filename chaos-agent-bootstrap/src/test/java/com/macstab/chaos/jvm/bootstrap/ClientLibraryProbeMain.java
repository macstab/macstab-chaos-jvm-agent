package com.macstab.chaos.jvm.bootstrap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Probe main for the client-library hooks: each mode calls a real client (Kafka, gRPC, Jedis,
 * Lettuce, R2DBC, AWS SDK, Netty) in a JVM started with the agent and prints {@code key=value}
 * lines; every mode ends with {@link DefectProbeMain#printCounts()}. The libraries come from {@code
 * chaos.test.clientClasspath}.
 */
public final class ClientLibraryProbeMain {

  private ClientLibraryProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "kafka-send" -> kafkaSend();
      case "kafka-poll" -> kafkaPoll();
      case "grpc-call" -> grpcCall();
      case "grpc-reject-before-resolution" -> grpcRejectBeforeResolution();
      case "jedis-set" -> jedisSet();
      case "jedis-pooled-set" -> jedisPooledSet();
      case "jedis-pool-set" -> jedisPoolSet();
      case "lettuce-set" -> lettuceSet();
      case "lettuce-async-reject" -> lettuceAsyncReject();
      case "r2dbc-execute" -> r2dbcExecute();
      case "aws-list-buckets" -> awsListBuckets();
      case "netty-write" -> nettyWrite();
      case "netty-write-reject" -> nettyWriteReject();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    DefectProbeMain.printCounts();
  }

  private static void report(final String key, final Throwable failure) {
    System.out.println(key + ".type=" + failure.getClass().getName());
    System.out.println(key + ".message=" + failure.getMessage());
  }

  /**
   * Sends to a matched ({@code orders}) and an unmatched ({@code audit}) topic without a broker:
   * how each send fails (synchronously, or through its future), how often its callback and the
   * producer interceptor saw the failure; then {@code partitionsFor}, which also waits for metadata
   * but is not a send.
   */
  private static void kafkaSend() throws Exception {
    final Properties config = new Properties();
    config.put("bootstrap.servers", "127.0.0.1:1");
    config.put("max.block.ms", "300");
    config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    config.put("interceptor.classes", CountingInterceptor.class.getName());
    try (org.apache.kafka.clients.producer.KafkaProducer<String, String> producer =
        new org.apache.kafka.clients.producer.KafkaProducer<>(config)) {
      for (final String topic : List.of("orders", "audit")) {
        final java.util.concurrent.atomic.AtomicInteger callbacks =
            new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> sent;
        try {
          sent =
              producer.send(
                  new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "k", "v"),
                  (metadata, exception) -> callbacks.incrementAndGet());
        } catch (final RuntimeException failure) {
          report(topic + ".send", failure);
          continue;
        }
        try {
          sent.get(5, java.util.concurrent.TimeUnit.SECONDS);
          System.out.println(topic + ".type=sent");
        } catch (final java.util.concurrent.ExecutionException failure) {
          report(topic, failure.getCause());
        }
        System.out.println(topic + ".callbacks=" + callbacks.get());
        System.out.println(topic + ".interceptorErrors=" + CountingInterceptor.ERRORS.getAndSet(0));
      }
      try {
        producer.partitionsFor("orders");
        System.out.println("partitions.type=returned");
      } catch (final RuntimeException failure) {
        report("partitions", failure);
      }
    }
  }

  /** Counts the failed sends a producer interceptor is told about. */
  public static final class CountingInterceptor
      implements org.apache.kafka.clients.producer.ProducerInterceptor<String, String> {
    static final java.util.concurrent.atomic.AtomicInteger ERRORS =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public org.apache.kafka.clients.producer.ProducerRecord<String, String> onSend(
        final org.apache.kafka.clients.producer.ProducerRecord<String, String> record) {
      return record;
    }

    @Override
    public void onAcknowledgement(
        final org.apache.kafka.clients.producer.RecordMetadata metadata,
        final Exception exception) {
      if (exception != null) {
        ERRORS.incrementAndGet();
      }
    }

    @Override
    public void close() {}

    @Override
    public void configure(final java.util.Map<String, ?> configs) {}
  }

  private static void kafkaPoll() {
    final Properties config = new Properties();
    config.put("bootstrap.servers", "127.0.0.1:1");
    config.put("group.id", "probe");
    config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    try (org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer =
        new org.apache.kafka.clients.consumer.KafkaConsumer<>(config)) {
      consumer.subscribe(List.of("orders"));
      try {
        consumer.poll(Duration.ofMillis(10));
        System.out.println("poll.type=returned");
      } catch (final RuntimeException failure) {
        report("poll", failure);
      }
    }
  }

  private static void grpcCall() throws Exception {
    final io.grpc.MethodDescriptor<String, String> say = echoSay();
    final io.grpc.ServerServiceDefinition service = echoService(say);
    final io.grpc.Server server =
        io.grpc.inprocess.InProcessServerBuilder.forName("probe")
            .addService(service)
            .build()
            .start();
    final io.grpc.ManagedChannel channel =
        io.grpc.inprocess.InProcessChannelBuilder.forName("probe").build();
    try {
      System.out.println(
          "reply="
              + io.grpc.stub.ClientCalls.blockingUnaryCall(
                  channel, say, io.grpc.CallOptions.DEFAULT, "hi"));
    } catch (final RuntimeException failure) {
      report("call", failure);
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  /**
   * Calls started before the channel's name is resolved: they wait as pending calls, and their
   * stream is created later, inside the channel's synchronization context. A blocking call and a
   * future call (rejected), then a call that succeeds; the server counts the calls it received.
   */
  private static void grpcRejectBeforeResolution() throws Exception {
    final io.grpc.MethodDescriptor<String, String> say = echoSay();
    final java.util.concurrent.atomic.AtomicInteger received =
        new java.util.concurrent.atomic.AtomicInteger();
    final io.grpc.Server server =
        io.grpc.inprocess.InProcessServerBuilder.forName("probe-late")
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    echoService(say),
                    new io.grpc.ServerInterceptor() {
                      @Override
                      public <Q, A> io.grpc.ServerCall.Listener<Q> interceptCall(
                          final io.grpc.ServerCall<Q, A> call,
                          final io.grpc.Metadata headers,
                          final io.grpc.ServerCallHandler<Q, A> next) {
                        received.incrementAndGet();
                        return next.startCall(call, headers);
                      }
                    }))
            .build()
            .start();
    io.grpc.NameResolverRegistry.getDefaultRegistry().register(new LateResolverProvider());
    final io.grpc.ManagedChannel channel =
        io.grpc.inprocess.InProcessChannelBuilder.forTarget("probe-late:///echo").build();
    // A deadline, so a call the channel never completes fails instead of hanging the probe.
    final io.grpc.CallOptions options =
        io.grpc.CallOptions.DEFAULT.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS);
    try {
      try {
        System.out.println(
            "blocking.reply="
                + io.grpc.stub.ClientCalls.blockingUnaryCall(channel, say, options, "hi"));
      } catch (final io.grpc.StatusRuntimeException failure) {
        System.out.println("blocking.code=" + failure.getStatus().getCode());
        System.out.println("blocking.description=" + failure.getStatus().getDescription());
      }
      try {
        System.out.println(
            "future.reply="
                + io.grpc.stub.ClientCalls.futureUnaryCall(channel.newCall(say, options), "hi")
                    .get(2, java.util.concurrent.TimeUnit.SECONDS));
      } catch (final java.util.concurrent.ExecutionException failure) {
        System.out.println(
            "future.code="
                + ((io.grpc.StatusRuntimeException) failure.getCause()).getStatus().getCode());
      }
      System.out.println(
          "later.reply=" + io.grpc.stub.ClientCalls.blockingUnaryCall(channel, say, options, "hi"));
      System.out.println("received=" + received.get());
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  /** Resolves {@code probe-late:///…} to the in-process server, 300 ms after it is asked. */
  private static final class LateResolverProvider extends io.grpc.NameResolverProvider {
    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 5;
    }

    @Override
    public String getDefaultScheme() {
      return "probe-late";
    }

    @Override
    public java.util.Collection<Class<? extends java.net.SocketAddress>>
        getProducedSocketAddressTypes() {
      return List.of(io.grpc.inprocess.InProcessSocketAddress.class);
    }

    @Override
    public io.grpc.NameResolver newNameResolver(
        final URI targetUri, final io.grpc.NameResolver.Args args) {
      if (!"probe-late".equals(targetUri.getScheme())) {
        return null;
      }
      return new io.grpc.NameResolver() {
        @Override
        public String getServiceAuthority() {
          return "probe-late";
        }

        @Override
        public void start(final io.grpc.NameResolver.Listener2 listener) {
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    try {
                      Thread.sleep(300L);
                    } catch (final InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    listener.onResult(
                        io.grpc.NameResolver.ResolutionResult.newBuilder()
                            .setAddressesOrError(
                                io.grpc.StatusOr.fromValue(
                                    List.of(
                                        new io.grpc.EquivalentAddressGroup(
                                            new io.grpc.inprocess.InProcessSocketAddress(
                                                "probe-late")))))
                            .build());
                  });
        }

        @Override
        public void shutdown() {}
      };
    }
  }

  private static io.grpc.MethodDescriptor<String, String> echoSay() {
    final io.grpc.MethodDescriptor.Marshaller<String> marshaller =
        new io.grpc.MethodDescriptor.Marshaller<>() {
          @Override
          public java.io.InputStream stream(final String value) {
            return new java.io.ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public String parse(final java.io.InputStream stream) {
            try {
              return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (final java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }
        };
    return io.grpc.MethodDescriptor.<String, String>newBuilder()
        .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("probe.Echo/Say")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();
  }

  private static io.grpc.ServerServiceDefinition echoService(
      final io.grpc.MethodDescriptor<String, String> say) {
    return io.grpc.ServerServiceDefinition.builder("probe.Echo")
        .addMethod(
            say,
            io.grpc.stub.ServerCalls.asyncUnaryCall(
                (request, observer) -> {
                  observer.onNext("echo " + request);
                  observer.onCompleted();
                }))
        .build();
  }

  private static void jedisSet() throws Exception {
    try (FakeRedis redis = new FakeRedis();
        redis.clients.jedis.Jedis jedis =
            new redis.clients.jedis.Jedis("127.0.0.1", redis.port())) {
      try {
        System.out.println("set.type=" + jedis.set("k", "v"));
      } catch (final RuntimeException failure) {
        report("set", failure);
      }
      System.out.println("ping=" + jedis.ping());
    }
  }

  /**
   * One {@code set} through a pooled {@code RedisClient} whose pool validates on borrow: the pool's
   * connection set-up and its {@code PING} are the client's own, not the application's.
   */
  private static void jedisPooledSet() throws Exception {
    final org.apache.commons.pool2.impl.GenericObjectPoolConfig<redis.clients.jedis.Connection>
        pool = new org.apache.commons.pool2.impl.GenericObjectPoolConfig<>();
    pool.setTestOnBorrow(true);
    try (FakeRedis fake = new FakeRedis();
        redis.clients.jedis.RedisClient client =
            redis.clients.jedis.RedisClient.builder()
                .hostAndPort("127.0.0.1", fake.port())
                .poolConfig(pool)
                .build()) {
      System.out.println("set=" + client.set("k", "v"));
    }
  }

  /** One {@code set} through a {@code JedisPool} (deprecated in Jedis 8, still common). */
  @SuppressWarnings("deprecation")
  private static void jedisPoolSet() throws Exception {
    final org.apache.commons.pool2.impl.GenericObjectPoolConfig<redis.clients.jedis.Jedis> config =
        new org.apache.commons.pool2.impl.GenericObjectPoolConfig<>();
    config.setTestOnBorrow(true);
    try (FakeRedis fake = new FakeRedis();
        redis.clients.jedis.JedisPool pool =
            new redis.clients.jedis.JedisPool(config, "127.0.0.1", fake.port());
        redis.clients.jedis.Jedis jedis = pool.getResource()) {
      System.out.println("set=" + jedis.set("k", "v"));
    }
  }

  private static void lettuceSet() throws Exception {
    try (FakeRedis redis = new FakeRedis()) {
      final io.lettuce.core.RedisClient client =
          io.lettuce.core.RedisClient.create("redis://127.0.0.1:" + redis.port());
      try (io.lettuce.core.api.StatefulRedisConnection<String, String> connection =
          client.connect()) {
        try {
          System.out.println("set.type=" + connection.sync().set("k", "v"));
        } catch (final RuntimeException failure) {
          report("set", failure);
        }
      } finally {
        client.shutdown();
      }
    }
  }

  /**
   * A Lettuce asynchronous {@code SET} and a batch of two {@code SET}s sent through {@code
   * dispatch(Collection)}: whether the call threw, and how each command's future ended.
   */
  private static void lettuceAsyncReject() throws Exception {
    try (FakeRedis redis = new FakeRedis()) {
      final io.lettuce.core.RedisClient client =
          io.lettuce.core.RedisClient.create("redis://127.0.0.1:" + redis.port());
      try (io.lettuce.core.api.StatefulRedisConnection<String, String> connection =
          client.connect()) {
        try {
          awaitCommand("single", connection.async().set("k", "v"));
        } catch (final RuntimeException failure) {
          report("single.call", failure);
        }
        final io.lettuce.core.codec.StringCodec codec = io.lettuce.core.codec.StringCodec.UTF8;
        final List<io.lettuce.core.protocol.AsyncCommand<String, String, String>> batch =
            new java.util.ArrayList<>();
        for (final String key : List.of("a", "b")) {
          batch.add(
              new io.lettuce.core.protocol.AsyncCommand<>(
                  new io.lettuce.core.protocol.Command<>(
                      io.lettuce.core.protocol.CommandType.SET,
                      new io.lettuce.core.output.StatusOutput<>(codec),
                      new io.lettuce.core.protocol.CommandArgs<>(codec)
                          .addKey(key)
                          .addValue("v"))));
        }
        try {
          connection.dispatch(batch);
        } catch (final RuntimeException failure) {
          report("batch.call", failure);
        }
        awaitCommand("batch.0", batch.get(0));
        awaitCommand("batch.1", batch.get(1));
      } finally {
        client.shutdown();
      }
    }
  }

  private static void awaitCommand(
      final String key, final java.util.concurrent.CompletionStage<?> command) {
    try {
      System.out.println(
          key
              + ".reply="
              + command.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
    } catch (final java.util.concurrent.ExecutionException failure) {
      report(key, failure.getCause());
    } catch (final InterruptedException | java.util.concurrent.TimeoutException failure) {
      report(key + ".wait", failure);
    }
  }

  private static void r2dbcExecute() {
    final io.r2dbc.spi.ConnectionFactory factory =
        io.r2dbc.spi.ConnectionFactories.get("r2dbc:h2:mem:///probe");
    final io.r2dbc.spi.Connection connection =
        reactor.core.publisher.Mono.from(factory.create()).block();
    try {
      final org.reactivestreams.Publisher<? extends io.r2dbc.spi.Result> results;
      try {
        results = connection.createStatement("SELECT 1").execute();
      } catch (final RuntimeException failure) {
        // A driver fails an execution through its publisher, not by throwing from execute().
        report("call", failure);
        return;
      }
      reactor.core.publisher.Flux.from(results).blockLast();
      System.out.println("execute.type=completed");
    } catch (final RuntimeException failure) {
      report("execute", failure);
    } finally {
      reactor.core.publisher.Mono.from(connection.close()).block();
    }
  }

  private static void awsListBuckets() {
    try (software.amazon.awssdk.services.s3.S3Client s3 =
        software.amazon.awssdk.services.s3.S3Client.builder()
            .region(software.amazon.awssdk.regions.Region.US_EAST_1)
            .endpointOverride(URI.create("http://127.0.0.1:1"))
            .credentialsProvider(
                software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("a", "b")))
            .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create())
            .build()) {
      s3.listBuckets();
      System.out.println("list.type=returned");
    } catch (final RuntimeException failure) {
      report("list", failure);
    }
  }

  private static void nettyWrite() throws Exception {
    final io.netty.channel.EventLoopGroup group =
        new io.netty.channel.MultiThreadIoEventLoopGroup(
            1, io.netty.channel.nio.NioIoHandler.newFactory());
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      final io.netty.channel.Channel channel =
          new io.netty.bootstrap.Bootstrap()
              .group(group)
              .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
              .handler(new io.netty.channel.ChannelInboundHandlerAdapter())
              .connect("127.0.0.1", server.getLocalPort())
              .sync()
              .channel();
      try (Socket accepted = server.accept()) {
        channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1, 2, 3})).sync();
        System.out.println("received=" + accepted.getInputStream().readNBytes(3).length);
      }
      channel.close().sync();
    } finally {
      group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).sync();
    }
  }

  /**
   * A rejected Netty write on a socket channel (after an empty flush, which writes nothing) and on
   * a connected datagram channel, whose {@code doWrite} is inherited from {@code
   * AbstractNioMessageChannel}: each write's future, and whether the channel closed.
   */
  private static void nettyWriteReject() throws Exception {
    final io.netty.channel.EventLoopGroup group =
        new io.netty.channel.MultiThreadIoEventLoopGroup(
            1, io.netty.channel.nio.NioIoHandler.newFactory());
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        java.net.DatagramSocket peer =
            new java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
      final io.netty.channel.Channel socket =
          new io.netty.bootstrap.Bootstrap()
              .group(group)
              .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
              .handler(new io.netty.channel.ChannelInboundHandlerAdapter())
              .connect("127.0.0.1", server.getLocalPort())
              .sync()
              .channel();
      try (Socket accepted = server.accept()) {
        socket.flush();
        reportWrite(
            "socket",
            socket.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1, 2, 3})),
            socket);
      }
      final io.netty.channel.Channel datagram =
          new io.netty.bootstrap.Bootstrap()
              .group(group)
              .channel(io.netty.channel.socket.nio.NioDatagramChannel.class)
              .handler(new io.netty.channel.ChannelInboundHandlerAdapter())
              .connect(new java.net.InetSocketAddress("127.0.0.1", peer.getLocalPort()))
              .sync()
              .channel();
      reportWrite(
          "datagram",
          datagram.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1, 2, 3})),
          datagram);
    } finally {
      group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).sync();
    }
  }

  private static void reportWrite(
      final String key,
      final io.netty.channel.ChannelFuture write,
      final io.netty.channel.Channel channel) {
    // Bounded waits: before the fix the write's future never completed.
    final boolean done = write.awaitUninterruptibly(2, java.util.concurrent.TimeUnit.SECONDS);
    System.out.println(key + ".done=" + done);
    if (done && write.cause() != null) {
      report(key, write.cause());
    }
    System.out.println(
        key
            + ".closed="
            + channel.closeFuture().awaitUninterruptibly(2, java.util.concurrent.TimeUnit.SECONDS));
  }

  /**
   * A minimal RESP server: answers HELLO and CLIENT with an error (so clients fall back to RESP2
   * without a handshake) and every other command with {@code +OK} or {@code +PONG}.
   */
  private static final class FakeRedis implements AutoCloseable {
    private final ServerSocket server;
    private final Thread acceptor;

    FakeRedis() throws Exception {
      server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
      acceptor =
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    while (!server.isClosed()) {
                      try {
                        final Socket socket = server.accept();
                        Thread.ofPlatform().daemon().start(() -> serve(socket));
                      } catch (final java.io.IOException closed) {
                        return;
                      }
                    }
                  });
    }

    int port() {
      return server.getLocalPort();
    }

    private static void serve(final Socket socket) {
      try (socket;
          BufferedReader in =
              new BufferedReader(
                  new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        final OutputStream out = socket.getOutputStream();
        String line;
        while ((line = in.readLine()) != null) {
          if (!line.startsWith("*")) {
            continue;
          }
          final int parts = Integer.parseInt(line.substring(1));
          String command = null;
          for (int i = 0; i < parts; i++) {
            in.readLine(); // $length
            final String value = in.readLine();
            if (i == 0) {
              command = value.toUpperCase(java.util.Locale.ROOT);
            }
          }
          final String reply =
              switch (command) {
                case "HELLO", "CLIENT" -> "-ERR unknown command\r\n";
                case "PING" -> "+PONG\r\n";
                default -> "+OK\r\n";
              };
          out.write(reply.getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
      } catch (final Exception closed) {
        // connection ended
      }
    }

    @Override
    public void close() throws Exception {
      server.close();
      acceptor.join(1000L);
    }
  }
}

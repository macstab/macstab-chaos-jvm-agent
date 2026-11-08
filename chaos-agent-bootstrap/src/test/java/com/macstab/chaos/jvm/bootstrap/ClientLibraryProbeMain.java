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
      case "jedis-set" -> jedisSet();
      case "jedis-pooled-set" -> jedisPooledSet();
      case "jedis-pool-set" -> jedisPoolSet();
      case "lettuce-set" -> lettuceSet();
      case "r2dbc-execute" -> r2dbcExecute();
      case "aws-list-buckets" -> awsListBuckets();
      case "netty-write" -> nettyWrite();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    DefectProbeMain.printCounts();
  }

  private static void report(final String key, final Throwable failure) {
    System.out.println(key + ".type=" + failure.getClass().getName());
    System.out.println(key + ".message=" + failure.getMessage());
  }

  private static void kafkaSend() {
    final Properties config = new Properties();
    config.put("bootstrap.servers", "127.0.0.1:1");
    config.put("max.block.ms", "300");
    config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    try (org.apache.kafka.clients.producer.KafkaProducer<String, String> producer =
        new org.apache.kafka.clients.producer.KafkaProducer<>(config)) {
      for (final String topic : List.of("orders", "audit")) {
        try {
          producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "k", "v"));
          System.out.println(topic + ".type=sent");
        } catch (final RuntimeException failure) {
          report(topic, failure);
        }
      }
    }
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
    final io.grpc.MethodDescriptor<String, String> say =
        io.grpc.MethodDescriptor.<String, String>newBuilder()
            .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("probe.Echo/Say")
            .setRequestMarshaller(marshaller)
            .setResponseMarshaller(marshaller)
            .build();
    final io.grpc.ServerServiceDefinition service =
        io.grpc.ServerServiceDefinition.builder("probe.Echo")
            .addMethod(
                say,
                io.grpc.stub.ServerCalls.asyncUnaryCall(
                    (request, observer) -> {
                      observer.onNext("echo " + request);
                      observer.onCompleted();
                    }))
            .build();
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

  private static void r2dbcExecute() {
    final io.r2dbc.spi.ConnectionFactory factory =
        io.r2dbc.spi.ConnectionFactories.get("r2dbc:h2:mem:///probe");
    final io.r2dbc.spi.Connection connection =
        reactor.core.publisher.Mono.from(factory.create()).block();
    try {
      reactor.core.publisher.Flux.from(connection.createStatement("SELECT 1").execute())
          .blockLast();
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

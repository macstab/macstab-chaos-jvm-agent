package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sample.SampleRequest;
import software.amazon.awssdk.services.sample.model.SampleModelRequest;

/**
 * The attributes the client-library hooks report, read reflectively from stand-ins shaped like the
 * library types: where the accessor is found (a public class, a public interface, the runtime class
 * itself) and {@code null} when it is missing, answers {@code null} or fails.
 */
@DisplayName("ClientCallExtractor")
class ClientCallExtractorTest {

  /** A value whose rendering fails, as a half-built library object's does. */
  static final class Broken {
    @Override
    public String toString() {
      throw new IllegalStateException("broken");
    }
  }

  /** An accessor declared on a public class. */
  public static class PublicRecord {
    private final Object name;

    PublicRecord(final Object name) {
      this.name = name;
    }

    public Object getFullMethodName() {
      return name;
    }
  }

  /** A non-public subclass: the accessor is called through its public superclass. */
  static final class HiddenRecord extends PublicRecord {
    HiddenRecord(final Object name) {
      super(name);
    }
  }

  /** An accessor declared on a public interface. */
  public interface Descriptor {
    Object getFullMethodName();
  }

  /** A non-public class implementing an unrelated interface first. */
  static final class HiddenDescriptor implements Comparable<HiddenDescriptor>, Descriptor {
    @Override
    public Object getFullMethodName() {
      return "orders.OrderService/Place";
    }

    @Override
    public int compareTo(final HiddenDescriptor other) {
      return 0;
    }
  }

  /** A public method no public type declares: reached on the runtime class itself. */
  static final class HiddenOnly {
    public Object getOperationName() {
      return "PutObject";
    }
  }

  @Nested
  @DisplayName("accessor lookup")
  class Lookup {

    @Test
    @DisplayName("calls an accessor of a public class")
    void publicClass() {
      assertThat(ClientCallExtractor.grpcMethod(new PublicRecord("orders"))).isEqualTo("orders");
    }

    @Test
    @DisplayName("calls an accessor of a non-public class through its public superclass")
    void publicSuperclass() {
      assertThat(ClientCallExtractor.grpcMethod(new HiddenRecord("payments")))
          .isEqualTo("payments");
    }

    @Test
    @DisplayName("calls an accessor of a non-public class through its public interface")
    void publicInterface() {
      assertThat(ClientCallExtractor.grpcMethod(new HiddenDescriptor()))
          .isEqualTo("orders.OrderService/Place");
    }

    @Test
    @DisplayName("calls an accessor only the non-public runtime class declares")
    void runtimeClass() {
      assertThat(ClientCallExtractor.awsOperation(new HiddenOnly())).isEqualTo("PutObject");
    }

    @Test
    @DisplayName("is null without a target, an accessor or a value, and when the value fails")
    void nullWhenUnreadable() {
      assertThat(ClientCallExtractor.grpcMethod(null)).isNull();
      assertThat(ClientCallExtractor.grpcMethod(new Object())).isNull();
      // Read twice: the absence is remembered.
      assertThat(ClientCallExtractor.grpcMethod(new Object())).isNull();
      assertThat(ClientCallExtractor.grpcMethod(new PublicRecord(null))).isNull();
      assertThat(ClientCallExtractor.grpcMethod(new PublicRecord(new Broken()))).isNull();
    }
  }

  @Nested
  @DisplayName("Kafka subscription")
  class KafkaSubscription {

    /** A consumer's subscription. */
    public static final class Consumer {
      private final Object subscription;

      Consumer(final Object subscription) {
        this.subscription = subscription;
      }

      public Object subscription() {
        return subscription;
      }
    }

    @Test
    @DisplayName("lists the subscribed topics sorted")
    void sortedTopics() {
      assertThat(ClientCallExtractor.kafkaSubscription(new Consumer(Set.of("b", "a", "c"))))
          .isEqualTo("a,b,c");
    }

    @Test
    @DisplayName("is null without topics, for a subscription that is no collection, and on failure")
    void nullWhenUnreadable() {
      assertThat(ClientCallExtractor.kafkaSubscription(new Consumer(Set.of()))).isNull();
      assertThat(ClientCallExtractor.kafkaSubscription(new Consumer("orders"))).isNull();
      assertThat(ClientCallExtractor.kafkaSubscription(new Consumer(List.of(new Broken()))))
          .isNull();
    }
  }

  @Nested
  @DisplayName("Redis commands")
  class RedisCommands {

    /** A Jedis protocol command. */
    public static final class Command {
      private final Object raw;
      private final String name;

      Command(final Object raw, final String name) {
        this.raw = raw;
        this.name = name;
      }

      public Object getRaw() {
        return raw;
      }

      @Override
      public String toString() {
        return name;
      }
    }

    /** Jedis command arguments. */
    public static final class CommandArguments {
      private final Object command;

      CommandArguments(final Object command) {
        this.command = command;
      }

      public Object getCommand() {
        return command;
      }
    }

    /** A Lettuce command. */
    public static final class RedisCommand {
      private final Object type;

      RedisCommand(final Object type) {
        this.type = type;
      }

      public Object getType() {
        return type;
      }
    }

    @Test
    @DisplayName("reads a Jedis command from its raw bytes, else from its name, upper case")
    void jedis() {
      final byte[] raw = "set".getBytes(StandardCharsets.US_ASCII);
      assertThat(ClientCallExtractor.jedisCommand(new CommandArguments(new Command(raw, "x"))))
          .isEqualTo("SET");
      assertThat(ClientCallExtractor.jedisCommand(new CommandArguments(new Command(null, "get"))))
          .isEqualTo("GET");
    }

    @Test
    @DisplayName("is null for a Jedis call without a command, and when the command fails")
    void jedisUnreadable() {
      assertThat(ClientCallExtractor.jedisCommand(new CommandArguments(null))).isNull();
      assertThat(ClientCallExtractor.jedisCommand(new CommandArguments(new Broken()))).isNull();
    }

    @Test
    @DisplayName("reads a Lettuce command type upper case; null without one or on failure")
    void lettuce() {
      assertThat(ClientCallExtractor.lettuceCommand(new RedisCommand("hget"))).isEqualTo("HGET");
      assertThat(ClientCallExtractor.lettuceCommand(new RedisCommand(null))).isNull();
      assertThat(ClientCallExtractor.lettuceCommand(new RedisCommand(new Broken()))).isNull();
    }

    @Test
    @DisplayName("names a Lettuce batch by its first command; an empty batch has no name")
    void lettuceBatch() {
      assertThat(
              ClientCallExtractor.lettuceCommand(
                  List.of(new RedisCommand("set"), new RedisCommand("get"))))
          .isEqualTo("SET");
      assertThat(ClientCallExtractor.lettuceCommand(List.of())).isNull();
    }

    @Test
    @DisplayName("completing something that is not a Lettuce command does nothing")
    void lettuceFailOnStandIn() {
      assertThatCode(
              () ->
                  ClientCallExtractor.lettuceFail(
                      List.of(new RedisCommand("set"), new Object()),
                      new IllegalStateException("down")))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("AWS service")
  class AwsService {

    /** AWS SDK client execution parameters. */
    public static final class ExecutionParams {
      private final Object input;
      private final RuntimeException failure;

      ExecutionParams(final Object input, final RuntimeException failure) {
        this.input = input;
        this.failure = failure;
      }

      public Object getInput() {
        if (failure != null) {
          throw failure;
        }
        return input;
      }
    }

    @Test
    @DisplayName("is the package segment after software.amazon.awssdk.services.")
    void serviceSegment() {
      assertThat(
              ClientCallExtractor.awsService(new ExecutionParams(new SampleModelRequest(), null)))
          .isEqualTo("sample");
      assertThat(ClientCallExtractor.awsService(new ExecutionParams(new SampleRequest(), null)))
          .isEqualTo("sample");
    }

    @Test
    @DisplayName("is null without an input, for an input outside the services, and on failure")
    void nullWhenUnreadable() {
      assertThat(ClientCallExtractor.awsService(new ExecutionParams(null, null))).isNull();
      assertThat(ClientCallExtractor.awsService(new ExecutionParams("not a request", null)))
          .isNull();
      assertThat(
              ClientCallExtractor.awsService(
                  new ExecutionParams(null, new IllegalStateException("closed"))))
          .isNull();
    }
  }

  @Nested
  @DisplayName("Netty peer")
  class NettyPeer {

    @Test
    @DisplayName("is the remote address of the channel the unsafe belongs to")
    void remoteAddressOfTheOuterChannel() {
      final InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 6379);
      final Channel channel = new Channel(peer, null);
      assertThat(ClientCallExtractor.nettyRemoteAddress(channel.unsafe())).isSameAs(peer);
      assertThat(ClientCallExtractor.nettyRemoteAddress(new SubclassedUnsafe(channel)))
          .as("the outer reference of a superclass")
          .isSameAs(peer);
    }

    @Test
    @DisplayName("is null for an object of no channel, and when the channel fails")
    void nullWhenUnreadable() {
      assertThat(ClientCallExtractor.nettyRemoteAddress(new Object())).isNull();
      final Channel failing = new Channel(null, new IllegalStateException("closed"));
      assertThat(ClientCallExtractor.nettyRemoteAddress(failing.unsafe())).isNull();
    }

    @Test
    @DisplayName(
        "a datagram channel implements Netty's DatagramChannel or DomainDatagramChannel, also"
            + " through a superclass or a sub-interface; a socket channel does not")
    void datagramChannel() throws Exception {
      final TypeDescription datagram = netty("io.netty.channel.socket.DatagramChannel");
      final TypeDescription domain = netty("io.netty.channel.unix.DomainDatagramChannel");
      final TypeDescription duplex = netty("io.netty.channel.socket.DuplexChannel");
      final DynamicType.Unloaded<?> nioDatagram = channel("probe.NioDatagramChannel", datagram);
      final DynamicType.Unloaded<?> extended =
          new ByteBuddy().makeInterface(datagram).name("probe.ExtendedDatagram").make();
      final Map<String, byte[]> types = new HashMap<>();
      for (final DynamicType.Unloaded<?> type :
          List.of(
              nioDatagram,
              extended,
              channel("probe.EpollDomainChannel", domain),
              channel("probe.NioSocketChannel", duplex),
              channel("probe.ViaSubInterface", extended.getTypeDescription()),
              new ByteBuddy()
                  .subclass(nioDatagram.getTypeDescription())
                  .name("probe.SubclassedDatagramChannel")
                  .make())) {
        types.put(type.getTypeDescription().getName(), type.getBytes());
      }
      for (final TypeDescription type : List.of(datagram, domain, duplex)) {
        types.put(
            type.getName(), new ByteBuddy().makeInterface().name(type.getName()).make().getBytes());
      }
      final ClassLoader loader = new ByteArrayClassLoader(getClass().getClassLoader(), types);

      for (final String name :
          List.of(
              "probe.NioDatagramChannel",
              "probe.EpollDomainChannel",
              "probe.SubclassedDatagramChannel",
              "probe.ViaSubInterface")) {
        assertThat(ClientCallExtractor.nettyDatagramChannel(newInstance(loader, name)))
            .as(name)
            .isTrue();
      }
      assertThat(
              ClientCallExtractor.nettyDatagramChannel(
                  newInstance(loader, "probe.NioSocketChannel")))
          .isFalse();
      assertThat(ClientCallExtractor.nettyDatagramChannel(new Object())).isFalse();
    }

    private TypeDescription netty(final String name) {
      return new ByteBuddy().makeInterface().name(name).make().getTypeDescription();
    }

    private DynamicType.Unloaded<?> channel(final String name, final TypeDescription implemented) {
      return new ByteBuddy().subclass(Object.class).implement(implemented).name(name).make();
    }

    private Object newInstance(final ClassLoader loader, final String name) throws Exception {
      return loader.loadClass(name).getConstructor().newInstance();
    }
  }

  /** A channel and its inner {@code Unsafe}, which holds the channel as {@code this$0}. */
  public static final class Channel {
    private final Object remoteAddress;
    private final RuntimeException failure;

    Channel(final Object remoteAddress, final RuntimeException failure) {
      this.remoteAddress = remoteAddress;
      this.failure = failure;
    }

    public Object remoteAddress() {
      if (failure != null) {
        throw failure;
      }
      return remoteAddress;
    }

    Unsafe unsafe() {
      return new Unsafe();
    }

    /** Refers to its channel, so the compiler keeps the outer reference. */
    class Unsafe {
      Channel channel() {
        return Channel.this;
      }
    }
  }

  @Nested
  @DisplayName("R2DBC failing publisher")
  class R2dbcFailingPublisher {

    @Test
    @DisplayName("is null when Reactor is not visible to the statement's class loader")
    void nullWithoutReactor() {
      // A boot-loaded object: its loader (the boot loader) has no Reactor.
      assertThat(
              ClientCallExtractor.r2dbcFailingPublisher(
                  new Object(), new IllegalStateException("r2dbc down")))
          .isNull();
    }
  }

  @Nested
  @DisplayName("gRPC failing stream")
  class GrpcFailingStream {

    /** A rejection carrying a gRPC status, as {@code StatusRuntimeException} does. */
    public static final class Rejection extends RuntimeException {
      private static final long serialVersionUID = 1L;
      private final transient Object status;

      Rejection(final Object status) {
        super("rejected");
        this.status = status;
      }

      public Object getStatus() {
        return status;
      }
    }

    @Test
    @DisplayName("builds the stream with (Status, RpcProgress, ClientStreamTracer[]), as 1.85 has")
    void currentConstructor() throws Exception {
      final FakeGrpc grpc = new FakeGrpc();
      final Object status = grpc.status();
      grpc.failingStream(grpc.statusType(), grpc.rpcProgress(), grpc.tracers());

      final Object stream = ClientCallExtractor.grpcFailingStream(new Rejection(status));

      assertThat(stream).isNotNull();
      assertThat(stream.getClass().getName()).isEqualTo("io.grpc.internal.FailingClientStream");
      assertThat(stream.getClass().getField("error").get(stream)).isSameAs(status);
    }

    @Test
    @DisplayName("builds the stream with (Status), as older lines have")
    void oldestConstructor() throws Exception {
      final FakeGrpc grpc = new FakeGrpc();
      final Object status = grpc.status();
      grpc.failingStream(grpc.statusType());

      final Object stream = ClientCallExtractor.grpcFailingStream(new Rejection(status));

      assertThat(stream).isNotNull();
      assertThat(stream.getClass().getField("error").get(stream)).isSameAs(status);
    }

    @Test
    @DisplayName("is null without a known constructor, and records the miss once")
    void unknownConstructor() throws Exception {
      final FakeGrpc grpc = new FakeGrpc();
      final Object status = grpc.status();
      grpc.failingStream(String.class);

      try {
        assertThat(ClientCallExtractor.grpcFailingStream(new Rejection(status))).isNull();
        assertThat(ClientCallExtractor.failingStreamMiss())
            .contains("io.grpc.internal.FailingClientStream");
      } finally {
        ClientCallExtractor.forgetFailingStreamMiss();
      }
    }

    @Test
    @DisplayName("is null for a failure without a gRPC status (the fallback IOException)")
    void noStatus() {
      assertThat(ClientCallExtractor.grpcFailingStream(new java.io.IOException("rpc down")))
          .isNull();
    }
  }

  /** {@code io.grpc} stand-ins defined in a class loader of their own. */
  static final class FakeGrpc {
    private final net.bytebuddy.dynamic.loading.ByteArrayClassLoader loader =
        new net.bytebuddy.dynamic.loading.ByteArrayClassLoader(
            FakeGrpc.class.getClassLoader(), java.util.Map.of());
    private final Class<?> statusType = define("io.grpc.Status");

    Class<?> statusType() {
      return statusType;
    }

    Object status() throws ReflectiveOperationException {
      return statusType.getConstructor().newInstance();
    }

    Class<?> rpcProgress() {
      return new net.bytebuddy.ByteBuddy()
          .makeEnumeration("PROCESSED", "REFUSED")
          .name("io.grpc.internal.ClientStreamListener$RpcProgress")
          .make()
          .load(loader, net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.INJECTION)
          .getLoaded();
    }

    Class<?> tracers() {
      return java.lang.reflect.Array.newInstance(define("io.grpc.ClientStreamTracer"), 0)
          .getClass();
    }

    /** Defines {@code FailingClientStream} with one constructor that keeps argument 0 in error. */
    void failingStream(final Class<?>... parameters) throws ReflectiveOperationException {
      new net.bytebuddy.ByteBuddy()
          .subclass(
              Object.class,
              net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default.NO_CONSTRUCTORS)
          .name("io.grpc.internal.FailingClientStream")
          .defineField("error", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
          .defineConstructor(net.bytebuddy.description.modifier.Visibility.PUBLIC)
          .withParameters(parameters)
          .intercept(
              net.bytebuddy.implementation.MethodCall.invoke(Object.class.getConstructor())
                  .andThen(
                      net.bytebuddy.implementation.FieldAccessor.ofField("error")
                          .setsArgumentAt(0)))
          .make()
          .load(loader, net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.INJECTION);
    }

    private Class<?> define(final String name) {
      return new net.bytebuddy.ByteBuddy()
          .subclass(Object.class)
          .name(name)
          .make()
          .load(loader, net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.INJECTION)
          .getLoaded();
    }
  }

  /** An unsafe subclass without an outer reference of its own. */
  static final class SubclassedUnsafe extends Channel.Unsafe {
    SubclassedUnsafe(final Channel channel) {
      channel.super();
    }
  }
}

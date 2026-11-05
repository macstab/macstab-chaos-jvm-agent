package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
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
    private final Object topic;

    PublicRecord(final Object topic) {
      this.topic = topic;
    }

    public Object topic() {
      return topic;
    }
  }

  /** A non-public subclass: the accessor is called through its public superclass. */
  static final class HiddenRecord extends PublicRecord {
    HiddenRecord(final Object topic) {
      super(topic);
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
      assertThat(ClientCallExtractor.kafkaTopic(new PublicRecord("orders"))).isEqualTo("orders");
    }

    @Test
    @DisplayName("calls an accessor of a non-public class through its public superclass")
    void publicSuperclass() {
      assertThat(ClientCallExtractor.kafkaTopic(new HiddenRecord("payments")))
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
      assertThat(ClientCallExtractor.kafkaTopic(new PublicRecord(null))).isNull();
      assertThat(ClientCallExtractor.kafkaTopic(new PublicRecord(new Broken()))).isNull();
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

  /** An unsafe subclass without an outer reference of its own. */
  static final class SubclassedUnsafe extends Channel.Unsafe {
    SubclassedUnsafe(final Channel channel) {
      channel.super();
    }
  }
}

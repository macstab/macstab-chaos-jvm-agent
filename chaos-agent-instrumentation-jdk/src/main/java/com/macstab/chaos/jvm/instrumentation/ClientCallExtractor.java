package com.macstab.chaos.jvm.instrumentation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Reads the attributes client-library hooks report (topic, gRPC method, Redis command, AWS service
 * and operation, Netty peer) reflectively, so the agent has no compile-time dependency on any of
 * those libraries. Every reader returns {@code null} when the value cannot be read; callers wrap
 * the reads in {@code BootstrapDispatcher.beginInternalCall()}/{@code endInternalCall()} so the
 * agent's own reflection is not evaluated as application reflection.
 *
 * <p>Public because the advice that calls it is inlined into the library classes, which live in
 * other packages; a package-private class there fails with {@link IllegalAccessError}. Part of the
 * bootstrap bridge ({@code BridgeInjector.BRIDGE_CLASSES}): the library classes resolve it through
 * the bootstrap class loader, so a library defined by a loader that cannot see the agent's (one
 * whose parent is the platform loader) still links. Like every bridge class it references no agent
 * class outside the bridge.
 */
public final class ClientCallExtractor {

  private static final String AWS_SERVICES_PACKAGE = "software.amazon.awssdk.services.";

  /** Accessor lookups per class; an empty Optional records that the accessor does not exist. */
  private static final ClassValue<ConcurrentHashMap<String, Optional<Method>>> ACCESSORS =
      new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Optional<Method>> computeValue(final Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  private ClientCallExtractor() {}

  /**
   * Calls the public no-argument method {@code name} on {@code target}, found on a public class or
   * interface of {@code target}'s type so the call works without {@code setAccessible} even when
   * the runtime class itself is not public.
   */
  private static @Nullable Object invoke(final Object target, final String name) throws Throwable {
    final Optional<Method> method =
        ACCESSORS.get(target.getClass()).computeIfAbsent(name, ignored -> find(target, name));
    return method.isPresent() ? method.get().invoke(target) : null;
  }

  private static Optional<Method> find(final Object target, final String name) {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      final Optional<Method> found = publicAccessor(type, name);
      if (found.isPresent()) {
        return found;
      }
      for (final Class<?> contract : type.getInterfaces()) {
        final Optional<Method> viaInterface = publicAccessor(contract, name);
        if (viaInterface.isPresent()) {
          return viaInterface;
        }
      }
    }
    // Last resort: the runtime class's own method, made accessible (not exported, e.g. a
    // package-private implementation class).
    try {
      final Method method = target.getClass().getMethod(name);
      method.setAccessible(true);
      return Optional.of(method);
    } catch (final ReflectiveOperationException | RuntimeException unavailable) {
      return Optional.empty();
    }
  }

  private static Optional<Method> publicAccessor(final Class<?> type, final String name) {
    if (!Modifier.isPublic(type.getModifiers())) {
      return Optional.empty();
    }
    try {
      return Optional.of(type.getMethod(name));
    } catch (final NoSuchMethodException absent) {
      return Optional.empty();
    }
  }

  /**
   * Returns the topic of a Kafka {@code ProducerRecord}.
   *
   * @param record the producer record
   * @return the topic, or {@code null}
   */
  public static @Nullable String kafkaTopic(final Object record) {
    return stringOf(record, "topic");
  }

  /**
   * Returns a Kafka consumer's subscribed topics, sorted and comma-separated.
   *
   * @param consumer the Kafka consumer
   * @return the topics, or {@code null} when none are subscribed
   */
  public static @Nullable String kafkaSubscription(final Object consumer) {
    try {
      final Object subscription = invoke(consumer, "subscription");
      if (!(subscription instanceof Collection<?> topics) || topics.isEmpty()) {
        return null;
      }
      final TreeSet<String> sorted = new TreeSet<>();
      for (final Object topic : topics) {
        sorted.add(String.valueOf(topic));
      }
      return String.join(",", sorted);
    } catch (final Throwable unreadable) {
      return null;
    }
  }

  /**
   * Returns the full method name of a gRPC {@code MethodDescriptor}.
   *
   * @param methodDescriptor the method descriptor
   * @return {@code package.Service/Method}, or {@code null}
   */
  public static @Nullable String grpcMethod(final Object methodDescriptor) {
    return stringOf(methodDescriptor, "getFullMethodName");
  }

  /**
   * Returns the command of Jedis {@code CommandArguments}, upper case.
   *
   * @param commandArguments the command arguments
   * @return the command name, or {@code null}
   */
  public static @Nullable String jedisCommand(final Object commandArguments) {
    try {
      final Object command = invoke(commandArguments, "getCommand");
      if (command == null) {
        return null;
      }
      final Object raw = invoke(command, "getRaw");
      final String name =
          raw instanceof byte[] bytes
              ? new String(bytes, StandardCharsets.US_ASCII)
              : String.valueOf(command);
      return name.toUpperCase(Locale.ROOT);
    } catch (final Throwable unreadable) {
      return null;
    }
  }

  /**
   * Returns the command type of a Lettuce {@code RedisCommand}, upper case.
   *
   * @param redisCommand the Lettuce command
   * @return the command name, or {@code null}
   */
  public static @Nullable String lettuceCommand(final Object redisCommand) {
    try {
      final Object type = invoke(redisCommand, "getType");
      return type == null ? null : String.valueOf(type).toUpperCase(Locale.ROOT);
    } catch (final Throwable unreadable) {
      return null;
    }
  }

  /**
   * Returns the operation name of AWS SDK {@code ClientExecutionParams}.
   *
   * @param executionParams the execution parameters
   * @return the operation name, or {@code null}
   */
  public static @Nullable String awsOperation(final Object executionParams) {
    return stringOf(executionParams, "getOperationName");
  }

  /**
   * Returns the service of AWS SDK {@code ClientExecutionParams}: the package segment after {@code
   * software.amazon.awssdk.services.} of the request class ({@code s3}, {@code dynamodb}).
   *
   * @param executionParams the execution parameters
   * @return the service, or {@code null}
   */
  public static @Nullable String awsService(final Object executionParams) {
    try {
      final Object input = invoke(executionParams, "getInput");
      if (input == null) {
        return null;
      }
      final String packageName = input.getClass().getPackageName();
      if (!packageName.startsWith(AWS_SERVICES_PACKAGE)) {
        return null;
      }
      final int end = packageName.indexOf('.', AWS_SERVICES_PACKAGE.length());
      return end < 0
          ? packageName.substring(AWS_SERVICES_PACKAGE.length())
          : packageName.substring(AWS_SERVICES_PACKAGE.length(), end);
    } catch (final Throwable unreadable) {
      return null;
    }
  }

  /**
   * Returns the peer address of the channel a Netty {@code Unsafe} belongs to.
   *
   * @param unsafe the channel's {@code Unsafe}
   * @return the remote {@code SocketAddress}, or {@code null}
   */
  public static @Nullable Object nettyRemoteAddress(final Object unsafe) {
    try {
      for (Class<?> type = unsafe.getClass(); type != null; type = type.getSuperclass()) {
        final Field outer;
        try {
          outer = type.getDeclaredField("this$0");
        } catch (final NoSuchFieldException notHere) {
          continue;
        }
        outer.setAccessible(true);
        final Object channel = outer.get(unsafe);
        return channel == null ? null : invoke(channel, "remoteAddress");
      }
      return null;
    } catch (final Throwable unreadable) {
      return null;
    }
  }

  private static @Nullable String stringOf(final Object target, final String accessor) {
    if (target == null) {
      return null;
    }
    try {
      final Object value = invoke(target, accessor);
      return value == null ? null : value.toString();
    } catch (final Throwable unreadable) {
      return null;
    }
  }
}

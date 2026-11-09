package com.macstab.chaos.jvm.instrumentation;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

  /**
   * The enclosing-instance field ({@code this$0}) per Netty {@code Unsafe} class, found once; an
   * empty Optional records a class without one.
   */
  private static final ClassValue<Optional<Field>> OUTER_INSTANCE =
      new ClassValue<>() {
        @Override
        protected Optional<Field> computeValue(final Class<?> type) {
          for (Class<?> declaring = type;
              declaring != null;
              declaring = declaring.getSuperclass()) {
            try {
              final Field outer = declaring.getDeclaredField("this$0");
              outer.setAccessible(true);
              return Optional.of(outer);
            } catch (final NoSuchFieldException notHere) {
              // look further up
            } catch (final RuntimeException inaccessible) {
              return Optional.empty();
            }
          }
          return Optional.empty();
        }
      };

  /**
   * Whether a Netty channel class is a datagram channel ({@code io.netty.channel.socket
   * .DatagramChannel} or {@code io.netty.channel.unix.DomainDatagramChannel}), found once per class
   * by name, so no Netty class is loaded.
   */
  private static final ClassValue<Boolean> NETTY_DATAGRAM =
      new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
          return implementsNamed(type, NETTY_DATAGRAM_TYPES);
        }
      };

  private static final List<String> NETTY_DATAGRAM_TYPES =
      List.of(
          "io.netty.channel.socket.DatagramChannel", "io.netty.channel.unix.DomainDatagramChannel");

  /** gRPC's own failing stream, built for a rejected call ({@link #grpcFailingStream}). */
  private static final String GRPC_FAILING_STREAM = "io.grpc.internal.FailingClientStream";

  /**
   * The {@code FailingClientStream} constructors gRPC versions have had, newest last; {@code
   * CompatibilityValidator} refuses a gRPC reject at activation against the same list.
   */
  private static final List<List<String>> GRPC_FAILING_STREAM_SHAPES =
      List.of(
          List.of("io.grpc.Status"),
          List.of("io.grpc.Status", "io.grpc.internal.ClientStreamListener$RpcProgress"),
          List.of("io.grpc.Status", "[Lio.grpc.ClientStreamTracer;"),
          List.of(
              "io.grpc.Status",
              "io.grpc.internal.ClientStreamListener$RpcProgress",
              "[Lio.grpc.ClientStreamTracer;"));

  /** The first gRPC failing stream that could not be built; see {@link #failingStreamMiss()}. */
  private static volatile @Nullable String failingStreamMiss;

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
   * Builds the stream gRPC itself returns for a call that fails before it reaches a transport: an
   * {@code io.grpc.internal.FailingClientStream} for the status of {@code rejected} (a {@code
   * StatusRuntimeException}), through the class loader of that status. The call then fails through
   * its listener with that status, as a deadline that expired before the call started does, also
   * when the stream is created later inside the channel's synchronization context (a call started
   * before the name resolved), where a throw would panic the channel.
   *
   * @param rejected the library exception the reject built
   * @return the failing stream, or {@code null} when {@code rejected} carries no gRPC status or
   *     this gRPC version has no known {@code FailingClientStream} constructor (recorded once, see
   *     {@link #failingStreamMiss()})
   */
  public static @Nullable Object grpcFailingStream(final Throwable rejected) {
    final Object status;
    try {
      status = rejected.getClass().getMethod("getStatus").invoke(rejected);
    } catch (final ReflectiveOperationException | RuntimeException noStatus) {
      return null;
    }
    if (status == null) {
      return null;
    }
    final ClassLoader loader = status.getClass().getClassLoader();
    try {
      final Class<?> failing = Class.forName(GRPC_FAILING_STREAM, false, loader);
      for (final List<String> shape : GRPC_FAILING_STREAM_SHAPES) {
        for (final Constructor<?> constructor : failing.getConstructors()) {
          if (parameterNames(constructor).equals(shape)) {
            return constructor.newInstance(failingStreamArguments(constructor, status));
          }
        }
      }
    } catch (final ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
      recordFailingStreamMiss(unavailable.toString());
      return null;
    }
    recordFailingStreamMiss("no known constructor");
    return null;
  }

  /**
   * Why a gRPC reject could not be turned into gRPC's failing stream (and was thrown from {@code
   * newStream} instead), the first time it happened; read into the diagnostics' hook failures.
   *
   * @return the reason, or {@code null} when it never happened
   */
  public static @Nullable String failingStreamMiss() {
    return failingStreamMiss;
  }

  /** Forgets the recorded miss ({@link #failingStreamMiss()}): the hooks were uninstalled. */
  public static void forgetFailingStreamMiss() {
    failingStreamMiss = null;
  }

  private static void recordFailingStreamMiss(final String reason) {
    if (failingStreamMiss == null) {
      failingStreamMiss = GRPC_FAILING_STREAM + ": " + reason;
    }
  }

  private static List<String> parameterNames(final Constructor<?> constructor) {
    final List<String> names = new ArrayList<>();
    for (final Class<?> parameter : constructor.getParameterTypes()) {
      names.add(parameter.getName());
    }
    return names;
  }

  /**
   * The status, then {@code RpcProgress.PROCESSED} (not retried transparently), then no tracers.
   */
  private static Object[] failingStreamArguments(
      final Constructor<?> constructor, final Object status) throws ReflectiveOperationException {
    final Class<?>[] parameters = constructor.getParameterTypes();
    final Object[] arguments = new Object[parameters.length];
    arguments[0] = status;
    for (int index = 1; index < parameters.length; index++) {
      arguments[index] =
          parameters[index].isArray()
              ? Array.newInstance(parameters[index].getComponentType(), 0)
              : parameters[index].getMethod("valueOf", String.class).invoke(null, "PROCESSED");
    }
    return arguments;
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
   * Returns the command type of a Lettuce {@code RedisCommand}, upper case; for a batch (a {@code
   * Collection} of commands), that of its first command.
   *
   * @param redisCommand the Lettuce command, or a collection of them
   * @return the command name, or {@code null}
   */
  public static @Nullable String lettuceCommand(final Object redisCommand) {
    if (redisCommand instanceof Collection<?> batch) {
      final Object first = batch.isEmpty() ? null : batch.iterator().next();
      return first == null ? null : lettuceCommand(first);
    }
    try {
      final Object type = invoke(redisCommand, "getType");
      return type == null ? null : String.valueOf(type).toUpperCase(Locale.ROOT);
    } catch (final Throwable unreadable) {
      return null;
    }
  }

  /**
   * Completes a rejected Lettuce command, or each command of a rejected batch, exceptionally
   * ({@code RedisCommand.completeExceptionally}), as Lettuce does for a command it cannot write.
   *
   * @param commands the Lettuce command, or a collection of them
   * @param failure the library exception the reject built
   */
  public static void lettuceFail(final Object commands, final Throwable failure) {
    if (commands instanceof Collection<?> batch) {
      for (final Object command : batch) {
        lettuceFail(command, failure);
      }
      return;
    }
    try {
      // Through the public interface: the command's runtime class need not be public.
      Class.forName(
              "io.lettuce.core.protocol.RedisCommand", false, commands.getClass().getClassLoader())
          .getMethod("completeExceptionally", Throwable.class)
          .invoke(commands, failure);
    } catch (final ReflectiveOperationException | LinkageError | RuntimeException unreachable) {
      // not a Lettuce command: nothing to complete
    }
  }

  /**
   * Builds the publisher a rejected R2DBC execution returns: Reactor's {@code Mono.error(failure)}
   * or {@code Flux.error(failure)}, whichever the statement's {@code execute()} may return (a
   * driver may narrow it, as r2dbc-h2 does to {@code Flux}), through the statement's class loader.
   *
   * @param statement the R2DBC statement
   * @param failure the library exception the reject built
   * @return the publisher, or {@code null} when Reactor is not visible to the statement's loader or
   *     neither type fits the return type
   */
  public static @Nullable Object r2dbcFailingPublisher(
      final Object statement, final Throwable failure) {
    try {
      final ClassLoader loader = statement.getClass().getClassLoader();
      final Class<?> returned = statement.getClass().getMethod("execute").getReturnType();
      for (final String type :
          List.of("reactor.core.publisher.Mono", "reactor.core.publisher.Flux")) {
        final Class<?> publisher = Class.forName(type, false, loader);
        if (returned.isAssignableFrom(publisher)) {
          return publisher.getMethod("error", Throwable.class).invoke(null, failure);
        }
      }
      return null;
    } catch (final ReflectiveOperationException | LinkageError | RuntimeException noReactor) {
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
      final Optional<Field> outer = OUTER_INSTANCE.get(unsafe.getClass());
      if (outer.isEmpty()) {
        return null;
      }
      final Object channel = outer.get().get(unsafe);
      return channel == null ? null : invoke(channel, "remoteAddress");
    } catch (final Throwable unreadable) {
      return null;
    }
  }

  /**
   * Returns the peer of a Netty channel ({@code Channel.remoteAddress()}, through the cached
   * accessor).
   *
   * @param channel the channel
   * @return the peer, or {@code null} when it is unconnected or cannot be read
   */
  public static @Nullable Object nettyChannelRemoteAddress(final Object channel) {
    try {
      return invoke(channel, "remoteAddress");
    } catch (final Throwable unreadable) {
      return null;
    }
  }

  /**
   * Returns whether {@code channel} is a Netty datagram channel. Its {@code doWrite} is not
   * evaluated: a real write error there fails one datagram and the channel stays open, but a throw
   * from the hook would close the channel ({@code AbstractUnsafe.handleWriteError}).
   *
   * @param channel the channel
   * @return {@code true} for a {@code DatagramChannel} or {@code DomainDatagramChannel}
   */
  public static boolean nettyDatagramChannel(final Object channel) {
    return NETTY_DATAGRAM.get(channel.getClass());
  }

  private static boolean implementsNamed(final Class<?> type, final List<String> names) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      for (final Class<?> implemented : current.getInterfaces()) {
        if (names.contains(implemented.getName()) || implementsNamed(implemented, names)) {
          return true;
        }
      }
    }
    return false;
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

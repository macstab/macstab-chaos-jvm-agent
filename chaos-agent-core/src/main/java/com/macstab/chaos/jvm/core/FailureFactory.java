package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * The single place that builds the failures the agent injects (ADR 0005): each is what the real
 * call raises, never a generic chaos exception.
 *
 * <ul>
 *   <li>{@link #reject}: the failure of the intercepted call, chosen from the call's {@link
 *       InvocationContext} (a {@code java.io} or a {@code java.nio} open, a socket read with or
 *       without {@code SO_TIMEOUT}). The switch lists every operation: a new one fails to compile
 *       until it is mapped.
 *   <li>{@link #errno}: what the JDK raises when the operating system reports an {@code errno}.
 *   <li>{@link #clientReject}: a client library's own exception, {@link IOException} when the
 *       library type is not visible.
 *   <li>{@link #timeout}, {@link #completionFailure}, {@link #injected}, {@link #suppressedStart}
 *       and {@link #suppressedExit} for the other effects.
 * </ul>
 *
 * <p>Every failure is trimmed to its call site: the frames up to and including the last {@code
 * BootstrapDispatcher} frame (the agent's bridge, entered from the woven method) are dropped, so
 * the top frame is the intercepted method; without a bridge frame (the manual {@code ChaosRuntime}
 * hooks) the frames up to the last frame of this package are dropped.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is stateless; all methods are static and may be called concurrently without
 * synchronization.
 */
final class FailureFactory {
  private FailureFactory() {}

  /** The class whose frames the advice enters the agent through. */
  private static final String BRIDGE_CLASS =
      "com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher";

  /** This package: the dispatch frames of a call that comes in without the bridge. */
  private static final String CORE_PACKAGE = "com.macstab.chaos.jvm.core.";

  /** The file calls on an open descriptor, whose errors name no path. */
  private static final java.util.Set<OperationType> DESCRIPTOR_CALLS =
      java.util.EnumSet.of(
          OperationType.FILE_IO_READ,
          OperationType.FILE_IO_WRITE,
          OperationType.FILE_IO_TRUNCATE,
          OperationType.FILE_IO_MAP,
          OperationType.FILE_IO_CLOSE);

  /** What {@code Thread.start} throws when the JVM cannot create the native thread. */
  private static final String NATIVE_THREAD_FAILURE =
      "unable to create native thread: possibly out of memory or process/resource limits reached";

  /**
   * Creates the failure the intercepted call raises when it is rejected.
   *
   * <p>A method entry or exit and a scheduled task's tick fail with {@link IllegalStateException}:
   * the user code there may throw any unchecked exception. The operations whose reject the
   * validator refuses, and those whose reject returns a value ({@code DispatchEngine}), never reach
   * this method; their arm only asserts that contract.
   *
   * @param context the intercepted call
   * @param message the detail message
   * @return the failure, trimmed to the call site
   */
  static Throwable reject(final InvocationContext context, final @Nullable String message) {
    return atCallSite(rejectFailure(context, message));
  }

  private static Throwable rejectFailure(
      final InvocationContext context, final @Nullable String message) {
    final OperationType operationType = context.operationType();
    return switch (operationType) {
      case METHOD_ENTER, METHOD_EXIT, SCHEDULE_TICK -> new IllegalStateException(message);
      case THREAD_START -> new OutOfMemoryError(NATIVE_THREAD_FAILURE);
      case VIRTUAL_THREAD_START, EXECUTOR_SUBMIT, SCHEDULE_SUBMIT ->
          new RejectedExecutionException(message);
      // The calls that declare only InterruptedException.
      case THREAD_SLEEP, EXECUTOR_AWAIT_TERMINATION, QUEUE_PUT, PROCESS_WAIT ->
          new InterruptedException(message);
      // ApplicationShutdownHooks.add once the JVM is shutting down.
      case SHUTDOWN_HOOK_REGISTER -> new IllegalStateException("Shutdown in progress");
      case CLASS_LOAD -> new ClassNotFoundException(message);
      case CLASS_DEFINE -> new ClassFormatError(message);
      // Method.invoke wraps what the target threw.
      case REFLECTION_INVOKE ->
          new java.lang.reflect.InvocationTargetException(new RuntimeException(message), message);
      case DIRECT_BUFFER_ALLOCATE -> new OutOfMemoryError(message);
      case OBJECT_DESERIALIZE -> new java.io.InvalidClassException(message);
      case OBJECT_SERIALIZE -> new java.io.NotSerializableException(message);
      case NATIVE_LIBRARY_LOAD -> new UnsatisfiedLinkError(message);
      case JNDI_LOOKUP -> createJndiNamingException(message);
      case JMX_INVOKE, JMX_GET_ATTR -> createJmxMBeanException(message);
      case ZIP_INFLATE -> new java.util.zip.DataFormatException(message);
      case SOCKET_CONNECT -> new java.net.ConnectException(message);
      // Only an SO_TIMEOUT can expire; a read without one fails as a broken connection.
      case SOCKET_READ ->
          context.timeoutMillis() > 0
              ? new java.net.SocketTimeoutException(message)
              : new java.net.SocketException(message);
      case SOCKET_WRITE, SOCKET_SHUTDOWN -> new java.net.SocketException(message);
      case SOCKET_BIND -> new java.net.BindException(message);
      case DNS_RESOLVE, DNS_ANSWER, DNS_REVERSE -> new java.net.UnknownHostException(message);
      case SSL_HANDSHAKE -> createSslHandshakeException(message);
      case JDBC_CONNECTION_ACQUIRE,
          JDBC_STATEMENT_EXECUTE,
          JDBC_PREPARED_STATEMENT,
          JDBC_TRANSACTION_COMMIT,
          JDBC_TRANSACTION_ROLLBACK ->
          createJdbcSqlException(message);
      // FileDescriptor.sync() declares only SyncFailedException; FileChannel.force any
      // IOException. SyncFailedException satisfies both.
      case FILE_IO_SYNC -> new java.io.SyncFailedException(message);
      // A java.io constructor declares FileNotFoundException; a java.nio open names the path.
      case FILE_IO_OPEN ->
          isJavaIo(context)
              ? new java.io.FileNotFoundException(message)
              : new java.nio.file.FileSystemException(context.path(), null, message);
      case SOCKET_ACCEPT,
          SOCKET_CLOSE,
          NIO_SELECTOR_SELECT,
          NIO_CHANNEL_READ,
          NIO_CHANNEL_WRITE,
          NIO_CHANNEL_CONNECT,
          NIO_CHANNEL_ACCEPT,
          DATAGRAM_SEND,
          DATAGRAM_RECEIVE,
          FILE_IO_READ,
          FILE_IO_WRITE,
          FILE_IO_TRUNCATE,
          FILE_IO_DELETE,
          FILE_IO_MOVE,
          FILE_IO_MAP,
          FILE_IO_CLOSE,
          FILE_IO_MOVE_TARGET,
          HTTP_CLIENT_SEND,
          HTTP_CLIENT_SEND_ASYNC,
          NETTY_CHANNEL_WRITE,
          PROCESS_START,
          // the client libraries' fallback, when their own type is not visible (clientReject)
          MESSAGE_SEND,
          MESSAGE_RECEIVE,
          RPC_CALL,
          REDIS_COMMAND,
          R2DBC_STATEMENT_EXECUTE,
          AWS_SDK_CALL ->
          new IOException(message);
      // Refused at activation (CompatibilityValidator), or answered with a value by
      // DispatchEngine.rejectTerminal: a reject never builds a failure for these. No real call
      // of them fails, so there is no real type; the message is kept.
      case EXECUTOR_WORKER_RUN,
          FORK_JOIN_TASK_RUN,
          EXECUTOR_SHUTDOWN,
          QUEUE_OFFER,
          QUEUE_TAKE,
          QUEUE_POLL,
          ASYNC_COMPLETE,
          ASYNC_COMPLETE_EXCEPTIONALLY,
          ASYNC_CANCEL,
          RESOURCE_LOAD,
          MONITOR_ENTER,
          THREAD_PARK,
          THREAD_LOCAL_GET,
          THREAD_LOCAL_SET,
          SYSTEM_GC_REQUEST,
          SYSTEM_EXIT_REQUEST,
          ZIP_DEFLATE,
          NETTY_CHANNEL_READ,
          SYSTEM_CLOCK_MILLIS,
          SYSTEM_CLOCK_NANOS,
          INSTANT_NOW,
          CLOCK_READ,
          LOCAL_DATE_TIME_NOW,
          ZONED_DATE_TIME_NOW,
          DATE_NEW,
          LIFECYCLE ->
          new IllegalStateException(message);
    };
  }

  /**
   * Whether the call is a {@code java.io} one ({@code FileInputStream} and the like), whose open
   * fails with {@link java.io.FileNotFoundException} rather than the {@code java.nio} exceptions.
   *
   * @param context the intercepted call
   * @return whether its owner class is in {@code java.io}
   */
  static boolean isJavaIo(final InvocationContext context) {
    final String owner = context.targetClassName();
    return owner != null && owner.startsWith("java.io.");
  }

  /**
   * Creates the exception the JDK raises when the operating system reports {@code errno} for an
   * operation of {@code operationType}.
   *
   * @param errno the error
   * @param operationType the failing operation
   * @param path the file of a file operation, or {@code null}
   * @param javaIo whether the call is a {@code java.io} one ({@code FileInputStream} and the like),
   *     whose open fails with {@link java.io.FileNotFoundException} rather than the {@code
   *     java.nio} exceptions
   * @return the exception, trimmed to the call site
   */
  static Throwable errno(
      final ChaosEffect.Errno errno,
      final OperationType operationType,
      final @Nullable String path,
      final boolean javaIo) {
    return atCallSite(errnoFailure(errno, operationType, path, javaIo));
  }

  private static Throwable errnoFailure(
      final ChaosEffect.Errno errno,
      final OperationType operationType,
      final @Nullable String path,
      final boolean javaIo) {
    final String reason = errno.reason();
    return switch (operationType) {
      // The JDK's own failure for a process that could not be started; path is the program.
      case PROCESS_START ->
          new java.io.IOException(
              "Cannot run program \"" + path + "\": error=" + errno.code() + ", " + reason);
      // What Thread.start throws when pthread_create fails.
      case THREAD_START -> new OutOfMemoryError(NATIVE_THREAD_FAILURE);
      case SOCKET_CONNECT,
          SOCKET_ACCEPT,
          SOCKET_READ,
          SOCKET_WRITE,
          SOCKET_CLOSE,
          SOCKET_BIND,
          SOCKET_SHUTDOWN,
          DATAGRAM_SEND,
          DATAGRAM_RECEIVE,
          NIO_CHANNEL_CONNECT,
          NIO_CHANNEL_ACCEPT,
          NIO_CHANNEL_READ,
          NIO_CHANNEL_WRITE ->
          networkErrno(errno, operationType, reason);
      default -> fileErrno(errno, operationType, path, javaIo, reason);
    };
  }

  /** The {@code java.net} failure of a socket call, as the JDK's {@code Net.c} maps errors. */
  private static Throwable networkErrno(
      final ChaosEffect.Errno errno, final OperationType operationType, final String reason) {
    // A read or write of an established connection reports the errno text as a SocketException
    // (the JDK wraps the native IOException); the specific java.net types belong to connecting.
    // A kernel ETIMEDOUT there means the connection is dead, unlike SocketTimeoutException
    // (SO_TIMEOUT expired, socket still usable), which ChaosEffect.timeout() raises.
    final boolean transfer =
        switch (operationType) {
          case SOCKET_READ, SOCKET_WRITE, NIO_CHANNEL_READ, NIO_CHANNEL_WRITE -> true;
          default -> false;
        };
    return switch (errno) {
      // A connected UDP socket reports an ICMP port unreachable as PortUnreachableException.
      case ECONNREFUSED ->
          operationType == OperationType.DATAGRAM_SEND
                  || operationType == OperationType.DATAGRAM_RECEIVE
              ? new java.net.PortUnreachableException(reason)
              : transfer
                  ? new java.net.SocketException(reason)
                  : new java.net.ConnectException(reason);
      case ETIMEDOUT ->
          switch (operationType) {
            case SOCKET_CONNECT, NIO_CHANNEL_CONNECT -> new java.net.ConnectException(reason);
            default -> new java.net.SocketException(reason);
          };
      case EHOSTUNREACH ->
          transfer
              ? new java.net.SocketException(reason)
              : new java.net.NoRouteToHostException(reason);
      case EADDRINUSE, EADDRNOTAVAIL, EACCES -> new java.net.BindException(reason);
      // An accept that cannot get a descriptor fails with a plain IOException in the JDK.
      case EMFILE, ENFILE ->
          operationType == OperationType.SOCKET_ACCEPT
                  || operationType == OperationType.NIO_CHANNEL_ACCEPT
              ? new java.io.IOException(reason)
              : new java.net.SocketException(reason);
      default -> new java.net.SocketException(reason);
    };
  }

  /**
   * The failure of a file call. A call on an open descriptor (read, write, truncate, map, close)
   * fails as {@code IOUtil} and {@code FileDispatcherImpl} report it: {@link java.io.IOException}
   * with the system's text. A call on a path (a {@code java.nio} open, delete, move) fails with the
   * {@code java.nio.file} exception naming the path.
   */
  private static Throwable fileErrno(
      final ChaosEffect.Errno errno,
      final OperationType operationType,
      final @Nullable String path,
      final boolean javaIo,
      final String reason) {
    if (operationType == OperationType.FILE_IO_SYNC) {
      // FileDescriptor.sync() declares only SyncFailedException; FileChannel.force any
      // IOException. SyncFailedException satisfies both.
      return new java.io.SyncFailedException(reason);
    }
    if (operationType == OperationType.FILE_IO_MAP && errno == ChaosEffect.Errno.ENOMEM) {
      // FileChannelImpl.map: an mmap that fails for lack of memory.
      return new java.io.IOException("Map failed", new OutOfMemoryError("Map failed"));
    }
    if (DESCRIPTOR_CALLS.contains(operationType)) {
      return new java.io.IOException(reason);
    }
    if (operationType == OperationType.FILE_IO_OPEN && javaIo) {
      // FileInputStream, FileOutputStream and RandomAccessFile constructors: "path (reason)".
      return new java.io.FileNotFoundException(path == null ? reason : path + " (" + reason + ")");
    }
    if (path == null) {
      return errno == ChaosEffect.Errno.ENOENT
          ? new java.io.FileNotFoundException(reason)
          : new java.io.IOException(reason);
    }
    return switch (errno) {
      case EACCES -> new java.nio.file.AccessDeniedException(path);
      case ENOENT -> new java.nio.file.NoSuchFileException(path);
      default -> new java.nio.file.FileSystemException(path, null, reason);
    };
  }

  /**
   * Creates the failure a client library raises for a rejected call, loaded through the client's
   * class loader so it is the library's own type: what the application's error handling catches.
   * Falls back to {@link IOException}, what a transport failure of any client is, when the library
   * type cannot be built (OkHttp and Netty fail with it anyway).
   *
   * @param operationType the client operation
   * @param client the client object the call was made on; its class loader sees the library
   * @param message the detail message
   * @return the failure to throw, trimmed to the call site
   */
  static Throwable clientReject(
      final OperationType operationType,
      final @Nullable Object client,
      final @Nullable String message) {
    return atCallSite(clientFailure(operationType, client, message));
  }

  private static Throwable clientFailure(
      final OperationType operationType,
      final @Nullable Object client,
      final @Nullable String message) {
    final ClassLoader loader = client == null ? null : client.getClass().getClassLoader();
    final String clientClass = client == null ? "" : client.getClass().getName();
    try {
      return switch (operationType) {
        case MESSAGE_SEND, MESSAGE_RECEIVE ->
            construct(loader, "org.apache.kafka.common.errors.TimeoutException", message);
        case RPC_CALL -> grpcUnavailable(loader, message);
        case REDIS_COMMAND ->
            construct(
                loader,
                clientClass.startsWith("io.lettuce.")
                    ? "io.lettuce.core.RedisCommandTimeoutException"
                    : "redis.clients.jedis.exceptions.JedisConnectionException",
                message);
        case R2DBC_STATEMENT_EXECUTE ->
            construct(loader, "io.r2dbc.spi.R2dbcTransientResourceException", message);
        case AWS_SDK_CALL ->
            (Throwable)
                Class.forName(
                        "software.amazon.awssdk.core.exception.SdkClientException", true, loader)
                    .getMethod("create", String.class)
                    .invoke(null, message);
        default -> new IOException(message);
      };
    } catch (final ReflectiveOperationException | LinkageError | ClassCastException unavailable) {
      return new IOException(message);
    }
  }

  private static Throwable construct(
      final @Nullable ClassLoader loader, final String className, final @Nullable String message)
      throws ReflectiveOperationException {
    return Class.forName(className, true, loader)
        .asSubclass(Throwable.class)
        .getConstructor(String.class)
        .newInstance(message);
  }

  private static Throwable grpcUnavailable(
      final @Nullable ClassLoader loader, final @Nullable String message)
      throws ReflectiveOperationException {
    final Class<?> status = Class.forName("io.grpc.Status", true, loader);
    final Object unavailable = status.getField("UNAVAILABLE").get(null);
    final Object described =
        status.getMethod("withDescription", String.class).invoke(unavailable, message);
    return (Throwable) status.getMethod("asRuntimeException").invoke(described);
  }

  /**
   * Creates a {@link Throwable} suitable for injecting a simulated {@link
   * java.util.concurrent.CompletableFuture} completion failure of the requested kind.
   *
   * <p>Each {@link ChaosEffect.FailureKind} maps to the exception type that best represents that
   * category of failure:
   *
   * <ul>
   *   <li>{@code TIMEOUT} → {@link java.util.concurrent.TimeoutException}
   *   <li>{@code REJECTED} → {@link java.util.concurrent.RejectedExecutionException}
   *   <li>{@code CLASS_NOT_FOUND}, {@code ILLEGAL_STATE} → {@link IllegalStateException}
   *   <li>{@code IO} → {@link java.io.IOException}
   *   <li>{@code INTERRUPTED} → {@link IllegalStateException} with an {@link InterruptedException}
   *       cause
   *   <li>{@code RUNTIME} → {@link RuntimeException}
   *   <li>{@code SECURITY} → {@link SecurityException}
   * </ul>
   *
   * @param failureKind the category of failure to simulate
   * @param message the detail message embedded in the constructed throwable
   * @return a new {@link Throwable} representing the requested failure kind, trimmed to the call
   *     site; never {@code null}
   */
  static Throwable completionFailure(
      final ChaosEffect.FailureKind failureKind, final String message) {
    return atCallSite(completion(failureKind, message));
  }

  private static Throwable completion(
      final ChaosEffect.FailureKind failureKind, final String message) {
    return switch (failureKind) {
      case TIMEOUT -> new TimeoutException(message);
      case REJECTED -> new RejectedExecutionException(message);
      case CLASS_NOT_FOUND ->
          new IllegalStateException(message, new ClassNotFoundException(message));
      case ILLEGAL_STATE -> new IllegalStateException(message);
      case IO -> new IOException(message);
      case INTERRUPTED -> new IllegalStateException(message, new InterruptedException(message));
      case RUNTIME -> new RuntimeException(message);
      case SECURITY -> new SecurityException(message);
    };
  }

  /**
   * The failure of a call whose own timeout ({@code SO_TIMEOUT}, the connect timeout) expired.
   *
   * @param operationType the call
   * @return the {@link java.net.SocketTimeoutException}, trimmed to the call site
   */
  static Throwable timeout(final OperationType operationType) {
    return atCallSite(
        new java.net.SocketTimeoutException(
            operationType == OperationType.SOCKET_CONNECT
                ? "Connect timed out"
                : "Read timed out"));
  }

  /**
   * The failure of a DNS answer a family filter left without an address: the lookup fails as one
   * for a name with no address of that family does.
   *
   * @param hostname the name looked up
   * @param family the family the answer was filtered to
   * @return the {@link java.net.UnknownHostException}, trimmed to the call site
   */
  static java.net.UnknownHostException noAddressOfFamily(
      final String hostname, final ChaosEffect.AddressFamily family) {
    return (java.net.UnknownHostException)
        atCallSite(
            new java.net.UnknownHostException(
                hostname + ": no " + family + " address in the answer"));
  }

  /**
   * The failure of a suppressed thread start: a start that cannot be skipped is refused.
   *
   * @return the {@link RejectedExecutionException}, trimmed to the call site
   */
  static Throwable suppressedStart() {
    return atCallSite(new RejectedExecutionException("thread start suppressed by chaos agent"));
  }

  /**
   * The failure of a suppressed {@code System.exit}: the exit is vetoed.
   *
   * @return the {@link SecurityException}, trimmed to the call site
   */
  static Throwable suppressedExit() {
    return atCallSite(new SecurityException("exit suppressed by chaos agent"));
  }

  /**
   * Resolves the exception class an {@link ChaosEffect.ExceptionInjectionEffect} names, through the
   * agent's class loader and without initialising it: a public, non-abstract {@link Throwable}
   * class with a public {@code (String)} or no-argument constructor. No access check is suppressed.
   * The validator calls it to refuse a class at activation; the scenario's controller keeps the
   * constructor it returns.
   *
   * @param className the class's binary name
   * @return the {@code (String)} constructor, or the no-argument one when there is none
   * @throws ChaosValidationException naming the class and why it cannot be injected
   */
  static Constructor<? extends Throwable> injectedConstructor(final String className) {
    final Class<?> type;
    try {
      type = Class.forName(className, false, ChaosDispatcher.class.getClassLoader());
    } catch (final ClassNotFoundException | LinkageError absent) {
      throw new ChaosValidationException(
          "exception class not found by the agent's class loader: " + className);
    }
    if (!Throwable.class.isAssignableFrom(type)) {
      throw new ChaosValidationException("exception class is not a Throwable: " + className);
    }
    if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())) {
      throw new ChaosValidationException(
          "exception class must be public and not abstract: " + className);
    }
    final Class<? extends Throwable> throwable = type.asSubclass(Throwable.class);
    try {
      return throwable.getConstructor(String.class);
    } catch (final NoSuchMethodException noMessage) {
      try {
        return throwable.getConstructor();
      } catch (final NoSuchMethodException none) {
        throw new ChaosValidationException(
            "exception class has no public (String) or no-argument constructor: " + className);
      }
    }
  }

  /**
   * Creates the exception an {@link ChaosEffect.ExceptionInjectionEffect} names, with its message,
   * through the constructor {@link #injectedConstructor} resolved at activation. Called for the
   * winning contribution only.
   *
   * @param effect the effect
   * @param constructor the class's {@code (String)} or no-argument constructor
   * @return the exception, trimmed to the call site, or without a stack trace when the effect asks
   *     for none; a {@link RuntimeException} naming the class when its constructor fails
   */
  static Throwable injected(
      final ChaosEffect.ExceptionInjectionEffect effect,
      final Constructor<? extends Throwable> constructor) {
    final Throwable instance;
    try {
      if (constructor.getParameterCount() == 1) {
        instance = constructor.newInstance(effect.message());
      } else {
        // No (String) constructor: the message is kept as the cause, so the scenario that fired
        // stays visible in logs.
        instance = constructor.newInstance();
        try {
          instance.initCause(new RuntimeException(effect.message()));
        } catch (final IllegalStateException alreadyInitialised) {
          // The no-arg constructor set its own cause; it is kept.
        }
      }
    } catch (final ReflectiveOperationException reflective) {
      return atCallSite(
          new RuntimeException(
              "[chaos-agent] failed to instantiate " + effect.exceptionClassName(), reflective));
    }
    if (!effect.withStackTrace()) {
      instance.setStackTrace(new StackTraceElement[0]);
      return instance;
    }
    return atCallSite(instance);
  }

  /**
   * Drops the agent's frames from the top of {@code failure}'s stack: the frames up to and
   * including the last frame of the bridge class (the woven method's call into the agent), or, when
   * there is none (a manual {@code ChaosRuntime} hook), up to and including the last frame of this
   * package. The top frame is then the intercepted method. Frames are never dropped by a package
   * prefix alone: the intercepted class itself may be in {@code com.macstab.chaos.jvm}.
   */
  private static Throwable atCallSite(final Throwable failure) {
    final StackTraceElement[] stack = failure.getStackTrace();
    int anchor = -1;
    for (int index = stack.length - 1; index >= 0 && anchor < 0; index--) {
      if (BRIDGE_CLASS.equals(stack[index].getClassName())) {
        anchor = index;
      }
    }
    for (int index = stack.length - 1; index >= 0 && anchor < 0; index--) {
      if (stack[index].getClassName().startsWith(CORE_PACKAGE)) {
        anchor = index;
      }
    }
    if (anchor >= 0 && anchor < stack.length - 1) {
      failure.setStackTrace(java.util.Arrays.copyOfRange(stack, anchor + 1, stack.length));
    }
    return failure;
  }

  private static Throwable createJndiNamingException(final @Nullable String message) {
    try {
      final Class<?> namingExceptionClass = Class.forName("javax.naming.NamingException");
      return (Throwable) namingExceptionClass.getConstructor(String.class).newInstance(message);
    } catch (final Exception ignored) {
      return new RuntimeException(message);
    }
  }

  private static Throwable createJmxMBeanException(final @Nullable String message) {
    try {
      final Class<?> mbeanExceptionClass = Class.forName("javax.management.MBeanException");
      return (Throwable)
          mbeanExceptionClass
              .getConstructor(Exception.class, String.class)
              .newInstance(new RuntimeException(message), message);
    } catch (final Exception ignored) {
      return new RuntimeException(message);
    }
  }

  private static Throwable createSslHandshakeException(final @Nullable String message) {
    try {
      final Class<?> sslHandshakeExceptionClass =
          Class.forName("javax.net.ssl.SSLHandshakeException");
      return (Throwable)
          sslHandshakeExceptionClass.getConstructor(String.class).newInstance(message);
    } catch (final Exception ignored) {
      return new java.io.IOException(message);
    }
  }

  private static Throwable createJdbcSqlException(final @Nullable String message) {
    try {
      final Class<?> sqlExceptionClass = Class.forName("java.sql.SQLException");
      return (Throwable) sqlExceptionClass.getConstructor(String.class).newInstance(message);
    } catch (final Exception ignored) {
      return new RuntimeException(message);
    }
  }
}

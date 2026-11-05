package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * Factory that constructs the appropriate {@link Throwable} for a chaos THROW decision based on the
 * {@link OperationType} of the intercepted operation.
 *
 * <p>Different operation types have different "natural" failure modes:
 *
 * <ul>
 *   <li>Network/socket operations fail with {@link java.io.IOException}.
 *   <li>JNDI lookups fail with {@code javax.naming.NamingException} (instantiated via reflection to
 *       avoid a hard dependency on {@code java.naming} when not present).
 *   <li>JMX operations fail with {@code javax.management.MBeanException} (similarly via
 *       reflection).
 *   <li>All other operations fall back to a generic {@link RuntimeException}.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is stateless; all methods are static and may be called concurrently without
 * synchronization.
 */
final class FailureFactory {
  private FailureFactory() {}

  /**
   * Creates the canonical {@link Throwable} that a chaos agent should throw when rejecting an
   * operation of the given type.
   *
   * <p>The returned exception type mirrors what the application would encounter under real-world
   * failure conditions for that operation kind (e.g. a {@link java.net.ConnectException} for a
   * socket-connect rejection rather than a generic {@link RuntimeException}).
   *
   * <p>For {@code JNDI_LOOKUP} and {@code JMX_INVOKE}/{@code JMX_GET_ATTR}, the exception is
   * instantiated via reflection so that the agent does not introduce a hard module dependency on
   * {@code java.naming} or {@code java.management}. If reflection fails (e.g. the module is
   * absent), the method falls back to {@link RuntimeException}.
   *
   * @param operationType the type of JVM operation being intercepted; determines the exception
   *     class to instantiate
   * @param message the detail message embedded in the constructed throwable
   * @return a new {@link Throwable} appropriate for rejecting {@code operationType}; never {@code
   *     null}
   */
  static Throwable reject(final OperationType operationType, final @Nullable String message) {
    return switch (operationType) {
      case CLASS_LOAD -> new ClassNotFoundException(message);
      case EXECUTOR_SUBMIT, THREAD_START, VIRTUAL_THREAD_START, SHUTDOWN_HOOK_REGISTER ->
          new RejectedExecutionException(message);
      case CLASS_DEFINE -> new ClassFormatError(message);
      case SOCKET_CONNECT -> new java.net.ConnectException(message);
      case SOCKET_READ -> new java.net.SocketTimeoutException(message);
      case SOCKET_WRITE -> new java.io.IOException(message);
      case NIO_CHANNEL_CONNECT, NIO_CHANNEL_READ, NIO_CHANNEL_WRITE, NIO_CHANNEL_ACCEPT ->
          new java.io.IOException(message);
      case OBJECT_DESERIALIZE -> new java.io.InvalidClassException(message);
      case OBJECT_SERIALIZE -> new java.io.NotSerializableException(message);
      case DIRECT_BUFFER_ALLOCATE -> new OutOfMemoryError(message);
      case NATIVE_LIBRARY_LOAD -> new UnsatisfiedLinkError(message);
      case JNDI_LOOKUP -> createJndiNamingException(message);
      case JMX_INVOKE, JMX_GET_ATTR -> createJmxMBeanException(message);
      // Operation types whose natural rejection is not IllegalStateException — mapped
      // explicitly so reject() produces the same throwable the application would observe
      // under a genuine failure of that kind, not a generic ISE.
      case SYSTEM_GC_REQUEST -> new OutOfMemoryError(message);
      case THREAD_SLEEP -> new InterruptedException(message);
      case THREAD_PARK -> new RuntimeException(message);
      case MONITOR_ENTER -> new IllegalMonitorStateException(message);
      case NIO_SELECTOR_SELECT -> new java.nio.channels.ClosedSelectorException();
      // Network/file I/O: callers wrap these in try/catch for IOException; IllegalStateException
      // fell out of those catch blocks and propagated up past the intended boundary, turning a
      // simulated I/O failure into a request-killing unchecked exception.
      case SOCKET_ACCEPT, SOCKET_CLOSE, FILE_IO_READ, FILE_IO_WRITE ->
          new java.io.IOException(message);
      // DNS: UnknownHostException is the canonical signal; ConnectException or generic IOE would
      // route fault-tolerance logic down the wrong path.
      case DNS_RESOLVE -> new java.net.UnknownHostException(message);
      // TLS handshake failure — construct reflectively so this module does not fail to load if
      // javax.net.ssl is absent from the runtime image (e.g. jlink slim images). Fall back to
      // plain IOException so the caller still sees an I/O failure rather than an ISE.
      case SSL_HANDSHAKE -> createSslHandshakeException(message);
      // JDBC: application code universally catches SQLException. Produce it reflectively to
      // avoid a hard dependency on java.sql at agent core load time.
      case JDBC_CONNECTION_ACQUIRE,
          JDBC_STATEMENT_EXECUTE,
          JDBC_PREPARED_STATEMENT,
          JDBC_TRANSACTION_COMMIT,
          JDBC_TRANSACTION_ROLLBACK ->
          createJdbcSqlException(message);
      // HTTP client: java.net.http.HttpClient#send declares IOException; async send completes
      // the future exceptionally with IOException as well. Keep the expected checked type.
      case HTTP_CLIENT_SEND, HTTP_CLIENT_SEND_ASYNC -> new java.io.IOException(message);
      // Netty surfaces transport failures as IOException through the pipeline.
      case NETTY_CHANNEL_WRITE, NETTY_CHANNEL_READ -> new java.io.IOException(message);
      // FileDescriptor.sync() declares only SyncFailedException; FileChannel.force any
      // IOException. SyncFailedException satisfies both.
      case FILE_IO_SYNC -> new java.io.SyncFailedException(message);
      case FILE_IO_TRUNCATE, FILE_IO_DELETE, FILE_IO_MOVE, PROCESS_START ->
          new java.io.IOException(message);
      // FileNotFoundException is what java.io constructors declare, and an IOException for nio.
      case FILE_IO_OPEN -> new java.io.FileNotFoundException(message);
      case FILE_IO_MAP, FILE_IO_CLOSE, FILE_IO_MOVE_TARGET -> new java.io.IOException(message);
      case SOCKET_SHUTDOWN -> new java.net.SocketException(message);
      case PROCESS_WAIT -> new InterruptedException(message);
      case SOCKET_BIND -> new java.net.BindException(message);
      case DATAGRAM_SEND, DATAGRAM_RECEIVE -> new java.io.IOException(message);
      case DNS_REVERSE -> new java.net.UnknownHostException(message);
      default -> new IllegalStateException(message);
    };
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
   * @return the exception
   */
  static Throwable errno(
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
      case THREAD_START ->
          new OutOfMemoryError(
              "unable to create native thread: possibly out of memory or process/resource limits"
                  + " reached");
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
    return switch (errno) {
      // A connected UDP socket reports an ICMP port unreachable as PortUnreachableException.
      case ECONNREFUSED ->
          operationType == OperationType.DATAGRAM_SEND
                  || operationType == OperationType.DATAGRAM_RECEIVE
              ? new java.net.PortUnreachableException(reason)
              : new java.net.ConnectException(reason);
      case ETIMEDOUT ->
          switch (operationType) {
            case SOCKET_CONNECT, NIO_CHANNEL_CONNECT -> new java.net.ConnectException(reason);
            case SOCKET_READ, NIO_CHANNEL_READ ->
                new java.net.SocketTimeoutException("Read timed out");
            // A write or close that times out reports the errno text, as the JDK does.
            default -> new java.net.SocketException(reason);
          };
      case EHOSTUNREACH -> new java.net.NoRouteToHostException(reason);
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

  /** The failure of a file call: {@code java.nio.file} exceptions naming the path. */
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
    if (operationType == OperationType.FILE_IO_CLOSE && javaIo) {
      // FileInputStream.close and the like report the error text, without the path.
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
   * Falls back to {@link #reject} when the library type cannot be built.
   *
   * @param operationType the client operation
   * @param client the client object the call was made on; its class loader sees the library
   * @param message the detail message
   * @return the failure to throw
   */
  static Throwable clientReject(
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
        default -> reject(operationType, message);
      };
    } catch (final ReflectiveOperationException | LinkageError | ClassCastException unavailable) {
      return reject(operationType, message);
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
   * @return a new {@link Throwable} representing the requested failure kind; never {@code null}
   */
  static Throwable completionFailure(
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

package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.OperationType;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

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
  static Throwable reject(final OperationType operationType, final String message) {
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
      case JNDI_LOOKUP -> {
        try {
          // javax.naming.NamingException has a (String) constructor
          yield (Throwable)
              Class.forName("javax.naming.NamingException")
                  .getConstructor(String.class)
                  .newInstance(message);
        } catch (Exception ex) {
          yield new RuntimeException(message);
        }
      }
      case JMX_INVOKE, JMX_GET_ATTR -> {
        try {
          yield (Throwable)
              Class.forName("javax.management.MBeanException")
                  .getConstructor(Exception.class, String.class)
                  .newInstance(new RuntimeException(message), message);
        } catch (Exception ex) {
          yield new RuntimeException(message);
        }
      }
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
      case SSL_HANDSHAKE -> {
        try {
          yield (Throwable)
              Class.forName("javax.net.ssl.SSLHandshakeException")
                  .getConstructor(String.class)
                  .newInstance(message);
        } catch (Exception ex) {
          yield new java.io.IOException(message);
        }
      }
      // JDBC: application code universally catches SQLException. Produce it reflectively to
      // avoid a hard dependency on java.sql at agent core load time.
      case JDBC_CONNECTION_ACQUIRE,
          JDBC_STATEMENT_EXECUTE,
          JDBC_PREPARED_STATEMENT,
          JDBC_TRANSACTION_COMMIT,
          JDBC_TRANSACTION_ROLLBACK -> {
        try {
          yield (Throwable)
              Class.forName("java.sql.SQLException")
                  .getConstructor(String.class)
                  .newInstance(message);
        } catch (Exception ex) {
          yield new RuntimeException(message);
        }
      }
      // HTTP client: java.net.http.HttpClient#send declares IOException; async send completes
      // the future exceptionally with IOException as well. Keep the expected checked type.
      case HTTP_CLIENT_SEND, HTTP_CLIENT_SEND_ASYNC -> new java.io.IOException(message);
      default -> new IllegalStateException(message);
    };
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
   *   <li>{@code INTERRUPTED} → {@link IllegalStateException} (wraps the interrupt semantics)
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
      case CLASS_NOT_FOUND, ILLEGAL_STATE -> new IllegalStateException(message);
      case IO -> new IOException(message);
      case INTERRUPTED -> new IllegalStateException("interrupted: " + message);
      case RUNTIME -> new RuntimeException(message);
      case SECURITY -> new SecurityException(message);
    };
  }
}

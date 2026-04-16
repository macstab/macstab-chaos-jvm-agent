package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.OperationType;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

final class FailureFactory {
  private FailureFactory() {}

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
      default -> new IllegalStateException(message);
    };
  }

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

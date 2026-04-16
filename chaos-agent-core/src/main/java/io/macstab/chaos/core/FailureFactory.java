package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.OperationType;
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

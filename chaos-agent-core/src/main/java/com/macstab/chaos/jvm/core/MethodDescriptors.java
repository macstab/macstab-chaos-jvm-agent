package com.macstab.chaos.jvm.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the return type of a woven method from its JVM descriptor, for return-value corruption.
 * The advice passes the descriptor as a constant string rather than a {@code Method} object, which
 * ByteBuddy would look up reflectively on every call.
 */
final class MethodDescriptors {

  private static final ClassValue<Map<String, Class<?>>> RETURN_TYPES =
      new ClassValue<>() {
        @Override
        protected Map<String, Class<?>> computeValue(final Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  /**
   * The return type of a method whose declared return type cannot be loaded: a value of it cannot
   * be corrupted safely, so the corruption is not applicable. Distinct from every real type, {@code
   * Object.class} included.
   */
  static final Class<?> UNRESOLVED = Unresolved.class;

  /** The type behind {@link #UNRESOLVED}; never instantiated. */
  private static final class Unresolved {
    private Unresolved() {}
  }

  private MethodDescriptors() {}

  /**
   * Returns the return type named by {@code descriptor}, loaded through the declaring type's class
   * loader, or {@link #UNRESOLVED} when it cannot be loaded.
   *
   * @param declaringType the class declaring the method
   * @param descriptor the method descriptor, e.g. {@code (Ljava/lang/String;)I}
   * @return the return type; {@code void.class} for a void method
   */
  static Class<?> returnType(final Class<?> declaringType, final String descriptor) {
    final int close = descriptor.lastIndexOf(')');
    final String returnDescriptor = descriptor.substring(close + 1);
    return RETURN_TYPES
        .get(declaringType)
        .computeIfAbsent(returnDescriptor, ignored -> load(declaringType, returnDescriptor));
  }

  private static Class<?> load(final Class<?> declaringType, final String returnDescriptor) {
    final Class<?> primitive =
        switch (returnDescriptor) {
          case "V" -> void.class;
          case "Z" -> boolean.class;
          case "B" -> byte.class;
          case "C" -> char.class;
          case "S" -> short.class;
          case "I" -> int.class;
          case "J" -> long.class;
          case "F" -> float.class;
          case "D" -> double.class;
          default -> null;
        };
    if (primitive != null) {
      return primitive;
    }
    final String binaryName =
        returnDescriptor.startsWith("[")
            ? returnDescriptor.replace('/', '.')
            : returnDescriptor.substring(1, returnDescriptor.length() - 1).replace('/', '.');
    try {
      return Class.forName(binaryName, false, declaringType.getClassLoader());
    } catch (final ClassNotFoundException | LinkageError notLoadable) {
      return UNRESOLVED;
    }
  }
}

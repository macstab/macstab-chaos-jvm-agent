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

  private MethodDescriptors() {}

  /**
   * Returns the return type named by {@code descriptor}, loaded through the declaring type's class
   * loader, or {@code Object.class} when it cannot be loaded.
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
    switch (returnDescriptor) {
      case "V":
        return void.class;
      case "Z":
        return boolean.class;
      case "B":
        return byte.class;
      case "C":
        return char.class;
      case "S":
        return short.class;
      case "I":
        return int.class;
      case "J":
        return long.class;
      case "F":
        return float.class;
      case "D":
        return double.class;
      default:
        break;
    }
    final String binaryName =
        returnDescriptor.startsWith("[")
            ? returnDescriptor.replace('/', '.')
            : returnDescriptor.substring(1, returnDescriptor.length() - 1).replace('/', '.');
    try {
      return Class.forName(binaryName, false, declaringType.getClassLoader());
    } catch (final ClassNotFoundException | LinkageError notLoadable) {
      return Object.class;
    }
  }
}

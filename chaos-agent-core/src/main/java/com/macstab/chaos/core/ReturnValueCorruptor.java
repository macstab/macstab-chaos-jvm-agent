package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Produces corrupted return values according to a {@link ChaosEffect.ReturnValueStrategy}.
 *
 * <p>When the requested strategy is inapplicable to the actual return type (for example, {@link
 * ChaosEffect.ReturnValueStrategy#EMPTY} on a primitive), the corruptor falls back to {@link
 * ChaosEffect.ReturnValueStrategy#ZERO} and logs the substitution at FINE level via the {@code
 * com.macstab.chaos} logger so tests and operators can observe it.
 *
 * <p>Primitive types are represented by their wrapper equivalents when passed as {@code
 * actualValue} — the caller is responsible for boxing before invocation and unboxing after.
 */
final class ReturnValueCorruptor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private ReturnValueCorruptor() {}

  /**
   * Corrupts {@code actualValue} according to the given {@code strategy}, using {@code returnType}
   * to choose an appropriate substitution.
   *
   * @param strategy the corruption strategy to apply
   * @param returnType the declared return type of the instrumented method; used for type-aware
   *     substitutions (EMPTY, BOUNDARY_MAX, BOUNDARY_MIN)
   * @param actualValue the original return value; may be {@code null} for reference types
   * @param scenarioId the scenario ID, used in fallback log messages
   * @return the corrupted value, or {@code null} when NULL strategy is applied to a reference type
   */
  static Object corrupt(
      final ChaosEffect.ReturnValueStrategy strategy,
      final Class<?> returnType,
      final Object actualValue,
      final String scenarioId) {
    return switch (strategy) {
      case NULL -> corruptNull(returnType, actualValue, scenarioId);
      case ZERO -> corruptZero(returnType);
      case EMPTY -> corruptEmpty(returnType, scenarioId);
      case BOUNDARY_MAX -> corruptBoundaryMax(returnType, scenarioId);
      case BOUNDARY_MIN -> corruptBoundaryMin(returnType, scenarioId);
    };
  }

  private static Object corruptNull(
      final Class<?> returnType, final Object actualValue, final String scenarioId) {
    if (returnType.isPrimitive()) {
      LOGGER.fine(
          () ->
              "ReturnValueCorruption NULL is inapplicable to primitive "
                  + returnType.getName()
                  + " in scenario "
                  + scenarioId
                  + "; falling back to ZERO");
      return corruptZero(returnType);
    }
    return null;
  }

  private static Object corruptZero(final Class<?> returnType) {
    if (returnType == boolean.class || returnType == Boolean.class) {
      return Boolean.FALSE;
    }
    if (returnType == byte.class || returnType == Byte.class) {
      return (byte) 0;
    }
    if (returnType == short.class || returnType == Short.class) {
      return (short) 0;
    }
    if (returnType == int.class || returnType == Integer.class) {
      return 0;
    }
    if (returnType == long.class || returnType == Long.class) {
      return 0L;
    }
    if (returnType == float.class || returnType == Float.class) {
      return 0.0f;
    }
    if (returnType == double.class || returnType == Double.class) {
      return 0.0d;
    }
    if (returnType == char.class || returnType == Character.class) {
      return '\0';
    }
    // Reference type: return null as the closest representation of "zero"
    return null;
  }

  private static Object corruptEmpty(final Class<?> returnType, final String scenarioId) {
    if (returnType == String.class) {
      return "";
    }
    if (Optional.class.isAssignableFrom(returnType)) {
      return Optional.empty();
    }
    if (List.class.isAssignableFrom(returnType)) {
      return Collections.emptyList();
    }
    if (Set.class.isAssignableFrom(returnType)) {
      return Collections.emptySet();
    }
    if (Map.class.isAssignableFrom(returnType)) {
      return Collections.emptyMap();
    }
    if (Collection.class.isAssignableFrom(returnType)) {
      return Collections.emptyList();
    }
    // Fallback: primitives and unrecognised reference types use ZERO
    LOGGER.fine(
        () ->
            "ReturnValueCorruption EMPTY is inapplicable to "
                + returnType.getName()
                + " in scenario "
                + scenarioId
                + "; falling back to ZERO");
    return corruptZero(returnType);
  }

  private static Object corruptBoundaryMax(final Class<?> returnType, final String scenarioId) {
    if (returnType == int.class || returnType == Integer.class) {
      return Integer.MAX_VALUE;
    }
    if (returnType == long.class || returnType == Long.class) {
      return Long.MAX_VALUE;
    }
    if (returnType == short.class || returnType == Short.class) {
      return Short.MAX_VALUE;
    }
    if (returnType == byte.class || returnType == Byte.class) {
      return Byte.MAX_VALUE;
    }
    if (returnType == float.class || returnType == Float.class) {
      return Float.MAX_VALUE;
    }
    if (returnType == double.class || returnType == Double.class) {
      return Double.MAX_VALUE;
    }
    if (returnType == char.class || returnType == Character.class) {
      return Character.MAX_VALUE;
    }
    LOGGER.fine(
        () ->
            "ReturnValueCorruption BOUNDARY_MAX is inapplicable to "
                + returnType.getName()
                + " in scenario "
                + scenarioId
                + "; falling back to ZERO");
    return corruptZero(returnType);
  }

  private static Object corruptBoundaryMin(final Class<?> returnType, final String scenarioId) {
    if (returnType == int.class || returnType == Integer.class) {
      return Integer.MIN_VALUE;
    }
    if (returnType == long.class || returnType == Long.class) {
      return Long.MIN_VALUE;
    }
    if (returnType == short.class || returnType == Short.class) {
      return Short.MIN_VALUE;
    }
    if (returnType == byte.class || returnType == Byte.class) {
      return Byte.MIN_VALUE;
    }
    if (returnType == float.class || returnType == Float.class) {
      return -Float.MAX_VALUE;
    }
    if (returnType == double.class || returnType == Double.class) {
      return -Double.MAX_VALUE;
    }
    if (returnType == char.class || returnType == Character.class) {
      return Character.MIN_VALUE;
    }
    LOGGER.fine(
        () ->
            "ReturnValueCorruption BOUNDARY_MIN is inapplicable to "
                + returnType.getName()
                + " in scenario "
                + scenarioId
                + "; falling back to ZERO");
    return corruptZero(returnType);
  }
}

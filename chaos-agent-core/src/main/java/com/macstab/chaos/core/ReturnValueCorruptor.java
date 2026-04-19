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

  private static final Map<Class<?>, Object> ZERO_VALUES =
      Map.ofEntries(
          Map.entry(boolean.class, Boolean.FALSE),
          Map.entry(Boolean.class, Boolean.FALSE),
          Map.entry(byte.class, (byte) 0),
          Map.entry(Byte.class, (byte) 0),
          Map.entry(short.class, (short) 0),
          Map.entry(Short.class, (short) 0),
          Map.entry(int.class, 0),
          Map.entry(Integer.class, 0),
          Map.entry(long.class, 0L),
          Map.entry(Long.class, 0L),
          Map.entry(float.class, 0.0f),
          Map.entry(Float.class, 0.0f),
          Map.entry(double.class, 0.0d),
          Map.entry(Double.class, 0.0d),
          Map.entry(char.class, '\0'),
          Map.entry(Character.class, '\0'));

  private static final Map<Class<?>, Object> BOUNDARY_MAX_VALUES =
      Map.ofEntries(
          Map.entry(byte.class, Byte.MAX_VALUE),
          Map.entry(Byte.class, Byte.MAX_VALUE),
          Map.entry(short.class, Short.MAX_VALUE),
          Map.entry(Short.class, Short.MAX_VALUE),
          Map.entry(int.class, Integer.MAX_VALUE),
          Map.entry(Integer.class, Integer.MAX_VALUE),
          Map.entry(long.class, Long.MAX_VALUE),
          Map.entry(Long.class, Long.MAX_VALUE),
          Map.entry(float.class, Float.MAX_VALUE),
          Map.entry(Float.class, Float.MAX_VALUE),
          Map.entry(double.class, Double.MAX_VALUE),
          Map.entry(Double.class, Double.MAX_VALUE),
          Map.entry(char.class, Character.MAX_VALUE),
          Map.entry(Character.class, Character.MAX_VALUE));

  private static final Map<Class<?>, Object> BOUNDARY_MIN_VALUES =
      Map.ofEntries(
          Map.entry(byte.class, Byte.MIN_VALUE),
          Map.entry(Byte.class, Byte.MIN_VALUE),
          Map.entry(short.class, Short.MIN_VALUE),
          Map.entry(Short.class, Short.MIN_VALUE),
          Map.entry(int.class, Integer.MIN_VALUE),
          Map.entry(Integer.class, Integer.MIN_VALUE),
          Map.entry(long.class, Long.MIN_VALUE),
          Map.entry(Long.class, Long.MIN_VALUE),
          Map.entry(float.class, -Float.MAX_VALUE),
          Map.entry(Float.class, -Float.MAX_VALUE),
          Map.entry(double.class, -Double.MAX_VALUE),
          Map.entry(Double.class, -Double.MAX_VALUE),
          Map.entry(char.class, Character.MIN_VALUE),
          Map.entry(Character.class, Character.MIN_VALUE));

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
      case BOUNDARY_MAX ->
          corruptBoundary(BOUNDARY_MAX_VALUES, "BOUNDARY_MAX", returnType, scenarioId);
      case BOUNDARY_MIN ->
          corruptBoundary(BOUNDARY_MIN_VALUES, "BOUNDARY_MIN", returnType, scenarioId);
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
    // Reference types not in the map return null — the closest representation of zero
    return ZERO_VALUES.getOrDefault(returnType, null);
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

  private static Object corruptBoundary(
      final Map<Class<?>, Object> boundaryValues,
      final String strategyName,
      final Class<?> returnType,
      final String scenarioId) {
    final Object value = boundaryValues.get(returnType);
    if (value != null) {
      return value;
    }
    LOGGER.fine(
        () ->
            "ReturnValueCorruption "
                + strategyName
                + " is inapplicable to "
                + returnType.getName()
                + " in scenario "
                + scenarioId
                + "; falling back to ZERO");
    return corruptZero(returnType);
  }
}

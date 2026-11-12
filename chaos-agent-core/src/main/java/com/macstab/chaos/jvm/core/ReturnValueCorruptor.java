package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Produces corrupted return values according to a {@link ChaosEffect.ReturnValueStrategy}.
 *
 * <p>When the requested strategy is inapplicable to the actual return type (for example, {@link
 * ChaosEffect.ReturnValueStrategy#EMPTY} on a primitive), the corruptor falls back to {@link
 * ChaosEffect.ReturnValueStrategy#ZERO} and logs the substitution at FINE level via the {@code
 * com.macstab.chaos.jvm} logger so tests and operators can observe it.
 *
 * <p>Primitive types are represented by their wrapper equivalents when passed as {@code
 * actualValue} — the caller is responsible for boxing before invocation and unboxing after.
 */
final class ReturnValueCorruptor {

  private static final AgentLog LOGGER = AgentLog.named("com.macstab.chaos.jvm");

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
  static @Nullable Object corrupt(
      final ChaosEffect.ReturnValueStrategy strategy,
      final @Nullable Class<?> returnType,
      final @Nullable Object actualValue,
      final String scenarioId) {
    // The dispatch decides a void or unknown return type is not applicable before it counts the
    // corruption (DispatchEngine.terminalActionFor), so no such call reaches here; a direct caller
    // passing null gets the value back unchanged instead of a NullPointerException.
    if (returnType == null) {
      return actualValue;
    }
    return switch (strategy) {
      case NULL -> corruptNull(returnType, scenarioId);
      case ZERO -> corruptZero(returnType);
      case EMPTY -> corruptEmpty(returnType, scenarioId);
      case BOUNDARY_MAX ->
          corruptBoundary(BOUNDARY_MAX_VALUES, "BOUNDARY_MAX", returnType, scenarioId);
      case BOUNDARY_MIN ->
          corruptBoundary(BOUNDARY_MIN_VALUES, "BOUNDARY_MIN", returnType, scenarioId);
    };
  }

  private static @Nullable Object corruptNull(final Class<?> returnType, final String scenarioId) {
    if (returnType.isPrimitive()) {
      LOGGER.fine(
          () ->
              "[chaos-agent] ReturnValueCorruption NULL is inapplicable to primitive "
                  + returnType.getName()
                  + " in scenario "
                  + scenarioId
                  + "; falling back to ZERO");
      return corruptZero(returnType);
    }
    return null;
  }

  private static @Nullable Object corruptZero(final Class<?> returnType) {
    // Reference types not in the map return null — the closest representation of zero
    return ZERO_VALUES.getOrDefault(returnType, null);
  }

  private static @Nullable Object corruptEmpty(final Class<?> returnType, final String scenarioId) {
    Object result;
    if ((result = tryEmptyString(returnType)) != null) {
      return result;
    }
    if (returnType.isArray()) {
      return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
    }
    if ((result = EMPTY_VALUES.get(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyStream(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyContainer(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyOptional(returnType)) != null) {
      return result;
    }
    // Fallback: primitives, unrecognised reference types, and concrete subtypes we cannot
    // safely substitute use ZERO.
    LOGGER.fine(
        () ->
            "[chaos-agent] ReturnValueCorruption EMPTY is inapplicable to "
                + returnType.getName()
                + " in scenario "
                + scenarioId
                + "; falling back to ZERO");
    return corruptZero(returnType);
  }

  /**
   * Empty String fallback. Returns {@code ""} for {@code String} and {@code CharSequence}, else
   * {@code null}.
   */
  private static @Nullable Object tryEmptyString(final Class<?> returnType) {
    if (returnType == String.class || returnType == CharSequence.class) {
      return "";
    }
    return null;
  }

  /** The empty primitive optionals: final value types, so one shared instance each. */
  private static final Map<Class<?>, Object> EMPTY_VALUES =
      Map.of(
          java.util.OptionalInt.class, java.util.OptionalInt.empty(),
          java.util.OptionalLong.class, java.util.OptionalLong.empty(),
          java.util.OptionalDouble.class, java.util.OptionalDouble.empty());

  /**
   * Fresh, mutable, empty containers in order of preference. For an interface or abstract return
   * type the first one assignable to it wins (List → ArrayList, Queue → LinkedList, BlockingQueue →
   * LinkedBlockingQueue, ConcurrentNavigableMap → ConcurrentSkipListMap, ...); for a concrete type
   * only an instance of exactly that type or a subtype fits. Mutable instances are used because
   * callers may add to the result.
   */
  // Every concrete container type a method may declare; LinkedList is one of them.
  @SuppressWarnings("JdkObsolete")
  private static final List<Supplier<Object>> EMPTY_CONTAINERS =
      List.of(
          ArrayList::new,
          LinkedList::new,
          ArrayDeque::new,
          LinkedHashSet::new,
          HashSet::new,
          TreeSet::new,
          ConcurrentSkipListSet::new,
          CopyOnWriteArrayList::new,
          CopyOnWriteArraySet::new,
          LinkedBlockingQueue::new,
          LinkedBlockingDeque::new,
          PriorityQueue::new,
          PriorityBlockingQueue::new,
          LinkedHashMap::new,
          HashMap::new,
          TreeMap::new,
          ConcurrentHashMap::new,
          ConcurrentSkipListMap::new);

  /**
   * Returns an empty collection or map that is assignable to {@code returnType}, or {@code null}.
   *
   * <p>ByteBuddy emits a checkcast to the declared type where the corrupted value is written, so
   * the substitute must be an instance of that type. The test runs in this direction because the
   * opposite one ({@code ArrayList.class.isAssignableFrom(returnType)}) is also true for
   * subclasses: a method declared to return {@code javax.management.AttributeList} would get a
   * plain {@code ArrayList}, and {@code BlockingQueue} or {@code ConcurrentNavigableMap} a {@code
   * LinkedList} or {@code TreeMap}, all failing with {@code ClassCastException} at the call site.
   */
  private static @Nullable Object tryEmptyContainer(final Class<?> returnType) {
    if (!Collection.class.isAssignableFrom(returnType)
        && !Map.class.isAssignableFrom(returnType)
        && returnType != Iterable.class) {
      return null;
    }
    for (final Supplier<Object> candidate : EMPTY_CONTAINERS) {
      final Object empty = candidate.get();
      if (returnType.isInstance(empty)) {
        return empty;
      }
    }
    return null;
  }

  /** A fresh empty stream for each stream type, or {@code null}. */
  private static @Nullable Object tryEmptyStream(final Class<?> returnType) {
    if (returnType == java.util.stream.Stream.class) {
      return java.util.stream.Stream.empty();
    }
    if (returnType == java.util.stream.IntStream.class) {
      return java.util.stream.IntStream.empty();
    }
    if (returnType == java.util.stream.LongStream.class) {
      return java.util.stream.LongStream.empty();
    }
    if (returnType == java.util.stream.DoubleStream.class) {
      return java.util.stream.DoubleStream.empty();
    }
    return null;
  }

  /** Optional is a value type; {@link Optional#empty()} is always assignable-compatible. */
  private static @Nullable Object tryEmptyOptional(final Class<?> returnType) {
    if (Optional.class.isAssignableFrom(returnType)) {
      return Optional.empty();
    }
    return null;
  }

  private static @Nullable Object corruptBoundary(
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
            "[chaos-agent] ReturnValueCorruption "
                + strategyName
                + " is inapplicable to "
                + returnType.getName()
                + " in scenario "
                + scenarioId
                + "; falling back to ZERO");
    return corruptZero(returnType);
  }
}

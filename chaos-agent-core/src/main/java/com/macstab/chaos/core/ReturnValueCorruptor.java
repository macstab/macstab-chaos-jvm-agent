package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
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
    // ByteBuddy cannot always statically bind a declared return class at the advice site: void
    // methods, bridge methods, and dynamically-generated lambdas arrive here with returnType ==
    // null. Every strategy below dereferences returnType (isPrimitive, getName, map lookup), so
    // without this guard we NPE from inside chaos advice — indistinguishable from a bug in the
    // instrumented method. Pass the value through unchanged: nothing to corrupt for a void
    // return, and the caller sees "no chaos applied" rather than an inexplicable crash.
    if (returnType == null) {
      return actualValue;
    }
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
    Object result;
    if ((result = tryEmptyString(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyConcreteCollection(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyConcreteMap(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyCollectionSingleton(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyQueueOrDeque(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptySortedInterface(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyConcurrentMap(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyCollectionOrIterable(returnType)) != null) {
      return result;
    }
    if ((result = tryEmptyOptional(returnType)) != null) {
      return result;
    }
    // Fallback: primitives, unrecognised reference types, and concrete subtypes we cannot
    // safely substitute use ZERO.
    LOGGER.fine(
        () ->
            "ReturnValueCorruption EMPTY is inapplicable to "
                + returnType.getName()
                + " in scenario "
                + scenarioId
                + "; falling back to ZERO");
    return corruptZero(returnType);
  }

  /** Empty String fallback. Returns {@code ""} for {@code String.class}, else {@code null}. */
  private static Object tryEmptyString(final Class<?> returnType) {
    if (returnType == String.class) {
      return "";
    }
    return null;
  }

  /**
   * For concrete mutable collection types, returns a fresh mutable empty instance that is
   * assignment-compatible with the declared return type. ByteBuddy emits an implicit checkcast at
   * the {@code @Advice.Return} write site, so returning an immutable singleton (e.g. {@code
   * Collections.emptyList()} whose concrete type is {@code ImmutableCollections$ListN}) for a
   * method declared to return {@code ArrayList<T>} causes a {@code ClassCastException} at the call
   * site. Check concrete types first (most specific to least specific) before interface checks.
   *
   * <p>Order matters: {@code LinkedHashSet} extends {@code HashSet}, so the LinkedHashSet check
   * must come first. ConcurrentSkipList* implement the sorted/navigable interfaces so they get
   * caught by the interface checks below if they themselves are the declared type; we add dedicated
   * checks because the declared-return-type contract can name them concretely in the method
   * signature.
   */
  private static Object tryEmptyConcreteCollection(final Class<?> returnType) {
    if (ArrayList.class.isAssignableFrom(returnType)) {
      return new ArrayList<>();
    }
    if (LinkedList.class.isAssignableFrom(returnType)) {
      return new LinkedList<>();
    }
    if (ArrayDeque.class.isAssignableFrom(returnType)) {
      return new ArrayDeque<>();
    }
    if (LinkedHashSet.class.isAssignableFrom(returnType)) {
      return new LinkedHashSet<>();
    }
    if (HashSet.class.isAssignableFrom(returnType)) {
      return new HashSet<>();
    }
    if (TreeSet.class.isAssignableFrom(returnType)) {
      return new TreeSet<>();
    }
    if (ConcurrentSkipListSet.class.isAssignableFrom(returnType)) {
      return new ConcurrentSkipListSet<>();
    }
    return null;
  }

  /**
   * Concrete Map types. Order matters: {@code LinkedHashMap} extends {@code HashMap}, so the
   * LinkedHashMap check must come first. See {@link #tryEmptyConcreteCollection} for the checkcast
   * rationale.
   */
  private static Object tryEmptyConcreteMap(final Class<?> returnType) {
    if (LinkedHashMap.class.isAssignableFrom(returnType)) {
      return new LinkedHashMap<>();
    }
    if (HashMap.class.isAssignableFrom(returnType)) {
      return new HashMap<>();
    }
    if (TreeMap.class.isAssignableFrom(returnType)) {
      return new TreeMap<>();
    }
    if (ConcurrentSkipListMap.class.isAssignableFrom(returnType)) {
      return new ConcurrentSkipListMap<>();
    }
    if (ConcurrentHashMap.class.isAssignableFrom(returnType)) {
      return new ConcurrentHashMap<>();
    }
    return null;
  }

  /**
   * For interface / abstract types, the immutable singletons are safe because the declared return
   * type does not require a specific concrete class — only the interface compatibility.
   * Double-check: the singleton's concrete class must be assignable to the declared return type to
   * satisfy the checkcast at the advice write site.
   */
  private static Object tryEmptyCollectionSingleton(final Class<?> returnType) {
    if (returnType.isAssignableFrom(java.util.Collections.emptyList().getClass())
        && List.class.isAssignableFrom(returnType)) {
      return Collections.emptyList();
    }
    if (returnType.isAssignableFrom(java.util.Collections.emptySet().getClass())
        && Set.class.isAssignableFrom(returnType)) {
      return Collections.emptySet();
    }
    if (returnType.isAssignableFrom(java.util.Collections.emptyMap().getClass())
        && Map.class.isAssignableFrom(returnType)) {
      return Collections.emptyMap();
    }
    return null;
  }

  /**
   * Queue and Deque interfaces are not backed by a {@code Collections.empty*} singleton, so fall
   * back to mutable-but-empty concrete implementations. Deque is a sub-interface of Queue, so check
   * it first.
   */
  private static Object tryEmptyQueueOrDeque(final Class<?> returnType) {
    if (Deque.class.isAssignableFrom(returnType)) {
      return new ArrayDeque<>();
    }
    if (Queue.class.isAssignableFrom(returnType)) {
      return new LinkedList<>();
    }
    return null;
  }

  /**
   * SortedMap / NavigableMap / SortedSet / NavigableSet have no {@code Collections.empty*}
   * singleton that satisfies the interface. {@code Collections.emptySortedMap()} etc. do exist but
   * returning a mutable instance is safer because callers may mutate the result.
   */
  private static Object tryEmptySortedInterface(final Class<?> returnType) {
    if (NavigableMap.class.isAssignableFrom(returnType)
        || SortedMap.class.isAssignableFrom(returnType)) {
      return new TreeMap<>();
    }
    if (NavigableSet.class.isAssignableFrom(returnType)
        || SortedSet.class.isAssignableFrom(returnType)) {
      return new TreeSet<>();
    }
    return null;
  }

  /** ConcurrentMap interface fallback. */
  private static Object tryEmptyConcurrentMap(final Class<?> returnType) {
    if (ConcurrentMap.class.isAssignableFrom(returnType)) {
      return new ConcurrentHashMap<>();
    }
    return null;
  }

  /** Collection / Iterable fallback to {@link Collections#emptyList()}. */
  private static Object tryEmptyCollectionOrIterable(final Class<?> returnType) {
    if (returnType.isAssignableFrom(java.util.Collections.emptyList().getClass())
        && Collection.class.isAssignableFrom(returnType)) {
      return Collections.emptyList();
    }
    if (Iterable.class.isAssignableFrom(returnType)
        && returnType.isAssignableFrom(java.util.Collections.emptyList().getClass())) {
      return Collections.emptyList();
    }
    return null;
  }

  /** Optional is a value type; {@link Optional#empty()} is always assignable-compatible. */
  private static Object tryEmptyOptional(final Class<?> returnType) {
    if (Optional.class.isAssignableFrom(returnType)) {
      return Optional.empty();
    }
    return null;
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

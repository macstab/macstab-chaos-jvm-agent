package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import javax.management.AttributeList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ReturnValueCorruptor")
class ReturnValueCorruptorTest {

  @Nested
  @DisplayName("NULL strategy")
  class NullStrategy {

    @Test
    @DisplayName("returns null for reference types")
    void returnsNullForReferenceTypes() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.NULL, String.class, "hello")).isNull();
    }

    @Test
    @DisplayName("falls back to ZERO for primitive int")
    void fallsBackToZeroForPrimitiveInt() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.NULL, int.class, 42)).isEqualTo(0);
    }

    @Test
    @DisplayName("falls back to false for primitive boolean")
    void fallsBackToFalseForPrimitiveBoolean() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.NULL, boolean.class, true))
          .isEqualTo(Boolean.FALSE);
    }
  }

  @Nested
  @DisplayName("ZERO strategy")
  class ZeroStrategy {

    @Test
    @DisplayName("returns 0 for int")
    void returnsZeroForInt() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.ZERO, int.class, 99)).isEqualTo(0);
    }

    @Test
    @DisplayName("returns 0L for long")
    void returnsZeroForLong() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.ZERO, long.class, 123L)).isEqualTo(0L);
    }

    @Test
    @DisplayName("returns false for boolean")
    void returnsFalseForBoolean() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.ZERO, boolean.class, true))
          .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("returns 0.0 for double")
    void returnsZeroForDouble() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.ZERO, double.class, 3.14)).isEqualTo(0.0d);
    }
  }

  @Nested
  @DisplayName("EMPTY strategy")
  class EmptyStrategy {

    @Test
    @DisplayName("returns empty string for String")
    void returnsEmptyStringForString() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.EMPTY, String.class, "hello"))
          .isEqualTo("");
    }

    @Test
    @DisplayName("returns empty list for List")
    void returnsEmptyListForList() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.EMPTY, List.class, List.of(1, 2)))
          .isEqualTo(Collections.emptyList());
    }

    @Test
    @DisplayName("returns empty Optional for Optional")
    void returnsEmptyOptionalForOptional() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.EMPTY, Optional.class, Optional.of("x")))
          .isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("falls back to ZERO for primitive int")
    void fallsBackToZeroForPrimitive() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.EMPTY, int.class, 5)).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("BOUNDARY_MAX strategy")
  class BoundaryMaxStrategy {

    @Test
    @DisplayName("returns Integer.MAX_VALUE for int")
    void returnsIntMaxForInt() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.BOUNDARY_MAX, int.class, 5))
          .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("returns Long.MAX_VALUE for long")
    void returnsLongMaxForLong() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.BOUNDARY_MAX, long.class, 5L))
          .isEqualTo(Long.MAX_VALUE);
    }
  }

  @Nested
  @DisplayName("BOUNDARY_MIN strategy")
  class BoundaryMinStrategy {

    @Test
    @DisplayName("returns Integer.MIN_VALUE for int")
    void returnsIntMinForInt() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.BOUNDARY_MIN, int.class, 5))
          .isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("returns Long.MIN_VALUE for long")
    void returnsLongMinForLong() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.BOUNDARY_MIN, long.class, 5L))
          .isEqualTo(Long.MIN_VALUE);
    }
  }

  @Nested
  @DisplayName("EMPTY always returns a value assignable to the declared type")
  class EmptyAssignability {

    @ParameterizedTest
    @ValueSource(
        classes = {
          List.class,
          Collection.class,
          Set.class,
          Map.class,
          Queue.class,
          Deque.class,
          ArrayList.class,
          PriorityQueue.class,
          BlockingQueue.class,
          BlockingDeque.class,
          ConcurrentNavigableMap.class,
          ConcurrentMap.class,
          NavigableSet.class,
          SortedMap.class,
          Iterable.class
        })
    void emptyContainerFitsDeclaredType(final Class<?> declared) {
      final Object empty = corrupt(ChaosEffect.ReturnValueStrategy.EMPTY, declared, new Object());

      assertThat(empty).isNotNull();
      assertThat(declared.isInstance(empty))
          .as("%s is assignable to %s", empty.getClass().getName(), declared.getName())
          .isTrue();
      assertThat(
              empty instanceof Collection<?> collection
                  ? collection.isEmpty()
                  : ((Map<?, ?>) empty).isEmpty())
          .isTrue();
    }

    @Test
    @DisplayName("a concrete subtype with no fitting empty value falls back to null")
    void concreteSubtypeFallsBack() {
      assertThat(
              corrupt(
                  ChaosEffect.ReturnValueStrategy.EMPTY, AttributeList.class, new AttributeList()))
          .as("a plain ArrayList would fail the checkcast to AttributeList")
          .isNull();
    }
  }

  @Nested
  @DisplayName("fallbacks")
  class Fallbacks {

    @Test
    @DisplayName("an unknown return type passes the value through unchanged")
    void unknownReturnTypePassesThrough() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.NULL, null, "hello")).isEqualTo("hello");
    }

    @Test
    @DisplayName("a boundary strategy without a boundary for the type falls back to ZERO")
    void boundaryFallsBackToZero() {
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.BOUNDARY_MAX, boolean.class, true))
          .isEqualTo(Boolean.FALSE);
      assertThat(corrupt(ChaosEffect.ReturnValueStrategy.BOUNDARY_MIN, boolean.class, true))
          .isEqualTo(Boolean.FALSE);
    }
  }

  private static Object corrupt(
      final ChaosEffect.ReturnValueStrategy strategy,
      final Class<?> returnType,
      final Object actual) {
    return ReturnValueCorruptor.corrupt(strategy, returnType, actual, "test-scenario");
  }
}

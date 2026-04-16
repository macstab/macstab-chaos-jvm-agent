package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ChaosEffect;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

  private static Object corrupt(
      final ChaosEffect.ReturnValueStrategy strategy,
      final Class<?> returnType,
      final Object actual) {
    return ReturnValueCorruptor.corrupt(strategy, returnType, actual, "test-scenario");
  }
}

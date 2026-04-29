package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ReturnValueCorruptionEffect runtime path via {@link
 * ChaosRuntime#afterMethodExit(String, String, Class, Object)}.
 */
@DisplayName("ReturnValueCorruptionEffect runtime")
class ReturnValueCorruptionRuntimeTest {

  @Test
  @DisplayName("NULL strategy replaces String return value with null")
  void nullStrategyReplacesStringWithNull() throws Throwable {
    final ChaosRuntime runtime = runtimeWithStrategy(ChaosEffect.ReturnValueStrategy.NULL);
    final Object result =
        runtime.afterMethodExit("com.example.Service", "getName", String.class, "original");
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("ZERO strategy replaces int return value with 0")
  void zeroStrategyReplacesIntWithZero() throws Throwable {
    final ChaosRuntime runtime = runtimeWithStrategy(ChaosEffect.ReturnValueStrategy.ZERO);
    final Object result = runtime.afterMethodExit("com.example.Service", "getName", int.class, 99);
    assertThat(result).isEqualTo(0);
  }

  @Test
  @DisplayName("BOUNDARY_MAX strategy replaces long return value with Long.MAX_VALUE")
  void boundaryMaxStrategyReplacesLongWithMaxValue() throws Throwable {
    final ChaosRuntime runtime = runtimeWithStrategy(ChaosEffect.ReturnValueStrategy.BOUNDARY_MAX);
    final Object result = runtime.afterMethodExit("com.example.Service", "getName", long.class, 1L);
    assertThat(result).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName("non-matching class name returns actual value unchanged")
  void nonMatchingClassReturnsActualValue() throws Throwable {
    final ChaosRuntime runtime = runtimeWithStrategy(ChaosEffect.ReturnValueStrategy.NULL);
    final Object result =
        runtime.afterMethodExit("com.example.OtherClass", "getName", String.class, "original");
    assertThat(result).isEqualTo("original");
  }

  @Test
  @DisplayName("no matching scenario returns actual value unchanged")
  void noMatchingScenarioReturnsActualValue() throws Throwable {
    final ChaosRuntime runtime = new ChaosRuntime();
    final Object result =
        runtime.afterMethodExit("com.example.Service", "getName", String.class, "unchanged");
    assertThat(result).isEqualTo("unchanged");
  }

  private static ChaosRuntime runtimeWithStrategy(final ChaosEffect.ReturnValueStrategy strategy) {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("corrupt-return")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.METHOD_EXIT),
                    NamePattern.exact("com.example.Service"),
                    NamePattern.exact("getName")))
            .effect(new ChaosEffect.ReturnValueCorruptionEffect(strategy))
            .activationPolicy(ActivationPolicy.always())
            .build());
    return runtime;
  }
}

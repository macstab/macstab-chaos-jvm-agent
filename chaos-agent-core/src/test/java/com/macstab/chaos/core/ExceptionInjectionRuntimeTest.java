package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the ExceptionInjectionEffect runtime path via {@link
 * ChaosRuntime#beforeMethodEnter(String, String)}.
 */
@DisplayName("ExceptionInjectionEffect runtime")
class ExceptionInjectionRuntimeTest {

  @Test
  @DisplayName("throws the declared exception class at matching method entry")
  void throwsDeclaredExceptionAtMatchingMethodEntry() {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("inject-ioex")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.METHOD_ENTER),
                    NamePattern.exact("com.example.Dao"),
                    NamePattern.exact("findById")))
            .effect(
                new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "injected", true))
            .activationPolicy(ActivationPolicy.always())
            .build());

    assertThatThrownBy(() -> runtime.beforeMethodEnter("com.example.Dao", "findById"))
        .isInstanceOf(IOException.class)
        .hasMessage("injected");
  }

  @Test
  @DisplayName("non-matching class name does not throw")
  void nonMatchingClassDoesNotThrow() {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("inject-miss")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.METHOD_ENTER),
                    NamePattern.exact("com.example.Dao"),
                    NamePattern.any()))
            .effect(new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "msg", true))
            .activationPolicy(ActivationPolicy.always())
            .build());

    assertThatCode(() -> runtime.beforeMethodEnter("com.example.OtherClass", "findById"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("withStackTrace=false produces exception with empty stack trace")
  void withStackTraceFalseProducesEmptyStackTrace() throws Throwable {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("inject-no-trace")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.METHOD_ENTER),
                    NamePattern.exact("com.example.Service"),
                    NamePattern.any()))
            .effect(
                new ChaosEffect.ExceptionInjectionEffect(
                    "java.lang.RuntimeException", "no-trace", false))
            .activationPolicy(ActivationPolicy.always())
            .build());

    try {
      runtime.beforeMethodEnter("com.example.Service", "doWork");
    } catch (final RuntimeException e) {
      assertThat(e.getStackTrace()).isEmpty();
    }
  }

  @Test
  @DisplayName("exception class outside the allowed packages is rejected at construction")
  void exceptionClassOutsideAllowedPackagesIsRejected() {
    // The allowlist (java., javax., jakarta., com.macstab.chaos.) is enforced at record
    // construction time. A class loaded later via Class.forName would be the perfect
    // primitive for a reflective-load gadget, so we refuse to store the name at all —
    // not at injection time, when the scenario has already been wired into the runtime.
    assertThatThrownBy(
            () ->
                new ChaosEffect.ExceptionInjectionEffect(
                    "com.example.NonExistentException", "msg", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowed package");
  }
}

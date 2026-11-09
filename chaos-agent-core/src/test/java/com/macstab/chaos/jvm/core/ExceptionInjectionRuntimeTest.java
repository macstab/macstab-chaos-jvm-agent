package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.util.Set;
import javax.chaostest.CountingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    // The allowlist (java., javax.) is enforced at record construction: the name of a class
    // outside it is never stored, so it can never reach Class.forName.
    assertThatThrownBy(
            () ->
                new ChaosEffect.ExceptionInjectionEffect(
                    "com.example.NonExistentException", "msg", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowed package");
  }

  @Nested
  @DisplayName("the exception class is resolved at activation")
  class ResolvedAtActivation {

    private final ChaosRuntime runtime = new ChaosRuntime();

    @AfterEach
    void close() {
      runtime.close();
    }

    @Test
    @DisplayName("jakarta. and the agent's packages are outside the allowlist")
    void jakartaIsRefusedByTheRecord() {
      assertThatThrownBy(
              () ->
                  new ChaosEffect.ExceptionInjectionEffect(
                      "jakarta.ws.rs.WebApplicationException", "msg", true))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("allowed package");
      assertThatThrownBy(
              () ->
                  new ChaosEffect.ExceptionInjectionEffect(
                      "com.macstab.chaos.jvm.api.ChaosValidationException", "msg", true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "java.lang.String",
          "java.lang.VirtualMachineError",
          "java.util.concurrent.CompletionException",
          "java.lang.NoSuchThingException"
        })
    @DisplayName("a class that cannot be thrown as configured is refused")
    void unusableClassIsRefused(final String className) {
      assertThatThrownBy(() -> runtime.activate(injecting("unusable", className, 0)))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining(className);
    }

    @Test
    @DisplayName("only the winning contribution's exception is constructed")
    void loserIsNotConstructed() {
      runtime.activate(injecting("winner", "javax.chaostest.CountingException", 10));
      runtime.activate(injecting("loser", "javax.chaostest.CountingException", 1));
      final int before = CountingException.CONSTRUCTED.get();

      assertThatThrownBy(() -> runtime.beforeMethodEnter("com.example.Dao", "findById"))
          .isInstanceOf(CountingException.class);

      assertThat(CountingException.CONSTRUCTED.get() - before).isEqualTo(1);
    }

    private ChaosScenario injecting(final String id, final String className, final int precedence) {
      return ChaosScenario.builder(id)
          .scope(ChaosScenario.ScenarioScope.JVM)
          .precedence(precedence)
          .selector(
              ChaosSelector.method(
                  Set.of(OperationType.METHOD_ENTER),
                  NamePattern.exact("com.example.Dao"),
                  NamePattern.exact("findById")))
          .effect(new ChaosEffect.ExceptionInjectionEffect(className, "injected", true))
          .activationPolicy(ActivationPolicy.always())
          .build();
    }
  }
}

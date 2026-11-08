package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;

/**
 * The extension's teardown when its setup never ran (an earlier extension failed first): there is
 * nothing to close, and it resolves only its own parameter types.
 */
@DisplayName("ChaosAgentExtension without setup")
class ChaosAgentExtensionGuardsTest {

  private final ChaosAgentExtension extension = new ChaosAgentExtension();

  @Test
  @DisplayName("after a test whose setup never ran, nothing is closed")
  void teardownWithoutSetup() {
    final Map<Object, Object> values = new HashMap<>();

    assertThatCode(() -> extension.afterEach(context(values))).doesNotThrowAnyException();
    assertThat(values).isEmpty();
  }

  @Test
  @DisplayName("before and around a test whose setup never ran, nothing is activated or bound")
  void testExecutionWithoutSetup() throws Throwable {
    final Map<Object, Object> values = new HashMap<>();
    final ExtensionContext context = context(values);

    extension.beforeTestExecution(context);
    final boolean[] proceeded = new boolean[1];
    extension.interceptTestMethod(
        () -> {
          proceeded[0] = true;
          return null;
        },
        null,
        context);
    extension.afterTestExecution(context);

    assertThat(values).isEmpty();
    assertThat(proceeded[0]).isTrue();
  }

  @Test
  @DisplayName("half a setup (no session) activates nothing and binds nothing")
  void testExecutionWithoutSession() throws Throwable {
    final Map<Object, Object> values = new HashMap<>();
    values.put(ChaosControlPlane.class, ChaosTestKit.install());
    final ExtensionContext context = context(values);

    extension.beforeTestExecution(context);
    assertThat(values).containsOnlyKeys(ChaosControlPlane.class);

    values.put(WithChaosSupport.class, java.util.List.of(new Object()));
    assertThat(extension.interceptTestFactoryMethod(() -> "proceeded", null, context))
        .isEqualTo("proceeded");
  }

  @Test
  @DisplayName("a failing method declaration stops the class scenarios already activated")
  void failedActivationStopsTheClassScenarios() throws Exception {
    final Map<Object, Object> values = new HashMap<>();
    final ExtensionContext context =
        context(values, Fixture.class, Fixture.class.getDeclaredMethod("invalid"));
    extension.beforeEach(context);
    try {
      assertThatThrownBy(() -> extension.beforeTestExecution(context))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("needs exactly one of value (JSON) and resource");
      assertThat(ChaosTestKit.install().diagnostics().scenario("guards-class-scenario").isPresent())
          .as("the class scenario is stopped")
          .isFalse();
    } finally {
      extension.afterEach(context);
    }
  }

  @WithChaos(
      """
      {"id": "guards-class-scenario", "scope": "SESSION",
       "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
       "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
      """)
  static final class Fixture {

    @WithChaos
    void invalid() {}
  }

  @Test
  @DisplayName("resolves only sessions and control planes")
  void parameterTypes() throws Exception {
    final ExtensionContext context = context(new HashMap<>());

    assertThat(extension.supportsParameter(parameter(ChaosSession.class), context)).isTrue();
    assertThat(extension.supportsParameter(parameter(ChaosControlPlane.class), context)).isTrue();
    assertThat(extension.supportsParameter(parameter(String.class), context)).isFalse();
  }

  @Test
  @DisplayName("outside a test (a static @BeforeAll, a constructor) a parameter fails, not null")
  void noSessionNoParameter() {
    assertThatThrownBy(
            () ->
                extension.resolveParameter(parameter(ChaosSession.class), context(new HashMap<>())))
        .isInstanceOf(org.junit.jupiter.api.extension.ParameterResolutionException.class)
        .hasMessageContaining("no ChaosSession available");
  }

  @SuppressWarnings("unused")
  private static void target(
      final ChaosSession session, final ChaosControlPlane plane, final String other) {}

  private static ParameterContext parameter(final Class<?> type) throws Exception {
    final Parameter parameter =
        java.util.Arrays.stream(
                ChaosAgentExtensionGuardsTest.class
                    .getDeclaredMethod(
                        "target", ChaosSession.class, ChaosControlPlane.class, String.class)
                    .getParameters())
            .filter(candidate -> candidate.getType() == type)
            .findFirst()
            .orElseThrow();
    return (ParameterContext)
        Proxy.newProxyInstance(
            ParameterContext.class.getClassLoader(),
            new Class<?>[] {ParameterContext.class},
            (self, method, args) ->
                method.getName().equals("getParameter")
                    ? parameter
                    : method.isDefault()
                        ? java.lang.reflect.InvocationHandler.invokeDefault(self, method, args)
                        : null);
  }

  /** A context whose store holds {@code values} and that has no parent. */
  private static ExtensionContext context(final Map<Object, Object> values) {
    return context(values, null, null);
  }

  /** A context for {@code testMethod} of {@code testClass} whose store holds {@code values}. */
  private static ExtensionContext context(
      final Map<Object, Object> values,
      final @Nullable Class<?> testClass,
      final @Nullable Method testMethod) {
    final ExtensionContext.Store store =
        (ExtensionContext.Store)
            Proxy.newProxyInstance(
                ExtensionContext.Store.class.getClassLoader(),
                new Class<?>[] {ExtensionContext.Store.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "get" -> values.get(args[0]);
                      case "put" -> values.put(args[0], args[1]);
                      case "remove" -> values.remove(args[0]);
                      default -> null;
                    });
    return (ExtensionContext)
        Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] {ExtensionContext.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "getStore" -> store;
                  case "getParent" -> Optional.empty();
                  case "getDisplayName" -> "guards";
                  case "getRequiredTestClass" -> testClass;
                  case "getRequiredTestMethod" -> testMethod;
                  default -> null;
                });
  }
}

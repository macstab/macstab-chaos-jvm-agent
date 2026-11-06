package com.macstab.chaos.jvm.spring.boot3.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/**
 * The extension used without its class-level setup (registered on a method only, or before {@code
 * beforeAll} ran): it activates nothing, tears down nothing, and a requested parameter fails with a
 * message saying what is missing.
 */
@DisplayName("ChaosAgentExtension without class-level setup")
class ChaosAgentExtensionGuardsTest {

  private final ChaosAgentExtension extension = new ChaosAgentExtension();
  private final ExtensionContext context = context();

  @Test
  @DisplayName("before and after each test, nothing happens without a session")
  void noSessionNoActivation() {
    assertThatCode(
            () -> {
              extension.beforeEach(context);
              extension.afterEach(context);
              extension.afterAll(context);
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("before each test, a control plane without a session activates nothing")
  void controlPlaneWithoutSession() {
    final ChaosControlPlane plane =
        (ChaosControlPlane)
            Proxy.newProxyInstance(
                ChaosControlPlane.class.getClassLoader(),
                new Class<?>[] {ChaosControlPlane.class},
                (self, method, args) -> {
                  throw new AssertionError("used without a session: " + method.getName());
                });
    context.getStore(null).put(ChaosControlPlane.class, plane);

    extension.beforeEach(context);

    assertThat(context.getStore(null).get(com.macstab.chaos.jvm.testkit.WithChaosSupport.class))
        .isNull();
  }

  @Test
  @DisplayName("resolves only sessions and control planes, and says when none is available")
  void parameters() throws Exception {
    assertThat(extension.supportsParameter(parameter(ChaosSession.class), context)).isTrue();
    assertThat(extension.supportsParameter(parameter(ChaosControlPlane.class), context)).isTrue();
    assertThat(extension.supportsParameter(parameter(String.class), context)).isFalse();
    assertThatThrownBy(() -> extension.resolveParameter(parameter(ChaosSession.class), context))
        .isInstanceOf(ParameterResolutionException.class)
        .hasMessageContaining("no ChaosSession available");
  }

  @Test
  @DisplayName("scenarios activated through a test context's control plane bean end with the class")
  void contextBeanActivationEndsWithTheClass() {
    final ChaosControlPlane bean =
        ChaosAgentExtension.contextPlane(
            com.macstab.chaos.jvm.bootstrap.ChaosPlatform.installLocally());
    bean.activate(
        com.macstab.chaos.jvm.api.ChaosScenario.builder("context-bean-jvm")
            .selector(
                com.macstab.chaos.jvm.api.ChaosSelector.executor(
                    java.util.Set.of(com.macstab.chaos.jvm.api.OperationType.EXECUTOR_SUBMIT)))
            .effect(com.macstab.chaos.jvm.api.ChaosEffect.delay(java.time.Duration.ofMillis(1)))
            .build());

    new ChaosAgentExtension().afterAll(context());

    assertThat(
            com.macstab.chaos.jvm.bootstrap.ChaosPlatform.current()
                .diagnostics()
                .scenario("context-bean-jvm"))
        .isEmpty();
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

  /** A context with an empty store and no parent: the state before beforeAll. */
  private static ExtensionContext context() {
    final Map<Object, Object> values = new HashMap<>();
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
                  case "getRequiredTestMethod" ->
                      ChaosAgentExtensionGuardsTest.class.getDeclaredMethod(
                          "noSessionNoActivation");
                  case "getDisplayName" -> "guards";
                  default -> null;
                });
  }
}

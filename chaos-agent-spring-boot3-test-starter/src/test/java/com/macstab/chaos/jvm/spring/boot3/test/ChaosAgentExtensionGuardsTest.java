package com.macstab.chaos.jvm.spring.boot3.test;

import static org.assertj.core.api.Assertions.assertThat;
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
    final Map<Object, Object> values = new HashMap<>();
    final ExtensionContext empty = context(values);

    extension.beforeTestExecution(empty);
    extension.afterTestExecution(empty);
    extension.afterAll(empty);

    assertThat(values).isEmpty();
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

    extension.beforeTestExecution(context);

    assertThat(context.getStore(null).get(com.macstab.chaos.jvm.testkit.WithChaosSupport.class))
        .isNull();
  }

  @Test
  @DisplayName("before all, a control plane without a session is no outer class: one is opened")
  void controlPlaneWithoutSessionBeforeAll() {
    final ExtensionContext lone = context();
    lone.getStore(null).put(ChaosControlPlane.class, unusedPlane());

    extension.beforeAll(lone);
    try {
      assertThat(lone.getStore(null).get(ChaosSession.class)).isNotNull();
      assertThat(lone.getStore(null).get(ChaosControlPlane.class))
          .isInstanceOf(com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane.class);
    } finally {
      extension.afterAll(lone);
    }
  }

  private static ChaosControlPlane unusedPlane() {
    return (ChaosControlPlane)
        Proxy.newProxyInstance(
            ChaosControlPlane.class.getClassLoader(),
            new Class<?>[] {ChaosControlPlane.class},
            (self, method, args) -> {
              throw new AssertionError("used without a session: " + method.getName());
            });
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
  @DisplayName(
      "scenarios activated through a context bean end when the last running chaos class ends")
  void contextBeanActivationEndsWithTheLastClass() {
    final ExtensionContext first = context();
    final ExtensionContext second = context();
    extension.beforeAll(first);
    extension.beforeAll(second);
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

    extension.afterAll(first);
    assertThat(beanScenario()).as("a class still running uses it").isPresent();

    extension.afterAll(second);
    assertThat(beanScenario()).isEmpty();
  }

  private static Optional<?> beanScenario() {
    return com.macstab.chaos.jvm.bootstrap.ChaosPlatform.current()
        .diagnostics()
        .scenario("context-bean-jvm");
  }

  @SuppressWarnings("unused")
  private static void target(
      final ChaosSession session, final ChaosControlPlane plane, final String other) {}

  private static ParameterContext parameter(final Class<?> type) throws Exception {
    final Parameter[] parameters =
        ChaosAgentExtensionGuardsTest.class
            .getDeclaredMethod("target", ChaosSession.class, ChaosControlPlane.class, String.class)
            .getParameters();
    final int index =
        java.util.stream.IntStream.range(0, parameters.length)
            .filter(candidate -> parameters[candidate].getType() == type)
            .findFirst()
            .orElseThrow();
    return (ParameterContext)
        Proxy.newProxyInstance(
            ParameterContext.class.getClassLoader(),
            new Class<?>[] {ParameterContext.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "getParameter" -> parameters[index];
                  case "getIndex" -> index;
                  default ->
                      method.isDefault()
                          ? java.lang.reflect.InvocationHandler.invokeDefault(self, method, args)
                          : null;
                });
  }

  /** A test class whose constructor takes the chaos types, with no autowiring annotation. */
  @SuppressWarnings("unused")
  private static final class ConstructorTarget {
    ConstructorTarget(final ChaosSession session, final ChaosControlPlane plane) {}
  }

  @Test
  @DisplayName(
      "leaves constructor parameters to Spring when the JUnit configuration autowires them")
  void constructorAutowiredByConfiguration() throws Exception {
    final Parameter plane =
        ConstructorTarget.class.getDeclaredConstructor(ChaosSession.class, ChaosControlPlane.class)
            .getParameters()[1];
    final ParameterContext parameter =
        (ParameterContext)
            Proxy.newProxyInstance(
                ParameterContext.class.getClassLoader(),
                new Class<?>[] {ParameterContext.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "getParameter" -> plane;
                      case "getIndex" -> 1;
                      default ->
                          method.isDefault()
                              ? java.lang.reflect.InvocationHandler.invokeDefault(
                                  self, method, args)
                              : null;
                    });

    assertThat(extension.supportsParameter(parameter, configured(Optional.of("all")))).isFalse();
    assertThat(extension.supportsParameter(parameter, configured(Optional.empty()))).isTrue();
  }

  /** A context whose JUnit configuration parameters all have {@code value}. */
  private static ExtensionContext configured(final Optional<String> value) {
    return (ExtensionContext)
        Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] {ExtensionContext.class},
            (self, method, args) ->
                method.getName().equals("getConfigurationParameter") && args.length == 1
                    ? value
                    : null);
  }

  /** A context with an empty store and no parent: the state before beforeAll. */
  private static ExtensionContext context() {
    return context(new HashMap<>());
  }

  /** A context with no parent whose store is backed by {@code values}. */
  private static ExtensionContext context(final Map<Object, Object> values) {
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
                  case "getRequiredTestClass" -> ChaosAgentExtensionGuardsTest.class;
                  case "getRequiredTestMethod" ->
                      ChaosAgentExtensionGuardsTest.class.getDeclaredMethod(
                          "noSessionNoActivation");
                  case "getDisplayName" -> "guards";
                  default -> null;
                });
  }
}

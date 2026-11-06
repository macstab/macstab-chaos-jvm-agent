package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.testkit.WithChaosSupport;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/**
 * The extension used without its class-level setup (registered on a method only, or before {@code
 * beforeAll} ran), and with its state held only by a parent context: it activates nothing it has no
 * session for, tears down nothing, and a requested parameter fails with a message saying what is
 * missing.
 */
@DisplayName("ChaosMicronautExtension without class-level setup")
class ChaosMicronautExtensionGuardsTest {

  private final ChaosMicronautExtension extension = new ChaosMicronautExtension();

  @Test
  @DisplayName("before and after each test and after all, nothing happens without a session")
  void noSessionNoActivation() {
    final Map<Object, Object> values = new HashMap<>();
    values.put(ChaosControlPlane.class, unused(ChaosControlPlane.class));
    final ExtensionContext context = context(values, null);

    assertThatCode(
            () -> {
              extension.beforeEach(context);
              extension.afterEach(context);
            })
        .doesNotThrowAnyException();
    assertThat(values).containsOnlyKeys(ChaosControlPlane.class);

    final Map<Object, Object> empty = new HashMap<>();
    assertThatCode(
            () -> {
              extension.beforeEach(context(empty, null));
              extension.afterAll(context(empty, null));
            })
        .doesNotThrowAnyException();
    assertThat(empty).isEmpty();
  }

  @Test
  @DisplayName("scenarios activated through a test context's control plane bean end with the class")
  void contextBeanActivationEndsWithTheClass() {
    final ChaosControlPlane bean =
        ChaosMicronautExtension.contextPlane(
            com.macstab.chaos.jvm.bootstrap.ChaosPlatform.installLocally());
    bean.activate(
        com.macstab.chaos.jvm.api.ChaosScenario.builder("mn-context-bean-jvm")
            .selector(
                com.macstab.chaos.jvm.api.ChaosSelector.executor(
                    java.util.Set.of(com.macstab.chaos.jvm.api.OperationType.EXECUTOR_SUBMIT)))
            .effect(com.macstab.chaos.jvm.api.ChaosEffect.delay(java.time.Duration.ofMillis(1)))
            .build());

    extension.afterAll(context(new HashMap<>(), null));

    assertThat(
            com.macstab.chaos.jvm.bootstrap.ChaosPlatform.current()
                .diagnostics()
                .scenario("mn-context-bean-jvm"))
        .isEmpty();
  }

  @Test
  @DisplayName("a session and control plane held by a parent context are found")
  void stateFromParentContext() {
    final Map<Object, Object> parentValues = new HashMap<>();
    parentValues.put(ChaosControlPlane.class, unused(ChaosControlPlane.class));
    parentValues.put(ChaosSession.class, unused(ChaosSession.class));
    final Map<Object, Object> methodValues = new HashMap<>();

    extension.beforeEach(context(methodValues, context(parentValues, null)));

    assertThat(methodValues.get(WithChaosSupport.class)).isEqualTo(List.of());
  }

  @Test
  @DisplayName("resolves only sessions and control planes, and says when none is available")
  void parameters() throws Exception {
    final ExtensionContext context = context(new HashMap<>(), context(new HashMap<>(), null));

    assertThat(extension.supportsParameter(parameter(ChaosSession.class), context)).isTrue();
    assertThat(extension.supportsParameter(parameter(ChaosControlPlane.class), context)).isTrue();
    assertThat(extension.supportsParameter(parameter(String.class), context)).isFalse();
    assertThatThrownBy(() -> extension.resolveParameter(parameter(ChaosSession.class), context))
        .isInstanceOf(ParameterResolutionException.class)
        .hasMessageStartingWith("ChaosMicronautExtension: no ChaosSession available");
  }

  /** A stand-in the extension must only store and hand on, never call. */
  private static <T> T unused(final Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (self, method, args) -> {
              throw new AssertionError("called " + method.getName());
            }));
  }

  @SuppressWarnings("unused")
  private static void target(
      final ChaosSession session, final ChaosControlPlane plane, final String other) {}

  private static ParameterContext parameter(final Class<?> type) throws Exception {
    final Parameter parameter =
        java.util.Arrays.stream(
                ChaosMicronautExtensionGuardsTest.class
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

  /**
   * A context whose store holds {@code values} without looking into the parent's store, with no
   * test class and the given parent.
   */
  private static ExtensionContext context(
      final Map<Object, Object> values, final ExtensionContext parent) {
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
                  case "getParent" -> Optional.ofNullable(parent);
                  case "getTestClass" -> Optional.empty();
                  case "getRequiredTestMethod" ->
                      ChaosMicronautExtensionGuardsTest.class.getDeclaredMethod(
                          "noSessionNoActivation");
                  case "getDisplayName" -> "guards";
                  default -> null;
                });
  }
}

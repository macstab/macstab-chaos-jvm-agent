package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.WithChaos;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Method-level {@link WithChaos} is active around the test method only: not during the test's
 * {@code @BeforeEach} and {@code @AfterEach} methods. The callbacks are driven in JUnit's order
 * through the callback interfaces the extension implements.
 */
@DisplayName("ChaosMicronautExtension method-level lifecycle")
class MethodLevelChaosLifecycleTest {

  private static final String SCENARIO_ID = "micronaut-method-level-lifecycle";

  @Test
  @DisplayName("method-level chaos starts before the test method and stops right after it")
  void methodLevelChaosBracketsTheTestMethod() throws Exception {
    final Object extension = new ChaosMicronautExtension();
    final ExtensionContext classContext = context(null, null);
    final ExtensionContext methodContext =
        context(classContext, Fixture.class.getDeclaredMethod("annotated"));

    ((BeforeAllCallback) extension).beforeAll(classContext);
    try {
      if (extension instanceof BeforeEachCallback callback) {
        callback.beforeEach(methodContext);
      }
      assertThat(scenario()).as("registered while @BeforeEach methods run").isEmpty();

      if (extension instanceof BeforeTestExecutionCallback callback) {
        callback.beforeTestExecution(methodContext);
      }
      assertThat(scenario().map(ChaosDiagnostics.ScenarioReport::state))
          .as("state while the test method runs")
          .contains(ChaosDiagnostics.ScenarioState.ACTIVE);

      if (extension instanceof AfterTestExecutionCallback callback) {
        callback.afterTestExecution(methodContext);
      }
      assertThat(scenario()).as("registered while @AfterEach methods run").isEmpty();
    } finally {
      // afterAll stops what the class's tracking control plane activated, whatever failed.
      if (extension instanceof AfterEachCallback callback) {
        callback.afterEach(methodContext);
      }
      ((AfterAllCallback) extension).afterAll(classContext);
    }
  }

  private static Optional<ChaosDiagnostics.ScenarioReport> scenario() {
    return ChaosPlatform.installLocally().diagnostics().scenario(SCENARIO_ID);
  }

  /** A context whose store is its own map and whose parent is {@code parent}. */
  private static ExtensionContext context(
      final @Nullable ExtensionContext parent, final @Nullable Method testMethod) {
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
                  case "getParent" -> Optional.ofNullable(parent);
                  case "getTestClass" -> Optional.of(Fixture.class);
                  case "getRequiredTestClass" -> Fixture.class;
                  case "getTestMethod" -> Optional.ofNullable(testMethod);
                  case "getRequiredTestMethod" -> testMethod;
                  case "getDisplayName" -> "method-level lifecycle";
                  default -> null;
                });
  }

  static final class Fixture {

    @WithChaos(
        """
        {"id": "micronaut-method-level-lifecycle", "scope": "JVM",
         "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
         "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
        """)
    void annotated() {}
  }
}

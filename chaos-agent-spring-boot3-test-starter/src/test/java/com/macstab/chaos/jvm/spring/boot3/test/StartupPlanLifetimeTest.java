package com.macstab.chaos.jvm.spring.boot3.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.spring.boot.common.ChaosAutoConfiguration;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The startup plan of a cached test context belongs to the context, not to the test classes that
 * use it: a class ending does not stop it while the context lives.
 */
@DisplayName("Startup plan of a shared test context")
class StartupPlanLifetimeTest {

  private static final String SCENARIO_ID = "boot3-cached-context-startup";

  @TempDir Path directory;

  @Test
  @DisplayName("outlives the test classes that share the context and stops with the context")
  void outlivesTheTestClasses() throws IOException {
    final Path plan =
        Files.writeString(
            directory.resolve("plan.json"),
            """
            {"scenarios": [{"id": "boot3-cached-context-startup", "scope": "JVM",
              "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
              "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]}
            """);
    final ChaosAgentExtension extension = new ChaosAgentExtension();

    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ChaosAutoConfiguration.class, ChaosTestAutoConfiguration.class))
        .withPropertyValues(
            "macstab.chaos.enabled=true",
            ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY + "=true",
            "macstab.chaos.config-file=" + plan)
        .run(
            context -> {
              final ConfigurableApplicationContext source = context.getSourceApplicationContext();
              source.publishEvent(
                  new ApplicationReadyEvent(
                      new SpringApplication(), new String[0], source, Duration.ZERO));
              assertThat(state()).contains(ChaosDiagnostics.ScenarioState.ACTIVE);

              // Test class A, then test class B, both on the cached context.
              final ExtensionContext classA = classContext();
              try {
                extension.beforeAll(classA);
              } finally {
                extension.afterAll(classA);
              }
              assertThat(state())
                  .as("after the first class sharing the context ended")
                  .contains(ChaosDiagnostics.ScenarioState.ACTIVE);

              final ExtensionContext classB = classContext();
              try {
                extension.beforeAll(classB);
              } finally {
                extension.afterAll(classB);
              }
              assertThat(state()).contains(ChaosDiagnostics.ScenarioState.ACTIVE);
            });

    assertThat(state()).as("after the context closed").isEmpty();
  }

  private static Optional<ChaosDiagnostics.ScenarioState> state() {
    return ChaosPlatform.installLocally()
        .diagnostics()
        .scenario(SCENARIO_ID)
        .map(ChaosDiagnostics.ScenarioReport::state);
  }

  /** The context of a top-level test class: its own store, no parent. */
  private static ExtensionContext classContext() {
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
                  case "getRequiredTestClass" -> StartupPlanLifetimeTest.class;
                  case "getDisplayName" -> "test class";
                  default -> null;
                });
  }
}

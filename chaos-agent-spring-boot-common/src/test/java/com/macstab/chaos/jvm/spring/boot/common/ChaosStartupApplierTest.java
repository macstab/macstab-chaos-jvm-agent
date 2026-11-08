package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the auto-configuration does once the application is ready: activate the startup plan from
 * {@code macstab.chaos.config-file} and track its handle, survive a missing, malformed or rejected
 * plan with a log line instead of failing the application, keep the parser's excerpt of a malformed
 * plan out of the log, and print the diagnostics when asked.
 */
@DisplayName("Startup plan applier")
class ChaosStartupApplierTest {

  private static final String PLAN =
      """
      {"scenarios": [{"id": "startup-delay", "scope": "JVM",
        "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
        "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]}
      """;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class))
          .withUserConfiguration(RuntimeConfiguration.class)
          .withPropertyValues("macstab.chaos.enabled=true");

  private final Logger logger = Logger.getLogger(ChaosAutoConfiguration.class.getName());
  private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(final LogRecord record) {
          logged.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @TempDir Path directory;

  @BeforeEach
  void captureLog() {
    logger.addHandler(capture);
  }

  @AfterEach
  void releaseLog() {
    logger.removeHandler(capture);
  }

  @Test
  @DisplayName("activates the configured plan once ready and tracks its handle")
  void activatesStartupPlan() throws IOException {
    final Path plan = Files.writeString(directory.resolve("plan.json"), PLAN);
    runner
        .withPropertyValues("macstab.chaos.config-file=" + plan)
        .run(
            context -> {
              ready(context);
              final ChaosRuntime runtime = context.getBean(ChaosRuntime.class);
              assertThat(runtime.diagnostics().scenario("startup-delay")).isPresent();
              context.getBean(ChaosHandleRegistry.class).destroy();
              assertThat(runtime.diagnostics().scenario("startup-delay"))
                  .as("the plan's handle is tracked and stops with the context")
                  .isEmpty();
            });
  }

  @Test
  @DisplayName("a plan file named by an untrusted source is ignored with a warning")
  void untrustedPlanFileIsIgnored() throws IOException {
    final Path plan = Files.writeString(directory.resolve("plan.json"), PLAN);
    runner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                            "configserver:app",
                            java.util.Map.of("macstab.chaos.config-file", plan.toString()))))
        .run(
            context -> {
              ready(context);
              assertThat(
                      context.getBean(ChaosRuntime.class).diagnostics().scenario("startup-delay"))
                  .isEmpty();
              assertThat(logged)
                  .anyMatch(
                      record ->
                          record.getMessage().contains("untrusted property source")
                              && record.getMessage().contains("macstab.chaos.config-file"));
            });
  }

  @Test
  @DisplayName("a missing or malformed plan is logged and leaves the application running")
  void badPlanIsLogged() throws IOException {
    final Path malformed = Files.writeString(directory.resolve("bad.json"), "{\"scenarios\": [");
    for (final String path :
        List.of(directory.resolve("absent.json").toString(), malformed.toString())) {
      logged.clear();
      runner
          .withPropertyValues("macstab.chaos.config-file=" + path)
          .run(
              context -> {
                assertThatCode(() -> ready(context)).doesNotThrowAnyException();
                assertThat(context.getBean(ChaosRuntime.class).diagnostics().snapshot().scenarios())
                    .isEmpty();
              });
      assertThat(logged)
          .anySatisfy(
              record ->
                  assertThat(record.getMessage()).contains("failed to load startup config file"));
    }
  }

  @Test
  @DisplayName("a malformed plan is logged without the parser's excerpt of the file")
  void badPlanLogCarriesNoInput() throws IOException {
    final Path malformed =
        Files.writeString(directory.resolve("secret.json"), "{\"scenarios\":SECRET123}");

    runner
        .withPropertyValues("macstab.chaos.config-file=" + malformed)
        .run(ChaosStartupApplierTest::ready);

    assertThat(logged)
        .filteredOn(record -> record.getMessage().contains("failed to load startup config file"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.getThrown()).as("no cause chain in the log").isNull();
              assertThat(record.getMessage())
                  .contains("malformed JSON")
                  .doesNotContain("SECRET123");
            });
  }

  @Test
  @DisplayName("a blank config-file is no plan: nothing is loaded and nothing logged")
  void blankConfigFileIsIgnored() {
    runner
        .withPropertyValues("macstab.chaos.config-file= ")
        .run(
            context -> {
              ready(context);
              assertThat(context.getBean(ChaosRuntime.class).diagnostics().snapshot().scenarios())
                  .isEmpty();
            });
    assertThat(logged).isEmpty();
  }

  @Test
  @DisplayName("a plan the runtime rejects is logged and leaves the application running")
  void rejectedPlanIsLogged() throws IOException {
    final Path plan =
        Files.writeString(
            directory.resolve("rejected.json"),
            PLAN.replace(
                "{\"type\": \"delay\", \"minDelay\": \"PT0.001S\", \"maxDelay\": \"PT0.001S\"}",
                "{\"type\": \"tornWrite\"}"));
    runner
        .withPropertyValues("macstab.chaos.config-file=" + plan)
        .run(context -> assertThatCode(() -> ready(context)).doesNotThrowAnyException());
    assertThat(logged)
        .anySatisfy(
            record -> assertThat(record.getMessage()).contains("failed to activate startup plan"));
  }

  @Test
  @DisplayName("debug-dump-on-start prints the diagnostics once ready")
  void debugDump() {
    runner
        .withPropertyValues("macstab.chaos.debug-dump-on-start=true")
        .run(ChaosStartupApplierTest::ready);
    assertThat(logged)
        .anySatisfy(record -> assertThat(record.getMessage()).contains("startup diagnostics"));
  }

  @SuppressWarnings("unchecked")
  private static void ready(final AssertableApplicationContext context) {
    final ConfigurableApplicationContext source = context.getSourceApplicationContext();
    final ApplicationListener<ApplicationReadyEvent> applier =
        context.getBean("chaosStartupApplier", ApplicationListener.class);
    applier.onApplicationEvent(
        new ApplicationReadyEvent(new SpringApplication(), new String[0], source, Duration.ZERO));
  }

  @Configuration(proxyBeanMethods = false)
  static class RuntimeConfiguration {
    // A real runtime as the control plane: the auto-configuration backs off its own, which
    // would install the agent into the test JVM.
    @Bean(destroyMethod = "close")
    ChaosRuntime chaosControlPlane() {
      return new ChaosRuntime();
    }
  }
}

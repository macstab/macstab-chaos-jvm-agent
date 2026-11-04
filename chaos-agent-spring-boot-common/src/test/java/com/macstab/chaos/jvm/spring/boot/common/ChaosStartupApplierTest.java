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
 * plan with a log line instead of failing the application, print the diagnostics when asked, and
 * keep an operator-supplied path from forging log lines.
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
              assertThat(context.getBean(ChaosHandleRegistry.class).stop("plan:" + "default"))
                  .as("the plan's handle is tracked under its id")
                  .isTrue();
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

  @Test
  @DisplayName("an operator-supplied path cannot forge log lines")
  void pathsAreSanitisedForTheLog() {
    assertThat(ChaosAutoConfiguration.sanitizeForLog("/etc/plan.json\n2026 FORGED\r\u0000"))
        .isEqualTo("/etc/plan.json_2026 FORGED__");
    assertThat(ChaosAutoConfiguration.sanitizeForLog("a\tb")).isEqualTo("a\tb");
    assertThat(ChaosAutoConfiguration.sanitizeForLog("x".repeat(5_000))).hasSize(1_024);
    assertThat(ChaosAutoConfiguration.sanitizeForLog(null)).isEmpty();
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

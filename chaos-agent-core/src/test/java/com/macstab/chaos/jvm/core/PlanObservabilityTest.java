package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A plan's {@link ChaosPlan.Observability} settings act on its scenarios. */
@DisplayName("Plan observability settings")
class PlanObservabilityTest {

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final Logger logger = Logger.getLogger("com.macstab.chaos.jvm");
  private final List<String> logged = new CopyOnWriteArrayList<>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(final LogRecord record) {
          logged.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void captureLogs() {
    logger.addHandler(capture);
  }

  @AfterEach
  void tearDown() {
    logger.removeHandler(capture);
    runtime.close();
  }

  @Test
  @DisplayName("structuredLoggingEnabled=false stops logging the plan's events while it is active")
  void mutesLogging() {
    final Level previous = logger.getLevel();
    logger.setLevel(Level.ALL);
    try {
      final ChaosActivationHandle plan =
          runtime.activate(plan(new ChaosPlan.Observability(true, false, false), "quiet"));
      submit();
      assertThat(logged).noneMatch(line -> line.contains("scenarioId=quiet"));

      plan.close();
      logged.clear();
      runtime.activate(scenario("quiet")).close();
      assertThat(logged).anyMatch(line -> line.contains("scenarioId=quiet"));
    } finally {
      logger.setLevel(previous);
    }
  }

  @Test
  @DisplayName("jmxEnabled=false hides the plan's scenarios from JMX until the plan is closed")
  void hidesFromJmx() {
    final ChaosActivationHandle plan =
        runtime.activate(plan(new ChaosPlan.Observability(false, true, false), "hidden"));
    runtime.activate(scenario("shown"));

    assertThat(runtime.jmxVisible("hidden")).isFalse();
    assertThat(runtime.jmxVisible("shown")).isTrue();
    final String dump = runtime.debugDump(runtime::jmxVisible);
    assertThat(dump).contains("shown state=").doesNotContain("hidden state=");

    plan.close();
    assertThat(runtime.jmxVisible("hidden")).isTrue();
  }

  @Test
  @DisplayName("debugDumpOnStart prints the diagnostics once the plan is active")
  void dumpsOnStart() {
    final PrintStream original = System.err;
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      runtime.activate(plan(new ChaosPlan.Observability(true, true, true), "dumped"));
    } finally {
      System.setErr(original);
    }
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("macstab-chaos diagnostics")
        .contains("dumped state=ACTIVE");
  }

  @Test
  @DisplayName("a plan that fails to activate leaves no settings behind")
  void failureClearsSettings() {
    runtime.activate(scenario("taken"));
    final ChaosPlan conflicting =
        new ChaosPlan(
            new ChaosPlan.Metadata("conflict", ""),
            new ChaosPlan.Observability(false, false, false),
            List.of(scenario("fresh"), scenario("taken")));
    try {
      runtime.activate(conflicting);
    } catch (final RuntimeException expected) {
      // the second scenario's id is in use
    }
    assertThat(runtime.jmxVisible("fresh")).isTrue();
  }

  private void submit() {
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
  }

  private static ChaosPlan plan(final ChaosPlan.Observability observability, final String id) {
    return new ChaosPlan(new ChaosPlan.Metadata(id, ""), observability, List.of(scenario(id)));
  }

  private static ChaosScenario scenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
  }
}

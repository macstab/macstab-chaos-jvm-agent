package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationConflictException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
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
      assertThat(logged).noneMatch(line -> line.contains("scenarioId=\"quiet\""));

      plan.close();
      logged.clear();
      runtime.activate(scenario("quiet")).close();
      assertThat(logged).anyMatch(line -> line.contains("scenarioId=\"quiet\""));
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

    assertThat(runtime.jmxVisible("jvm", "hidden")).isFalse();
    assertThat(runtime.jmxVisible("jvm", "shown")).isTrue();
    final String dump =
        runtime.debugDump(report -> runtime.jmxVisible(report.scopeKey(), report.id()));
    assertThat(dump).contains("shown state=").doesNotContain("hidden state=");

    plan.close();
    assertThat(runtime.jmxVisible("jvm", "hidden")).isTrue();
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
    assertThatThrownBy(() -> runtime.activate(conflicting))
        .isInstanceOf(ChaosActivationConflictException.class)
        .hasMessageContaining("jvm::taken");

    assertThat(runtime.jmxVisible("jvm", "fresh")).isTrue();
    assertThat(runtime.diagnostics().scenario("fresh")).as("nothing of the plan started").isEmpty();
  }

  @Test
  @DisplayName("two sessions' plans with one id keep their own logging setting")
  void sessionsMuteIndependently() {
    final Level previous = logger.getLevel();
    logger.setLevel(Level.ALL);
    try (ChaosSession loud = runtime.openSession("loud");
        ChaosSession quiet = runtime.openSession("quiet")) {
      quiet.activate(sessionPlan(new ChaosPlan.Observability(true, false, false), "dup"));
      loud.activate(sessionPlan(new ChaosPlan.Observability(true, true, false), "dup"));
      logged.clear();

      submitIn(quiet);
      submitIn(loud);

      assertThat(logged)
          .anyMatch(line -> line.startsWith("type=APPLIED") && line.contains(scopeOf(loud)))
          .noneMatch(line -> line.contains(scopeOf(quiet)));
    } finally {
      logger.setLevel(previous);
    }
  }

  @Test
  @DisplayName("a FAILED event of a muted session plan is not logged")
  void mutedSessionFailure() {
    try (ChaosSession quiet = runtime.openSession("quiet");
        ChaosSession loud = runtime.openSession("loud")) {
      quiet.activate(sessionPlan(new ChaosPlan.Observability(true, false, false), "dup"));
      loud.activate(sessionPlan(new ChaosPlan.Observability(true, true, false), "dup"));
      logged.clear();

      assertThatThrownBy(() -> quiet.activate(sessionScenario("dup")))
          .isInstanceOf(ChaosActivationConflictException.class);
      assertThatThrownBy(() -> loud.activate(sessionScenario("dup")))
          .isInstanceOf(ChaosActivationConflictException.class);

      assertThat(logged)
          .filteredOn(line -> line.startsWith("type=FAILED"))
          .singleElement()
          .asString()
          .contains(scopeOf(loud));
    }
  }

  @Test
  @DisplayName("a session closed with its plan still open leaves no settings behind")
  void sessionCloseClearsSettings() {
    final ChaosSession session = runtime.openSession("leaky");
    session.activate(sessionPlan(new ChaosPlan.Observability(false, false, false), "leak"));
    assertThat(runtime.jmxVisible("session:" + session.id(), "leak")).isFalse();

    session.close();
    session.close();

    assertThat(runtime.jmxVisible("session:" + session.id(), "leak")).isTrue();
    assertThat(runtime.jmxVisible("jvm", "leak")).isTrue();
    logged.clear();
    runtime.activate(scenario("leak")).close();
    assertThat(logged).anyMatch(line -> line.contains("scenarioId=\"leak\""));
  }

  @Test
  @DisplayName("jmxEnabled=false hides one session's scenario, not the same id elsewhere")
  void hidesPerScope() {
    try (ChaosSession hidden = runtime.openSession("hidden");
        ChaosSession shown = runtime.openSession("shown")) {
      shown.activate(sessionPlan(new ChaosPlan.Observability(true, true, false), "dup"));
      hidden.activate(sessionPlan(new ChaosPlan.Observability(false, true, false), "dup"));

      final String dump =
          runtime.debugDump(report -> runtime.jmxVisible(report.scopeKey(), report.id()));

      assertThat(dump)
          .contains("dup state=ACTIVE scope=" + "session:" + shown.id())
          .doesNotContain("session:" + hidden.id());
    }
  }

  private void submitIn(final ChaosSession session) {
    try (ChaosSession.ScopeBinding binding = session.bind()) {
      submit();
    }
  }

  private static String scopeOf(final ChaosSession session) {
    return "scope=\"session:" + session.id() + "\"";
  }

  private static ChaosPlan sessionPlan(
      final ChaosPlan.Observability observability, final String id) {
    return new ChaosPlan(
        new ChaosPlan.Metadata(id, ""), observability, List.of(sessionScenario(id)));
  }

  private static ChaosScenario sessionScenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
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

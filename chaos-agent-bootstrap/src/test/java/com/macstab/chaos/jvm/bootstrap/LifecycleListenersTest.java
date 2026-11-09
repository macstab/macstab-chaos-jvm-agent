package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.bootstrap.jfr.JfrIntegration;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.AgentSettings;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's own listeners (the reports' hit counter, the JFR sink) are lifecycle listeners: they
 * never read SKIPPED, so they do not make every skipped call build one. Observed through the
 * built-in logging listener, which logs each SKIPPED event it receives at FINER.
 */
@DisplayName("Lifecycle listeners")
class LifecycleListenersTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("with reports and JFR on and no listener of the user's, no SKIPPED event is built")
  void agentListenersBuildNoSkipped() throws Throwable {
    final Logger agentLogger = Logger.getLogger("com.macstab.chaos.jvm");
    final Level levelBefore = agentLogger.getLevel();
    final List<String> lines = new CopyOnWriteArrayList<>();
    final Handler capture = handler(lines);
    agentLogger.setLevel(Level.FINER);
    agentLogger.addHandler(capture);
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final ChaosReports reports =
          ChaosReports.createIfEnabled(
                  AgentSettings.of("reports=on;reportsDir=" + tempDir, Map.of()), runtime)
              .orElseThrow();
      try {
        JfrIntegration.installIfAvailable(runtime);
        runtime.activate(skippingScenario("skipping"));
        for (int i = 0; i < 5; i++) {
          runtime.beforeMethodEnter("com.example.Skipped", "run");
        }

        assertThat(lines).noneMatch(line -> line.startsWith("type=" + ChaosEvent.Type.SKIPPED));

        final List<ChaosEvent> seen = new CopyOnWriteArrayList<>();
        runtime.addEventListener(seen::add);
        runtime.beforeMethodEnter("com.example.Skipped", "run");
        assertThat(seen)
            .as("a listener of the user's still gets SKIPPED")
            .extracting(ChaosEvent::type)
            .contains(ChaosEvent.Type.SKIPPED);
      } finally {
        reports.close();
      }
    } finally {
      agentLogger.removeHandler(capture);
      agentLogger.setLevel(levelBefore);
    }
  }

  /** A scenario that matches every call and applies to none of the first thousand. */
  static ChaosScenario skippingScenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(
            ChaosSelector.method(
                Set.of(OperationType.METHOD_ENTER),
                NamePattern.exact("com.example.Skipped"),
                NamePattern.exact("run")))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.builder().activateAfterMatches(1_000).build())
        .build();
  }

  private static Handler handler(final List<String> lines) {
    final Handler handler =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            lines.add(String.valueOf(record.getMessage()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    handler.setLevel(Level.ALL);
    return handler;
  }
}

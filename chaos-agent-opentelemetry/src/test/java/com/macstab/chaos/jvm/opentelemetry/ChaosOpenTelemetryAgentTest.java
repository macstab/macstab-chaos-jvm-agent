package com.macstab.chaos.jvm.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The documented wiring in an agent JVM: {@code ChaosOpenTelemetry} on {@code
 * ChaosPlatform.current()}, the runtime from the {@code -javaagent:} jar, and metrics and span
 * events fed by calls the agent wove, not by events raised in the test.
 */
@DisplayName("ChaosOpenTelemetry in an agent JVM")
class ChaosOpenTelemetryAgentTest {

  /** The agent's modules come from its jar only, as in an application. */
  private static final List<String> AGENT_MODULES =
      List.of(
          "chaos-agent-core",
          "chaos-agent-instrumentation-jdk",
          "chaos-agent-startup-config",
          "chaos-agent-bootstrap");

  @Test
  @DisplayName("woven executor submissions reach chaos.applications and the span's events")
  void wovenCallsReachMetricsAndSpans() throws Exception {
    final Map<String, String> result = run(AgentOpenTelemetryProbeMain.class);
    final String calls = String.valueOf(AgentOpenTelemetryProbeMain.CALLS);

    assertThat(result)
        .containsEntry("handleApplied", calls)
        .containsEntry("applications", calls)
        .containsEntry("spanEvents", calls)
        .containsEntry("spanEventScenario", AgentOpenTelemetryProbeMain.SCENARIO_ID);
    assertThat(result.get("spanEventOperation")).isEqualToIgnoringCase("executor_submit");
  }

  /** Runs {@code main} with the agent jar, and returns its {@code key=value} lines. */
  private static Map<String, String> run(final Class<?> main) throws Exception {
    final String classpath =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator, -1))
            .filter(entry -> AGENT_MODULES.stream().noneMatch(entry::contains))
            .collect(Collectors.joining(File.pathSeparator));
    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + System.getProperty("chaos.bootstrap.agentJar"));
    command.add("-cp");
    command.add(classpath);
    command.add(main.getName());
    final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    final Map<String, String> values = new HashMap<>();
    for (final String line : output.split("\n", -1)) {
      final int equals = line.indexOf('=');
      if (equals > 0) {
        values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
      }
    }
    return values;
  }
}

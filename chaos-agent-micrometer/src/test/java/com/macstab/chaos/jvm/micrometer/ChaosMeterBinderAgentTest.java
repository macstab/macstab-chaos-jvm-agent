package com.macstab.chaos.jvm.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * The documented wiring in an agent JVM: the application's {@code ChaosMeterBinder} on {@code
 * ChaosPlatform.current()}, the runtime from the {@code -javaagent:} jar, and meters fed by calls
 * the agent wove, not by events raised in the test.
 */
@DisplayName("ChaosMeterBinder in an agent JVM")
class ChaosMeterBinderAgentTest {

  /** The agent's modules come from its jar only, as in an application. */
  private static final List<String> AGENT_MODULES =
      List.of(
          "chaos-agent-core",
          "chaos-agent-instrumentation-jdk",
          "chaos-agent-startup-config",
          "chaos-agent-bootstrap");

  @Test
  @DisplayName("woven executor submissions reach chaos.applications and chaos.injected.delay")
  void wovenCallsReachTheMeters() throws Exception {
    final Map<String, String> result = run(AgentMeterProbeMain.class);

    assertThat(Long.parseLong(result.get("elapsedMillis")))
        .as("three submissions delayed by 20 ms each")
        .isGreaterThanOrEqualTo(55L);
    assertThat(result).containsEntry("handleApplied", String.valueOf(AgentMeterProbeMain.CALLS));
    assertThat(result)
        .containsEntry("applications", String.valueOf(AgentMeterProbeMain.CALLS))
        .containsEntry("delayTimerCount", String.valueOf(AgentMeterProbeMain.CALLS));
    assertThat(Long.parseLong(result.get("hooksWoven")))
        .as("the executor hook group was woven for the scenario")
        .isPositive();
  }

  /** Runs {@code main} with the agent jar, and returns its {@code key=value} lines. */
  static Map<String, String> run(final Class<?> main) throws Exception {
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
    // The output goes to a file, so a child that hangs cannot block the read: it is waited for at
    // most 60 s, and destroyed in any case.
    final Path log = Files.createTempFile("chaos-agent-child", ".log");
    final Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    final String output;
    try {
      final boolean finished = process.waitFor(60, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
      output = Files.readString(log, StandardCharsets.UTF_8);
      assertThat(finished).as("the child ended within 60 s: %s", output).isTrue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
      Files.deleteIfExists(log);
    }
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

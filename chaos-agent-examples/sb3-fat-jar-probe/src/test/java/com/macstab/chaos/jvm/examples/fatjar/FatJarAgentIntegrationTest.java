package com.macstab.chaos.jvm.examples.fatjar;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starts the Spring Boot fat jar with {@code -javaagent:} and a plan that rejects pool acquisitions
 * and HTTP client calls. Hikari and OkHttp live in nested jars loaded by Spring Boot's class loader
 * long after premain, so their hooks must be matched lazily by name.
 */
@DisplayName("Agent in a Spring Boot fat jar")
class FatJarAgentIntegrationTest {

  @TempDir Path tempDir;

  private static final String PLAN =
      """
      {"scenarios": [
        {"id": "pool-down", "scope": "JVM",
         "selector": {"type": "jdbc", "operations": ["JDBC_CONNECTION_ACQUIRE"]},
         "effect": {"type": "reject", "message": "pool down"}},
        {"id": "http-down", "scope": "JVM",
         "selector": {"type": "httpClient", "operations": ["HTTP_CLIENT_SEND"]},
         "effect": {"type": "reject", "message": "http down"}}]}
      """;

  @Test
  @DisplayName("without the agent the pool and OkHttp work")
  void baseline() throws Exception {
    final Map<String, String> result = run(List.of());

    assertThat(result).containsEntry("jdbc", "ok").containsEntry("okhttp", "200");
  }

  @Test
  @DisplayName("with the agent the Hikari pool and OkHttp hooks fire")
  void hooksFire() throws Exception {
    final String plan = Base64.getEncoder().encodeToString(PLAN.getBytes(StandardCharsets.UTF_8));
    final Map<String, String> result =
        run(
            List.of(
                "-javaagent:"
                    + System.getProperty("chaos.bootstrap.agentJar")
                    + "=configBase64="
                    + plan));

    assertThat(result.get("jdbc")).isEqualTo("java.sql.SQLException");
    assertThat(result.get("okhttp")).isEqualTo("java.io.IOException");
  }

  private Map<String, String> run(final List<String> jvmArgs) throws Exception {
    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(jvmArgs);
    command.add("-jar");
    command.add(System.getProperty("chaos.fatjar.app"));
    // Output to a file, not a pipe: the wait below is bounded only if nothing has to read the
    // child's output first. A child still running at the timeout is destroyed.
    final Path log = Files.createTempFile(tempDir, "fatjar", ".out");
    final Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    final boolean finished;
    try {
      finished = process.waitFor(60, TimeUnit.SECONDS);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
    }
    final List<String> lines = Files.readAllLines(log);
    final String output = String.join("\n", lines);
    assertThat(finished).as("the application should finish within 60 s: " + output).isTrue();
    final Map<String, String> values = new HashMap<>();
    for (final String line : lines) {
      final int equals = line.indexOf('=');
      if (equals > 0 && !line.startsWith("[")) {
        values.put(line.substring(0, equals), line.substring(equals + 1));
      }
    }
    assertThat(values).as(output).containsKeys("jdbc", "okhttp");
    return values;
  }
}

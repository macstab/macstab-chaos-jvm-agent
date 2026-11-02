package com.macstab.chaos.jvm.examples.fatjar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Starts the Spring Boot fat jar with {@code -javaagent:} and a plan that rejects pool acquisitions
 * and HTTP client calls. Hikari and OkHttp live in nested jars loaded by Spring Boot's class loader
 * long after premain, so their hooks must be matched lazily by name.
 */
@DisplayName("Agent in a Spring Boot fat jar")
class FatJarAgentIntegrationTest {

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

  private static Map<String, String> run(final List<String> jvmArgs) throws Exception {
    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(jvmArgs);
    command.add("-jar");
    command.add(System.getProperty("chaos.fatjar.app"));
    final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    final Map<String, String> values = new HashMap<>();
    final StringBuilder output = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        output.append(line).append('\n');
        final int equals = line.indexOf('=');
        if (equals > 0 && !line.startsWith("[")) {
          values.put(line.substring(0, equals), line.substring(equals + 1));
        }
      }
    }
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(output.toString()).isTrue();
    assertThat(values).as(output.toString()).containsKeys("jdbc", "okhttp");
    return values;
  }
}

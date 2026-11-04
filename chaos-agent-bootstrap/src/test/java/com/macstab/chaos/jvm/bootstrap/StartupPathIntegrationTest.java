package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's start-up paths that a healthy single start never takes, each in a child JVM running
 * {@link StartupPathProbeMain}: the dynamic-attach opt-in, concurrent and re-entrant
 * initialization, failures while installing and while rolling back, a taken or refused MBean name,
 * a JVM without JFR and one without the attach mechanism.
 */
@DisplayName("Agent start-up paths (end-to-end)")
class StartupPathIntegrationTest {

  private static final long TIMEOUT_SECONDS = 90L;

  private static final String GOOD_PLAN =
      """
      {"metadata":{"name":"watched"},
       "observability":{"jmxEnabled":true,"structuredLoggingEnabled":false,"debugDumpOnStart":true},
       "scenarios":[{"id":"watched-sleep","scope":"JVM",
         "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
         "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
      """;

  /** Parses, but the runtime refuses it: a torn write cannot apply to an executor submit. */
  private static final String REFUSED_PLAN =
      """
      {"metadata":{"name":"refused"},"scenarios":[{"id":"refused-torn","scope":"JVM",
       "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
       "effect":{"type":"tornWrite"}}]}
      """;

  @TempDir Path tempDir;

  private Path goodPlan;
  private Path refusedPlan;
  private Path quietPlan;
  private Path reports;

  @BeforeEach
  void writePlans() throws Exception {
    goodPlan = Files.writeString(tempDir.resolve("good-plan.json"), GOOD_PLAN);
    refusedPlan = Files.writeString(tempDir.resolve("refused-plan.json"), REFUSED_PLAN);
    quietPlan =
        Files.writeString(
            tempDir.resolve("quiet-plan.json"),
            GOOD_PLAN.replace("\"debugDumpOnStart\":true", "\"debugDumpOnStart\":false"));
    reports = Files.createDirectories(tempDir.resolve("reports"));
  }

  @Nested
  @DisplayName("dynamic attach")
  class DynamicAttach {

    @Test
    @DisplayName("is ignored without the opt-in and installs once the property opts in")
    void propertyOptIn() throws Exception {
      final Result result = run(Map.of(), List.of(), "agentmain");

      assertThat(result.value("installedBefore"))
          .isEqualTo("IllegalStateException:chaos agent is not installed");
      assertThat(result.value("installedAfterAttach")).isEqualTo("false");
      assertThat(result.output())
          .contains(
              "[chaos-agent] ignoring dynamic attach: set -Dmacstab.chaos.allow-dynamic-attach");
      assertThat(result.value("installedAfterOptIn")).isEqualTo("true");
    }

    @Test
    @DisplayName("is ignored when the environment variable says false")
    void environmentOptOut() throws Exception {
      final Result result =
          run(Map.of("MACSTAB_CHAOS_ALLOW_DYNAMIC_ATTACH", "false"), List.of(), "agentmain");

      assertThat(result.value("installedAfterAttach")).isEqualTo("false");
      assertThat(result.output()).contains("[chaos-agent] ignoring dynamic attach");
    }

    @Test
    @DisplayName("installs when the environment variable opts in")
    void environmentOptIn() throws Exception {
      final Result result =
          run(Map.of("MACSTAB_CHAOS_ALLOW_DYNAMIC_ATTACH", "true"), List.of(), "agentmain");

      assertThat(result.value("installedAfterAttach")).isEqualTo("true");
      assertThat(result.output()).doesNotContain("ignoring dynamic attach");
    }
  }

  @Nested
  @DisplayName("concurrent initialization")
  class ConcurrentInitialization {

    @Test
    @DisplayName(
        "waits for the installing thread, refuses re-entry, and hides the half-installed runtime")
    void waitsForTheInstallingThread() throws Exception {
      final Result result = run(Map.of(), List.of(), "concurrent", "ok");

      assertThat(result.value("reentry"))
          .isEqualTo(
              "IllegalStateException:chaos agent initialization re-entered on the initializing"
                  + " thread");
      assertThat(result.value("currentWhileInstalling"))
          .isEqualTo("IllegalStateException:chaos agent is not installed");
      assertThat(result.value("first")).isEqualTo("runtime");
      assertThat(result.value("waiter")).isEqualTo("runtime");
      assertThat(result.value("localInstallReturnsInstalled")).isEqualTo("true");
    }

    @Test
    @DisplayName("a waiting initializer sees the installing thread's runtime exception")
    void runtimeFailureReachesTheWaiter() throws Exception {
      final Result result = run(Map.of(), List.of(), "concurrent", "runtime");

      assertThat(result.value("first")).isEqualTo("IllegalStateException:bridge refused");
      assertThat(result.value("waiter")).isEqualTo("IllegalStateException:bridge refused");
    }

    @Test
    @DisplayName("a waiting initializer sees the installing thread's error")
    void errorReachesTheWaiter() throws Exception {
      final Result result = run(Map.of(), List.of(), "concurrent", "error");

      assertThat(result.value("first")).isEqualTo("LinkageError:bridge refused");
      assertThat(result.value("waiter")).isEqualTo("LinkageError:bridge refused");
    }

    @Test
    @DisplayName("a checked exception while installing is wrapped for both threads")
    void checkedFailureIsWrapped() throws Exception {
      final Result result = run(Map.of(), List.of(), "concurrent", "checked");

      final String wrapped =
          "IllegalStateException:agent initialization failed <- TimeoutException:bridge refused";
      assertThat(result.value("first")).isEqualTo(wrapped);
      assertThat(result.value("waiter")).isEqualTo(wrapped);
    }

    @Test
    @DisplayName(
        "a local install waiting for the install lock returns the runtime installed meanwhile")
    void localInstallAfterLock() throws Exception {
      final Result result = run(Map.of(), List.of(), "local-install-lock");

      assertThat(result.value("localInstallReturnsInstalled")).isEqualTo("true");
    }
  }

  @Nested
  @DisplayName("start-up failures")
  class StartupFailures {

    @Test
    @DisplayName(
        "a refused watched plan stops its poller and is reported; a retry watches the file")
    void refusedWatchedPlanThenRetry() throws Exception {
      final Result result = run(Map.of(), List.of(), "startup-retry");

      assertThat(result.value("firstAttempt"))
          .startsWith("IllegalStateException:plan not applied, previous scenarios kept:")
          .contains("TornWriteEffect");
      assertThat(result.value("pollerAfterFailure")).isEqualTo("false");
      assertThat(result.value("failureStatus"))
          .startsWith("state=error|")
          .contains("|source=file:" + refusedPlan);
      assertThat(result.value("pollerAfterRetry")).isEqualTo("true");
      assertThat(result.value("watchedScenario")).isEqualTo("ACTIVE");
      assertThat(result.value("retryStatus")).startsWith("state=ok|scenarios=1|");
      assertThat(result.output().split("macstab-chaos diagnostics", -1))
          .as("the watched plan's dump is printed once")
          .hasSize(2);
    }

    @Test
    @DisplayName("failures while undoing a failed start-up are attached to the start-up failure")
    void rollbackFailuresAreSuppressed() throws Exception {
      final Result result =
          run(
              Map.of(),
              List.of(
                  "-Djavax.management.builder.initial="
                      + RefusingMBeanServerBuilder.class.getName()),
              "rollback-failures");

      assertThat(result.value("failure")).isEqualTo("ChaosValidationException");
      assertThat(result.value("suppressed.IllegalStateException"))
          .isEqualTo("transformer removal refused");
      assertThat(result.value("suppressed.MBeanRegistrationException"))
          .isEqualTo("unregistration refused");
      assertThat(result.value("installed")).isEqualTo("false");
      assertThat(result.value("failureStatus"))
          .startsWith("state=error|scenarios=0|line=0|column=0|reason=")
          .contains("TornWriteEffect");
    }

    @Test
    @DisplayName("a local install without the attach mechanism fails with the -javaagent advice")
    void localInstallWithoutAttach() throws Exception {
      final Result result = run(Map.of(), List.of("-XX:+DisableAttachMechanism"), "no-attach");

      assertThat(result.value("localInstall")).startsWith("IllegalStateException:");
    }
  }

  @Nested
  @DisplayName("MBean and JFR")
  class MBeanAndJfr {

    @Test
    @DisplayName(
        "a taken MBean name is left alone, a requested dump is printed once, and reports"
            + " close during shutdown")
    void takenMBeanName() throws Exception {
      final Result result = run(Map.of(), List.of(), "mbean-taken");

      assertThat(result.output())
          .contains(
              "[chaos-agent] MBean registration skipped: com.macstab.chaos.jvm:type=ChaosDiagnostics"
                  + " is already registered in this JVM");
      assertThat(result.value("takenBeanKept")).isEqualTo("true");
      assertThat(result.value("installed")).isEqualTo("true");
      assertThat(result.output().split("macstab-chaos diagnostics", -1))
          .as("the agent args and the plan both ask for the dump; it is printed once")
          .hasSize(2);
      assertThat(result.value("closedDuringShutdown")).isEqualTo("true");
    }

    @Test
    @DisplayName(
        "an MBean server that refuses the MBean leaves the agent installed without it; the dump"
            + " the agent args ask for is printed")
    void refusedMBean() throws Exception {
      final Result result =
          run(
              Map.of(),
              List.of(
                  "-Djavax.management.builder.initial="
                      + RefusingMBeanServerBuilder.class.getName()),
              "mbean-refused");

      assertThat(result.output())
          .contains("[chaos-agent] MBean registration skipped: registration refused");
      assertThat(result.value("installed")).isEqualTo("true");
      assertThat(result.value("chaosBeanRegistered")).isEqualTo("false");
      assertThat(result.output().split("macstab-chaos diagnostics", -1))
          .as("the agent args ask for the dump the plan does not")
          .hasSize(2);
    }

    @Test
    @DisplayName("on a JVM without the jdk.jfr module the agent starts and applies chaos")
    void withoutJfr() throws Exception {
      final Result result =
          runWithAgent(List.of("--limit-modules", "java.se,jdk.unsupported,jdk.attach"), "no-jfr");

      assertThat(result.value("jfrModule")).isEqualTo("false");
      assertThat(Long.parseLong(result.value("sleepMillis"))).isGreaterThanOrEqualTo(150L);
    }
  }

  // ── child JVM ──────────────────────────────────────────────────────────────

  private Result run(
      final Map<String, String> environment, final List<String> jvmArgs, final String... args)
      throws Exception {
    final List<String> command = new ArrayList<>();
    command.add("-Djdk.attach.allowAttachSelf=true");
    command.add("-XX:+EnableDynamicAgentLoading");
    command.addAll(jvmArgs);
    return execute(environment, command, args);
  }

  private Result runWithAgent(final List<String> jvmArgs, final String... args) throws Exception {
    final List<String> command = new ArrayList<>();
    command.add("-javaagent:" + System.getProperty("chaos.bootstrap.agentJar"));
    command.addAll(jvmArgs);
    return execute(Map.of(), command, args);
  }

  private Result execute(
      final Map<String, String> environment, final List<String> options, final String... args)
      throws Exception {
    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(ChildJvmProbe.coverageAgent());
    command.addAll(options);
    command.add("-Dchaos.bootstrap.agentJar=" + System.getProperty("chaos.bootstrap.agentJar"));
    command.add("-Dchaos.test.goodPlan=" + goodPlan);
    command.add("-Dchaos.test.badPlan=" + refusedPlan);
    command.add("-Dchaos.test.quietPlan=" + quietPlan);
    command.add("-Dchaos.test.reports=" + reports);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(StartupPathProbeMain.class.getName());
    command.addAll(List.of(args));

    final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().remove("MACSTAB_CHAOS_ALLOW_DYNAMIC_ATTACH");
    builder.environment().putAll(environment);
    final Process process = builder.start();
    final List<String> lines = new CopyOnWriteArrayList<>();
    // Read on another thread, so a hanging child still reaches the timeout below.
    final Thread reader =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  try (BufferedReader in =
                      new BufferedReader(
                          new InputStreamReader(
                              process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                      lines.add(line);
                    }
                  } catch (final java.io.IOException closed) {
                    // the process was destroyed
                  }
                });
    final boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    reader.join(TimeUnit.SECONDS.toMillis(5));
    final String output = String.join("\n", lines);
    assertThat(finished)
        .as("probe JVM must finish within %d s: %s", TIMEOUT_SECONDS, output)
        .isTrue();
    assertThat(process.exitValue()).as("probe JVM exit value, output:\n%s", output).isZero();
    final Map<String, String> values = new LinkedHashMap<>();
    for (final String line : lines) {
      final int separator = line.indexOf('=');
      if (separator > 0 && !line.startsWith(" ") && !line.startsWith("[")) {
        values.put(line.substring(0, separator), line.substring(separator + 1).trim());
      }
    }
    return new Result(output, values);
  }

  /** Output of one probe run. */
  private record Result(String output, Map<String, String> values) {

    /** Returns the value printed for {@code key}; fails the test when it is absent. */
    String value(final String key) {
      assertThat(values)
          .as("probe output must contain %s=, output:\n%s", key, output)
          .containsKey(key);
      return values.get(key);
    }
  }
}

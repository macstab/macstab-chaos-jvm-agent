package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a probe main class in a child JVM that loads the agent jar through {@code -javaagent:} with
 * a plan file, and returns the {@code key=value} lines the probe printed.
 *
 * <p>The child JVM uses this test JVM's classpath plus an optional extra classpath, so probes can
 * use test-only libraries (H2, HikariCP, OkHttp) without adding them to the agent.
 */
final class ChildJvmProbe {

  private static final long TIMEOUT_SECONDS = 60L;

  private final Path workDir;

  ChildJvmProbe(final Path workDir) {
    this.workDir = workDir;
  }

  /**
   * Runs {@code mainClass mode} in a child JVM without {@code -javaagent:}; the probe attaches the
   * agent itself (self-attach is allowed for the child).
   *
   * @param mainClass the probe main class
   * @param mode the probe mode passed as the first program argument
   * @return the probe's {@code key=value} output lines
   */
  ProbeResult runWithoutAgent(final Class<?> mainClass, final String mode) throws Exception {
    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Djdk.attach.allowAttachSelf=true");
    command.add("-XX:+EnableDynamicAgentLoading");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(mainClass.getName());
    command.add(mode);
    return execute(command);
  }

  /**
   * Runs {@code mainClass mode} in a child JVM with the agent attached and no startup plan.
   *
   * @param mainClass the probe main class
   * @param mode the probe mode passed as the first program argument
   * @param jvmArgs additional JVM arguments placed before the classpath
   * @return the probe's {@code key=value} output lines
   */
  ProbeResult runWithoutPlan(final Class<?> mainClass, final String mode, final String... jvmArgs)
      throws Exception {
    return runWithoutPlanOnClasspath(mainClass, mode, null, jvmArgs);
  }

  /**
   * As {@link #runWithoutPlan(Class, String, String...)}, with {@code extraClasspath} appended to
   * the child's classpath.
   *
   * @param mainClass the probe main class
   * @param mode the probe mode passed as the first program argument
   * @param extraClasspath extra classpath entries, or {@code null}
   * @param jvmArgs additional JVM arguments placed before the classpath
   * @return the probe's {@code key=value} output lines
   */
  ProbeResult runWithoutPlanOnClasspath(
      final Class<?> mainClass,
      final String mode,
      final String extraClasspath,
      final String... jvmArgs)
      throws Exception {
    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + System.getProperty("chaos.bootstrap.agentJar"));
    command.addAll(List.of(jvmArgs));
    command.add("-cp");
    final String baseClasspath = System.getProperty("java.class.path");
    command.add(
        extraClasspath == null || extraClasspath.isEmpty()
            ? baseClasspath
            : baseClasspath + File.pathSeparator + extraClasspath);
    command.add(mainClass.getName());
    command.add(mode);
    return execute(command);
  }

  /**
   * Runs {@code mainClass mode} in a child JVM with the agent attached and {@code plan} loaded.
   *
   * @param plan the plan written to the child's config file
   * @param mainClass the probe main class
   * @param mode the probe mode passed as the first program argument
   * @param extraClasspath extra classpath entries, or {@code null}
   * @param jvmArgs additional JVM arguments placed before the classpath
   * @return the probe's {@code key=value} output lines; later keys overwrite earlier ones
   */
  ProbeResult run(
      final ChaosPlan plan,
      final Class<?> mainClass,
      final String mode,
      final String extraClasspath,
      final String... jvmArgs)
      throws Exception {
    final Path configFile = workDir.resolve(mode + "-plan.json");
    Files.writeString(configFile, ChaosPlanMapper.write(plan));
    final String agentJar = System.getProperty("chaos.bootstrap.agentJar");

    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-javaagent:" + agentJar + "=configFile=" + configFile);
    command.addAll(List.of(jvmArgs));
    command.add("-cp");
    final String baseClasspath = System.getProperty("java.class.path");
    command.add(
        extraClasspath == null || extraClasspath.isEmpty()
            ? baseClasspath
            : baseClasspath + File.pathSeparator + extraClasspath);
    command.add(mainClass.getName());
    command.add(mode);

    return execute(command);
  }

  private static ProbeResult execute(final List<String> command) throws Exception {
    final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Read on another thread: reading to the end first would block until the child exits, so a
    // hanging child would never reach the timeout below.
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
    return new ProbeResult(output, parse(lines));
  }

  private static Map<String, String> parse(final List<String> lines) {
    final Map<String, String> values = new LinkedHashMap<>();
    for (final String line : lines) {
      final int separator = line.indexOf('=');
      if (separator > 0 && !line.startsWith(" ") && !line.startsWith("[")) {
        values.put(line.substring(0, separator), line.substring(separator + 1).trim());
      }
    }
    return values;
  }

  /** Output of one probe run. */
  record ProbeResult(String output, Map<String, String> values) {

    /** Returns the value printed for {@code key}; fails the test when it is absent. */
    String value(final String key) {
      assertThat(values)
          .as("probe output must contain %s=, output:\n%s", key, output)
          .containsKey(key);
      return values.get(key);
    }

    /** Returns the value printed for {@code key} as a {@code long}. */
    long longValue(final String key) {
      return Long.parseLong(value(key));
    }
  }
}

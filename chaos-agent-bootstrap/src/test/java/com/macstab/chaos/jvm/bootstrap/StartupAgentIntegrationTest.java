package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StartupAgent integration")
class StartupAgentIntegrationTest {

  private static final long DELAY_MS = 70L;

  @TempDir Path tempDir;

  @Nested
  @DisplayName("executor submit delay")
  class ExecutorSubmitDelay {

    @Test
    @DisplayName("startup agent delays executor submit")
    void startupAgentDelaysExecutorSubmit() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-executor", ""),
              null,
              List.of(
                  ChaosScenario.builder("startup-delay")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          new ChaosSelector.ExecutorSelector(
                              Set.of(OperationType.EXECUTOR_SUBMIT),
                              NamePattern.exact("java.util.concurrent.ThreadPoolExecutor"),
                              NamePattern.any(),
                              null))
                      .effect(ChaosEffect.delay(Duration.ofMillis(70)))
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final ChildJvmProbe.ProbeResult result = runProbe(plan, "executor");
      assertThat(result.longValue("applied.startup-delay")).isEqualTo(1L);
      assertThat(result.longValue("elapsedMillis"))
          .as("the submission takes the whole configured %d ms", DELAY_MS)
          .isGreaterThanOrEqualTo(DELAY_MS);
    }

    @Test
    @DisplayName("weaves without making the JDK warn about sun.misc.Unsafe")
    void weavingPrintsNoUnsafeWarning() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-quiet", ""),
              null,
              List.of(
                  ChaosScenario.builder("quiet-delay")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                      .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final ChildJvmProbe.ProbeResult result = runProbe(plan, "executor");

      assertThat(result.longValue("applied.quiet-delay")).isEqualTo(1L);
      // JDK 24 and later print this on the first sun.misc.Unsafe memory access (JEP 498).
      assertThat(result.output()).doesNotContain("sun.misc.Unsafe");
    }
  }

  @Nested
  @DisplayName("resource lookup suppression")
  class ResourceLookupSuppression {

    @Test
    @DisplayName("startup agent suppresses resource lookup")
    void startupAgentCanSuppressResourceLookup() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-resource", ""),
              null,
              List.of(
                  ChaosScenario.builder("resource-suppress")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          ChaosSelector.classLoading(
                              Set.of(OperationType.RESOURCE_LOAD),
                              NamePattern.exact("probe-resource.txt")))
                      .effect(ChaosEffect.suppress())
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "resource").output();
      assertThat(output).contains("resource=missing");
    }
  }

  @Nested
  @DisplayName("thread start rejection")
  class ThreadStartRejection {

    @Test
    @DisplayName("startup agent rejects thread start matching selector")
    void startupAgentRejectsMatchingThreadStart() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-thread", ""),
              null,
              List.of(
                  ChaosScenario.builder("thread-reject")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          new ChaosSelector.ThreadSelector(
                              Set.of(OperationType.THREAD_START),
                              ChaosSelector.ThreadKind.ANY,
                              NamePattern.exact("chaos-probe-thread"),
                              null))
                      .effect(ChaosEffect.reject("chaos-thread-rejection"))
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "thread").output();
      assertThat(output).contains("thread=rejected");
    }
  }

  @Nested
  @DisplayName("class load rejection")
  class ClassLoadRejection {

    @Test
    @DisplayName("startup agent rejects class load matching selector")
    void startupAgentRejectsMatchingClassLoad() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-classload", ""),
              null,
              List.of(
                  ChaosScenario.builder("classload-reject")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          ChaosSelector.classLoading(
                              Set.of(OperationType.CLASS_LOAD),
                              NamePattern.exact("org.assertj.core.api.Assertions")))
                      .effect(ChaosEffect.reject("chaos-classload-rejection"))
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "classload").output();
      assertThat(output).contains("classload=rejected");
    }
  }

  @Nested
  @DisplayName("GC suppression")
  class GcSuppression {

    @Test
    @DisplayName("startup agent suppresses System.gc()")
    void startupAgentSuppressesSystemGc() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-gc", ""),
              null,
              List.of(
                  ChaosScenario.builder("gc-suppress")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)))
                      .effect(ChaosEffect.suppress())
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final ChildJvmProbe.ProbeResult result = runProbe(plan, "gc");
      assertThat(result.longValue("applied.gc-suppress")).isPositive();
      assertThat(result.longValue("gcCollections")).as("System.gc() collected nothing").isZero();
    }

    @Test
    @DisplayName("without a GC scenario System.gc() collects")
    void systemGcCollectsWithoutScenario() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-no-gc", ""),
              null,
              List.of(
                  ChaosScenario.builder("exit-suppress")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_EXIT_REQUEST)))
                      .effect(ChaosEffect.suppress())
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      assertThat(runProbe(plan, "gc").longValue("gcCollections")).isPositive();
    }
  }

  @Nested
  @DisplayName("Exit suppression")
  class ExitSuppression {

    @Test
    @DisplayName("startup agent suppresses System.exit() and throws SecurityException")
    void startupAgentSuppressesSystemExit() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-exit", ""),
              null,
              List.of(
                  ChaosScenario.builder("exit-suppress")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_EXIT_REQUEST)))
                      .effect(ChaosEffect.suppress())
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "exit").output();
      assertThat(output).contains("exit=suppressed");
    }
  }

  @Nested
  @DisplayName("malformed inline plan")
  class MalformedInlinePlan {

    @Test
    @DisplayName("aborts the start without printing the parser's excerpt of the plan")
    void stderrCarriesNoInput() throws Exception {
      // An unquoted token: Jackson's own message quotes it ("Unrecognized token 'SECRET123'").
      final List<String> command =
          List.of(
              Path.of(System.getProperty("java.home"), "bin", "java").toString(),
              "-javaagent:"
                  + System.getProperty("chaos.bootstrap.agentJar")
                  + "=configJson={\"scenarios\":SECRET123}",
              "-cp",
              System.getProperty("java.class.path"),
              AgentProcessProbeMain.class.getName(),
              "gc");
      final ChildJvmProbe.BoundedRun run =
          ChildJvmProbe.runBounded(
              command, tempDir.resolve("malformed.out"), Duration.ofSeconds(30));
      assertThat(run.finished()).as("probe JVM should finish: " + run.output()).isTrue();

      assertThat(run.exitValue()).as("the start is aborted: " + run.output()).isNotZero();
      assertThat(run.output()).contains("malformed JSON").doesNotContain("SECRET123");
    }
  }

  @Nested
  @DisplayName("a class-path copy of the agent")
  class ClassPathCopy {

    @Test
    @DisplayName("of another version shadows -javaagent: one warning names both jars")
    void anotherVersionIsReported() throws Exception {
      final Path copy = agentCopy("other-version.jar", "0.0.1-other");

      final String output = runShadowed(copy);

      assertThat(output)
          .contains("[chaos-agent] agent copies of different versions are on the class path")
          .contains(copy.toRealPath().toString())
          .contains("0.0.1-other")
          .contains(Path.of(System.getProperty("chaos.bootstrap.agentJar")).toRealPath().toString())
          .contains(agentVersion());
    }

    @Test
    @DisplayName("of the same version is not reported")
    void sameVersionIsQuiet() throws Exception {
      final Path copy = agentCopy("same-version.jar", agentVersion());

      final String output = runShadowed(copy);

      assertThat(output).doesNotContain("agent copies of different versions");
    }

    /** A jar with the agent's entry class and the given {@code Implementation-Version}. */
    private Path agentCopy(final String name, final String version) throws Exception {
      final java.util.jar.Manifest manifest = new java.util.jar.Manifest();
      manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
      manifest
          .getMainAttributes()
          .put(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION, version);
      final Path jar = tempDir.resolve(name);
      final String entry = ChaosAgentBootstrap.class.getName().replace('.', '/') + ".class";
      try (java.util.jar.JarOutputStream out =
              new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest);
          java.io.InputStream in = ClassLoader.getSystemResourceAsStream(entry)) {
        out.putNextEntry(new java.util.jar.JarEntry(entry));
        java.util.Objects.requireNonNull(in, entry).transferTo(out);
        out.closeEntry();
      }
      return jar;
    }

    /** Runs the agent with {@code copy} in front of the class path; returns stdout and stderr. */
    private String runShadowed(final Path copy) throws Exception {
      final List<String> command =
          List.of(
              Path.of(System.getProperty("java.home"), "bin", "java").toString(),
              "-javaagent:" + System.getProperty("chaos.bootstrap.agentJar"),
              "-cp",
              copy + java.io.File.pathSeparator + System.getProperty("java.class.path"),
              AgentProcessProbeMain.class.getName(),
              "gc");
      final ChildJvmProbe.BoundedRun run =
          ChildJvmProbe.runBounded(
              command, tempDir.resolve(copy.getFileName() + ".out"), Duration.ofSeconds(30));
      assertThat(run.finished()).as("probe JVM should finish: " + run.output()).isTrue();
      assertThat(run.exitValue()).as("probe JVM exit value: " + run.output()).isZero();
      final String output = run.output();
      return output;
    }

    private static String agentVersion() throws Exception {
      try (java.util.jar.JarFile agent =
          new java.util.jar.JarFile(System.getProperty("chaos.bootstrap.agentJar"))) {
        return String.valueOf(
            agent
                .getManifest()
                .getMainAttributes()
                .getValue(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION));
      }
    }
  }

  private ChildJvmProbe.ProbeResult runProbe(final ChaosPlan plan, final String mode)
      throws Exception {
    final Path configFile = tempDir.resolve(mode + "-plan.json");
    Files.writeString(configFile, ChaosPlanMapper.write(plan));
    final String agentJar = System.getProperty("chaos.bootstrap.agentJar");
    final java.util.List<String> command = new java.util.ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    // No coverage agent here: these plans reach every file write, thread start, class load, gc and
    // exit of the JVM, the coverage agent's own dump at shutdown included.
    command.add("-javaagent:" + agentJar + "=configFile=" + configFile);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(AgentProcessProbeMain.class.getName());
    command.add(mode);
    final ChildJvmProbe.BoundedRun run =
        ChildJvmProbe.runBounded(command, tempDir.resolve(mode + ".out"), Duration.ofSeconds(30));
    assertThat(run.finished())
        .as("probe JVM should finish: %s%nthreads:%n%s", run.output(), run.threads())
        .isTrue();
    assertThat(run.exitValue()).as("probe JVM exit value: " + run.output()).isZero();
    return run.result();
  }
}

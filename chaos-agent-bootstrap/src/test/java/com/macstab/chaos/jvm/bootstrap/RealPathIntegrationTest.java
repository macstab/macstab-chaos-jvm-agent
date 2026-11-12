package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Effects, policies, stressors and startup sources proven on woven JDK calls in a JVM with the
 * agent, not only through in-process runtime calls: each test runs {@link RealPathProbeMain} in a
 * child JVM.
 */
@DisplayName("Real calls in a JVM with the agent")
class RealPathIntegrationTest {

  @TempDir Path tempDir;

  private ChildJvmProbe probe;

  @BeforeEach
  void setUp() {
    probe = new ChildJvmProbe(tempDir);
  }

  private ChildJvmProbe.ProbeResult selfAttached(final String mode) throws Exception {
    return probe.runWithoutAgent(RealPathProbeMain.class, mode);
  }

  private static List<Long> matches(final ChildJvmProbe.ProbeResult result) {
    final String listed = result.value("appliedMatches");
    return listed.isEmpty()
        ? List.of()
        : Arrays.stream(listed.split(",")).map(Long::parseLong).toList();
  }

  @Nested
  @DisplayName("A gate on Thread.sleep")
  class Gate {

    @Test
    @DisplayName("holds the call until release(), which lets it return at once")
    void heldUntilReleased() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("gate-release");

      assertThat(result.value("heldAfter300ms")).isEqualTo("true");
      assertThat(result.value("returned")).isEqualTo("true");
      assertThat(result.longValue("returnedAfterReleaseMillis")).isLessThan(1_000L);
      assertThat(result.longValue("elapsedMillis")).isGreaterThanOrEqualTo(300L);
      assertThat(result.longValue("applied")).isEqualTo(1L);
    }

    @Test
    @DisplayName("lets the call go once its maxBlock has passed")
    void releasedAtMaxBlock() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("gate-max-block");

      assertThat(result.value("returnedBeforeMaxBlock")).isEqualTo("false");
      assertThat(result.value("returned")).isEqualTo("true");
      assertThat(result.longValue("elapsedMillis")).isBetween(200L, 2_000L);
    }

    @Test
    @DisplayName("lets the held call go when the scenario is stopped")
    void releasedByStop() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("gate-stop");

      assertThat(result.value("heldAfter300ms")).isEqualTo("true");
      assertThat(result.value("returned")).isEqualTo("true");
      assertThat(result.longValue("returnedAfterReleaseMillis")).isLessThan(1_000L);
    }
  }

  @Nested
  @DisplayName("Activation policies on woven Thread.sleep calls")
  class ActivationPolicies {

    @Test
    @DisplayName("probability 0.3 applies to about 30 % of 1,000 calls")
    void probability() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("policy:probability:42");

      assertThat(result.longValue("matched")).isEqualTo(1_000L);
      // Binomial(1000, 0.3): mean 300, sd 14.5; the bound is about 7 sd wide.
      assertThat(result.longValue("applied")).isBetween(200L, 400L);
    }

    @Test
    @DisplayName("the same seed makes the same decisions in another JVM; another seed does not")
    void seedDeterminism() throws Exception {
      final List<Long> first = matches(selfAttached("policy:probability:42"));
      final List<Long> second = matches(selfAttached("policy:probability:42"));
      final List<Long> otherSeed = matches(selfAttached("policy:probability:43"));

      assertThat(first).isNotEmpty().isEqualTo(second);
      assertThat(otherSeed).isNotEqualTo(first);
    }

    @Test
    @DisplayName("replayMatches applies exactly the listed match numbers")
    void replay() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("policy:replay:3,7,8,20,50");

      assertThat(matches(result)).containsExactly(3L, 7L, 8L, 20L, 50L);
      assertThat(result.longValue("matched")).isEqualTo(50L);
    }

    @Test
    @DisplayName("a warm-up of 10 matches applies from the 11th call on")
    void warmUp() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("policy:warm-up");

      assertThat(result.longValue("matched")).isEqualTo(15L);
      assertThat(matches(result)).containsExactly(11L, 12L, 13L, 14L, 15L);
    }

    @Test
    @DisplayName("a rate limit of 5 per second applies 5 times per window")
    void rateLimit() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("policy:rate-limit");

      assertThat(result.longValue("appliedFirstWindow")).isEqualTo(5L);
      assertThat(result.longValue("applied")).isEqualTo(10L);
    }

    @Test
    @DisplayName("before activeFrom nothing is matched or applied; after it every call is")
    void activeFrom() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("policy:active-from");

      assertThat(result.longValue("matchedBeforeFrom")).isZero();
      assertThat(result.longValue("appliedBeforeFrom")).isZero();
      assertThat(result.longValue("applied")).isEqualTo(10L);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"active-for", "active-until"})
    @DisplayName("a call after the time window ends is not delayed, and the scenario is INACTIVE")
    void windowExpiry(final String policy) throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("policy:" + policy);

      assertThat(result.longValue("firstCallMillis")).isGreaterThanOrEqualTo(200L);
      assertThat(result.longValue("lateCallMillis")).isLessThan(100L);
      assertThat(result.value("state")).isEqualTo("INACTIVE");
      assertThat(result.longValue("applied")).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("Clock-skew modes on Instant.now()")
  class ClockSkewModes {

    @Test
    @DisplayName("FREEZE returns the same instant on every read")
    void freeze() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("clock-mode:FREEZE");

      assertThat(result.longValue("read2")).isEqualTo(result.longValue("read1"));
      assertThat(result.longValue("read3")).isEqualTo(result.longValue("read1"));
      assertThat(result.longValue("offset3")).isLessThanOrEqualTo(-300L);
      assertThat(result.longValue("offsetAfterStop")).isBetween(-50L, 50L);
    }

    @Test
    @DisplayName("DRIFT moves the clock further with every read")
    void drift() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("clock-mode:DRIFT");

      assertThat(result.longValue("offset1")).isBetween(9_950L, 10_050L);
      assertThat(result.longValue("offset2")).isBetween(19_950L, 20_050L);
      assertThat(result.longValue("offset3")).isBetween(29_950L, 30_050L);
    }

    @Test
    @DisplayName("REGRESS returns the first read unchanged, then steps back once and stays back")
    void regress() throws Exception {
      final ChildJvmProbe.ProbeResult result = selfAttached("clock-mode:REGRESS");

      assertThat(result.longValue("offset1")).isBetween(-50L, 50L);
      assertThat(result.longValue("read2"))
          .as("time ran backwards")
          .isLessThan(result.longValue("read1"));
      assertThat(result.longValue("offset2")).isBetween(-10_050L, -9_950L);
      assertThat(result.longValue("offset3")).isBetween(-10_050L, -9_950L);
    }
  }

  @Nested
  @DisplayName("Stressor loads")
  class StressorLoads {

    @Test
    @DisplayName("heap pressure holds its 64 MiB while active and frees them on close")
    void heap() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(RealPathProbeMain.class, "heap-pressure", "-Xmx256m");

      assertThat(result.value("state")).isEqualTo("ACTIVE");
      assertThat(result.longValue("heldMiB")).isGreaterThanOrEqualTo(60L);
      assertThat(result.longValue("leftMiB")).isLessThan(16L);
    }

    @Test
    @DisplayName("metaspace pressure loads its classes into the metaspace; they unload once closed")
    void metaspace() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(RealPathProbeMain.class, "metaspace-pressure");

      assertThat(result.longValue("loadedClasses")).isGreaterThanOrEqualTo(2_000L);
      assertThat(result.longValue("metaspaceGrowthKiB")).isPositive();
      assertThat(result.longValue("unloadedClasses")).isPositive();
    }

    @Test
    @DisplayName("code-cache pressure fills the code cache through the JIT")
    void codeCache() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(RealPathProbeMain.class, "code-cache-pressure");

      assertThat(result.longValue("codeCacheGrowthKiB")).isPositive();
      assertThat(result.longValue("compilationMillis")).isPositive();
    }

    @Test
    @DisplayName("string-intern pressure adds its 10,000 strings to the JVM's string table")
    void stringIntern() throws Exception {
      final String[] statistics = {
        "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintStringTableStatistics"
      };
      final ChildJvmProbe.ProbeResult with =
          probe.runWithoutPlan(RealPathProbeMain.class, "string-intern-pressure", statistics);
      final ChildJvmProbe.ProbeResult without =
          probe.runWithoutPlan(RealPathProbeMain.class, "exit-now", statistics);

      assertThat(with.value("state")).isEqualTo("ACTIVE");
      assertThat(literals(with) - literals(without)).isGreaterThanOrEqualTo(10_000L);
    }

    /** The "Number of literals" of the string table the JVM printed at exit. */
    private long literals(final ChildJvmProbe.ProbeResult result) {
      final Matcher stringTable =
          Pattern.compile("StringTable statistics:[\\s\\S]*?Number of literals\\s*:\\s*(\\d+)")
              .matcher(result.output());
      assertThat(stringTable.find())
          .as("string table statistics in:\n%s", result.output())
          .isTrue();
      return Long.parseLong(stringTable.group(1));
    }

    @Test
    @DisplayName(
        "direct-buffer pressure that runs out of direct memory at start frees what it took")
    void directBufferOutOfMemoryAtStart() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutPlan(
              RealPathProbeMain.class, "direct-buffer-oom", "-XX:MaxDirectMemorySize=16m");

      assertThat(result.value("outcome")).isEqualTo("refused");
      assertThat(result.value("message")).contains("could not get its resources");
      assertThat(result.longValue("directLeftMiB")).isZero();
      assertThat(result.value("registered")).isEqualTo("false");
    }
  }

  @Nested
  @DisplayName("A non-daemon stressor of a startup plan")
  class NonDaemonStressors {

    private static final Duration HOLD = Duration.ofSeconds(2);

    private long runMillis(final ChaosScenario scenario) throws Exception {
      final long start = System.nanoTime();
      final ChildJvmProbe.ProbeResult result =
          probe.run(
              new ChaosPlan(new ChaosPlan.Metadata("keeps-alive", ""), null, List.of(scenario)),
              RealPathProbeMain.class,
              "exit-now",
              null);
      assertThat(result.value("mainReturned")).isEqualTo("true");
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Test
    @DisplayName("a keep-alive thread with daemon=false keeps the JVM running until it ends")
    void keepAlive() throws Exception {
      final long millis =
          runMillis(
              ChaosScenario.builder("keep-alive")
                  .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
                  .effect(ChaosEffect.keepAlive("probe-keepalive", false, Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.builder().activeFor(HOLD).build())
                  .build());

      assertThat(millis).isGreaterThanOrEqualTo(HOLD.toMillis()).isLessThan(30_000L);
    }

    @Test
    @DisplayName("a leaked non-daemon thread keeps the JVM running until its lifespan ends")
    void threadLeak() throws Exception {
      final long millis =
          runMillis(
              ChaosScenario.builder("leak")
                  .selector(ChaosSelector.stress(ChaosSelector.StressTarget.THREAD_LEAK))
                  .effect(new ChaosEffect.ThreadLeakEffect(1, "probe-leak-", false, HOLD))
                  .activationPolicy(ActivationPolicy.withDestructiveEffects())
                  .build());

      assertThat(millis).isGreaterThanOrEqualTo(HOLD.toMillis()).isLessThan(30_000L);
    }
  }

  @Nested
  @DisplayName("Startup plan sources")
  class StartupSources {

    private static final Duration DELAY = Duration.ofMillis(200);

    private static String planJson(final String id) {
      return ChaosPlanMapper.write(
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-" + id, ""),
              null,
              List.of(
                  ChaosScenario.builder(id)
                      .selector(
                          ChaosSelector.thread(
                              Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY))
                      .effect(ChaosEffect.delay(DELAY))
                      .activationPolicy(ActivationPolicy.always())
                      .build())));
    }

    private Path planFile(final String id) throws Exception {
      return Files.writeString(tempDir.resolve(id + ".json"), planJson(id));
    }

    /**
     * Runs the probe with {@code -javaagent} and no agent arguments, the plan coming from {@code
     * environment} and {@code jvmArgs} alone.
     */
    private ChildJvmProbe.ProbeResult run(
        final Map<String, String> environment, final List<String> jvmArgs, final String mode)
        throws Exception {
      final List<String> command = new ArrayList<>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.addAll(ChildJvmProbe.coverageAgent());
      command.add("-javaagent:" + System.getProperty("chaos.bootstrap.agentJar"));
      command.addAll(jvmArgs);
      command.add("-cp");
      command.add(System.getProperty("java.class.path"));
      command.add(RealPathProbeMain.class.getName());
      command.add(mode);
      final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      builder.environment().putAll(environment);
      final File log = Files.createTempFile(tempDir, "startup-source-", ".log").toFile();
      final Process process = builder.redirectOutput(log).start();
      try {
        final boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        final List<String> lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
        final String output = String.join("\n", lines);
        assertThat(finished).as("child must finish, output:\n%s", output).isTrue();
        assertThat(process.exitValue()).as("exit value, output:\n%s", output).isZero();
        return new ChildJvmProbe.ProbeResult(output, ChildJvmProbe.parse(lines));
      } finally {
        if (process.isAlive()) {
          ChildJvmProbe.stop(process);
        }
      }
    }

    private static void assertDelayed(final ChildJvmProbe.ProbeResult result) {
      assertThat(result.value("state")).isEqualTo("ACTIVE");
      assertThat(result.longValue("sleepMillis")).isGreaterThanOrEqualTo(DELAY.toMillis());
      assertThat(result.longValue("applied")).isEqualTo(1L);
    }

    @Test
    @DisplayName("MACSTAB_CHAOS_CONFIG_FILE names the plan")
    void environmentFile() throws Exception {
      assertDelayed(
          run(
              Map.of("MACSTAB_CHAOS_CONFIG_FILE", planFile("env-file").toString()),
              List.of(),
              "timed-sleep:env-file"));
    }

    @Test
    @DisplayName("MACSTAB_CHAOS_CONFIG_BASE64 carries the plan")
    void environmentBase64() throws Exception {
      final String encoded =
          Base64.getEncoder()
              .encodeToString(planJson("env-base64").getBytes(StandardCharsets.UTF_8));

      assertDelayed(
          run(Map.of("MACSTAB_CHAOS_CONFIG_BASE64", encoded), List.of(), "timed-sleep:env-base64"));
    }

    @Test
    @DisplayName("-Dmacstab.chaos.configJson carries the plan")
    void systemPropertyJson() throws Exception {
      assertDelayed(
          run(
              Map.of(),
              List.of("-Dmacstab.chaos.configJson=" + planJson("property-json")),
              "timed-sleep:property-json"));
    }

    @Test
    @DisplayName("-Dmacstab.chaos.configFile wins over MACSTAB_CHAOS_CONFIG_FILE")
    void systemPropertyBeforeEnvironment() throws Exception {
      final Path fromEnvironment = planFile("from-env");
      final Path fromProperty = planFile("from-property");

      final ChildJvmProbe.ProbeResult winner =
          run(
              Map.of("MACSTAB_CHAOS_CONFIG_FILE", fromEnvironment.toString()),
              List.of("-Dmacstab.chaos.configFile=" + fromProperty),
              "timed-sleep:from-property");
      final ChildJvmProbe.ProbeResult loser =
          run(
              Map.of("MACSTAB_CHAOS_CONFIG_FILE", fromEnvironment.toString()),
              List.of("-Dmacstab.chaos.configFile=" + fromProperty),
              "timed-sleep:from-env");

      assertDelayed(winner);
      assertThat(loser.value("state")).isEqualTo("ABSENT");
    }

    @Test
    @DisplayName("a reload that removes a scenario stops its effect on woven calls")
    void reloadStopsTheRemovedScenario() throws Exception {
      final Path plan = planFile("reloaded-away");

      final ChildJvmProbe.ProbeResult result =
          run(
              Map.of(
                  "MACSTAB_CHAOS_CONFIG_FILE",
                  plan.toString(),
                  "MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL",
                  "50"),
              List.of(),
              "reload:" + plan + ":reloaded-away");

      assertThat(result.longValue("beforeMillis")).isGreaterThanOrEqualTo(DELAY.toMillis());
      assertThat(result.value("removedState")).isIn("STOPPED", "ABSENT");
      assertThat(result.value("unrelatedState")).isEqualTo("ACTIVE");
      assertThat(result.longValue("afterMillis")).isLessThan(100L);
    }
  }
}

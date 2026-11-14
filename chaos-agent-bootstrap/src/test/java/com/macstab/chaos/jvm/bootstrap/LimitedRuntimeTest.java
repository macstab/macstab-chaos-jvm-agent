package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent on a runtime without {@code java.sql} or {@code java.management} (a jlink image, {@code
 * --limit-modules}): the hooks and the MBean that need such a module must not take the JVM down at
 * start.
 */
@DisplayName("A runtime without optional modules")
class LimitedRuntimeTest {

  /** What the agent needs, without java.sql. */
  private static final String MODULES =
      "java.base,java.instrument,java.logging,java.management,jdk.unsupported,java.net.http,"
          + "java.naming,jdk.attach";

  /** The fewest modules the agent starts with: no java.management either. */
  private static final String MINIMAL_MODULES = "java.base,java.instrument,java.logging";

  @TempDir Path tempDir;

  @Test
  @DisplayName("starts without java.management, its diagnostics MBean left out")
  void startWithoutJavaManagement() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlan(
                LimitedRuntimeProbeMain.class, "minimal", "--limit-modules", MINIMAL_MODULES);

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("javaSql")).isEqualTo("false");
          softly
              .assertThat(result.value("mbeanSkipped"))
              .contains("MBean registration skipped: the diagnostics MBean could not be linked")
              .contains("java.management in the runtime: false");
          softly.assertThat(result.value("done")).isEqualTo("true");
        });
  }

  @Test
  @DisplayName("starts eager without java.management: the JMX hooks fail, not the JVM")
  void eagerStartWithoutJavaManagement() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlan(
                LimitedRuntimeProbeMain.class,
                "minimal-eager",
                "--limit-modules",
                MINIMAL_MODULES,
                "-Dmacstab.chaos.instrumentation=eager");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("done")).isEqualTo("true");
          softly.assertThat(result.value("hookFailures")).contains("JNDI_JMX");
        });
  }

  @Test
  @DisplayName("activates a code-cache stressor without java.management")
  void codeCachePressureWithoutJavaManagement() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlan(
                LimitedRuntimeProbeMain.class,
                "minimal-code-cache",
                "--limit-modules",
                MINIMAL_MODULES);

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("codeCache")).isEqualTo("activated:ACTIVE");
          softly.assertThat(result.value("failures")).isEqualTo("[]");
          softly.assertThat(result.value("done")).isEqualTo("true");
        });
  }

  @Test
  @DisplayName("starts with every hook group woven (instrumentation=eager)")
  void eagerStartWithoutJavaSql() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlan(
                LimitedRuntimeProbeMain.class,
                "eager",
                "--limit-modules",
                MODULES,
                "-Dmacstab.chaos.instrumentation=eager");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("javaSql")).isEqualTo("false");
          softly.assertThat(result.value("done")).isEqualTo("true");
        });
  }

  @Test
  @DisplayName("starts with a JDBC scenario in its startup plan")
  void jdbcStartupScenarioWithoutJavaSql() throws Exception {
    final ChaosPlan plan =
        new ChaosPlan(
            new ChaosPlan.Metadata("limited", ""),
            null,
            List.of(
                ChaosScenario.builder("jdbc")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build()));

    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .run(plan, LimitedRuntimeProbeMain.class, "plan", null, "--limit-modules", MODULES);

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("javaSql")).isEqualTo("false");
          softly.assertThat(result.value("done")).isEqualTo("true");
          softly.assertThat(result.value("failures")).isEqualTo("[]");
        });
  }
}

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
 * The agent on a runtime without {@code java.sql} (a jlink image, {@code --limit-modules}): the
 * JDBC hooks, which only need that module, must not take the JVM down at start.
 */
@DisplayName("A runtime without java.sql")
class LimitedRuntimeTest {

  /** What the agent needs, without java.sql. */
  private static final String MODULES =
      "java.base,java.instrument,java.logging,java.management,jdk.unsupported,java.net.http,"
          + "java.naming,jdk.attach";

  @TempDir Path tempDir;

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

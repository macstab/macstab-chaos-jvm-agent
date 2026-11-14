package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JDBC statements a driver or a pool issues for itself (validation, set-up, the rollback of a
 * connection taken back) are not the application's: a JDBC scenario neither fails nor counts them.
 * Real HikariCP over H2 in a child JVM.
 */
@DisplayName("JDBC traffic of the pool and the driver")
class JdbcInternalEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe.ProbeResult run(final String mode) throws Exception {
    return new ChildJvmProbe(tempDir)
        .runWithoutPlanOnClasspath(
            JdbcInternalProbeMain.class, mode, System.getProperty("chaos.test.jdbcClasspath"));
  }

  @Test
  @DisplayName("a driver's isValid statement is not a target: the borrow validates, the app fails")
  void driverValidationIsNotATarget() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("driver-validation");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("isValid")).isEqualTo("true");
          softly.assertThat(result.longValue("borrowMillis")).isLessThan(1_800L);
          softly.assertThat(result.value("app.type")).isEqualTo("java.sql.SQLException");
          softly.assertThat(result.longValue("applied")).isEqualTo(1L);
        });
  }

  @Test
  @DisplayName("the pool's test query, init SQL and dirty-connection rollback are not targets")
  void poolQueriesAreNotTargets() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("pool-queries");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("init")).isEqualTo("ok");
          softly.assertThat(result.longValue("borrowMillis")).isLessThan(1_800L);
          softly.assertThat(result.value("app.type")).isEqualTo("java.sql.SQLException");
          softly.assertThat(result.longValue("applied")).isEqualTo(1L);
          softly.assertThat(result.value("rollback.close")).isEqualTo("ok");
          softly.assertThat(result.longValue("rollback.applied")).isZero();
          // The scenario does reach a rollback: the application's own.
          softly.assertThat(result.value("rollback.app")).isEqualTo("java.sql.SQLException");
          softly.assertThat(result.longValue("rollback.appApplied")).isEqualTo(1L);
        });
  }
}

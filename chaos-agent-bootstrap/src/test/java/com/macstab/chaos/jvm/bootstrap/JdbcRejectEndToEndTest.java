package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A JDBC reject of prepare, commit and rollback on a real H2 connection, in a child JVM. */
@DisplayName("JDBC rejects of prepare, commit and rollback")
class JdbcRejectEndToEndTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("fail with SQLException on a plain and a pooled connection; the pool stays usable")
  void prepareCommitRollbackRejects() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlanOnClasspath(
                JdbcRejectProbeMain.class,
                "rejects",
                System.getProperty("chaos.test.jdbcClasspath"));

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("plain.prepare.type")).isEqualTo("java.sql.SQLException");
          softly.assertThat(result.value("plain.prepare.message")).isEqualTo("chaos");
          softly.assertThat(result.value("plain.commit.type")).isEqualTo("java.sql.SQLException");
          softly.assertThat(result.value("plain.commit.message")).isEqualTo("chaos");
          softly.assertThat(result.value("plain.rollback.type")).isEqualTo("java.sql.SQLException");
          softly.assertThat(result.value("plain.rollback.message")).isEqualTo("chaos");
          softly.assertThat(result.value("pooled.prepare.type")).isEqualTo("java.sql.SQLException");
          softly.assertThat(result.value("pooled.prepare.message")).isEqualTo("chaos");
          softly.assertThat(result.value("pooled.commit.type")).isEqualTo("java.sql.SQLException");
          softly.assertThat(result.value("pooled.commit.message")).isEqualTo("chaos");
          softly
              .assertThat(result.value("pooled.rollback.type"))
              .isEqualTo("java.sql.SQLException");
          softly.assertThat(result.value("pooled.rollback.message")).isEqualTo("chaos");
          softly.assertThat(result.value("pooled.borrowAfter")).isEqualTo("ok");
          // The rejected rollback rolled nothing back: the uncommitted row is still there.
          softly.assertThat(result.value("plain.rowsAfterRollback")).isEqualTo("1");
          softly.assertThat(result.value("pooled.rowsAfterRollback")).isEqualTo("1");
        });
  }
}

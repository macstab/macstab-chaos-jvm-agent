package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Method hooks on application classes, woven in a child JVM. */
@DisplayName("Method hooks on application classes")
class MethodHookEndToEndTest {

  @TempDir Path tempDir;

  private ChildJvmProbe.ProbeResult run(final String mode) throws Exception {
    return new ChildJvmProbe(tempDir).runWithoutPlan(MethodHookProbeMain.class, mode);
  }

  @Test
  @DisplayName("bridge and lambda methods are not woven: one call is one evaluation")
  void bridgeAndSyntheticMethodsAreSkipped() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("bridge");

    assertSoftly(
        softly -> {
          softly.assertThat(result.longValue("compare")).isEqualTo(-1L);
          softly.assertThat(result.value("label")).isEqualTo("rank 1");
          softly.assertThat(result.longValue("matched")).isEqualTo(2L);
          softly.assertThat(result.longValue("applied")).isEqualTo(2L);
        });
  }

  @Test
  @DisplayName("return values are corrupted for each return type and strategy")
  void returnValuesAreCorrupted() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("returns");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("big")).isEqualTo(Long.toString(Long.MAX_VALUE));
          softly.assertThat(result.value("flag")).isEqualTo("false");
          softly.assertThat(result.value("ratio")).isEqualTo(Double.toString(-Double.MAX_VALUE));
          softly.assertThat(result.value("text")).isEqualTo("null");
          softly.assertThat(result.value("list")).isEqualTo("[]");
          softly.assertThat(result.value("maybe")).isEqualTo("Optional.empty");
          softly.assertThat(result.value("array")).isEqualTo("[]");
          softly.assertThat(result.value("boxed")).isEqualTo("0");
          softly.assertThat(result.longValue("applied")).isEqualTo(8L);
        });
  }

  @Test
  @DisplayName("checked JDK exceptions are injected as configured, with or without a stack trace")
  void checkedExceptionsAreInjected() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("exceptions");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("io.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("io.message")).isEqualTo("disk gone");
          softly.assertThat(result.longValue("io.stackDepth")).isZero();
          softly.assertThat(result.longValue("io.applied")).isEqualTo(1L);
          softly
              .assertThat(result.value("timeout.type"))
              .isEqualTo("java.util.concurrent.TimeoutException");
          softly.assertThat(result.value("timeout.message")).isEqualTo("too slow");
          softly.assertThat(result.longValue("timeout.stackDepth")).isPositive();
          softly.assertThat(result.longValue("timeout.applied")).isEqualTo(1L);
        });
  }

  @Test
  @DisplayName("METHOD_ENTER and METHOD_EXIT delays hold the call for their duration")
  void delaysAreTimed() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("delays");

    assertSoftly(
        softly -> {
          softly.assertThat(result.longValue("enter.millis")).isBetween(195L, 5_000L);
          softly.assertThat(result.longValue("exit.millis")).isBetween(195L, 5_000L);
          softly.assertThat(result.value("results")).isEqualTo("hello a|42");
          softly.assertThat(result.longValue("enter.applied")).isEqualTo(1L);
          softly.assertThat(result.longValue("exit.applied")).isEqualTo(1L);
        });
  }

  @Test
  @DisplayName("an interface's default and static methods are woven")
  void interfaceDefaultAndStaticMethods() throws Exception {
    final ChildJvmProbe.ProbeResult result = run("interface");

    assertSoftly(
        softly -> {
          softly.assertThat(result.longValue("static")).isZero();
          softly.assertThat(result.longValue("default")).isZero();
          // base() directly, price(), and base() inside price().
          softly.assertThat(result.longValue("matched")).isEqualTo(3L);
          softly.assertThat(result.longValue("applied")).isEqualTo(3L);
        });
  }
}

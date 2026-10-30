package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Chaos MBean")
class ChaosDiagnosticsMBeanTest {

  private static final String PLAN =
      """
      {"metadata":{"name":"jmx"},"scenarios":[{"id":"jmx-delay","scope":"JVM",
       "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
       "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
      """;

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("control operations are refused unless enabled")
  void controlDisabled() {
    final ChaosDiagnosticsMBean bean =
        new ChaosDiagnosticsMBean(runtime.diagnostics(), runtime, false);

    assertThat(bean.isControlEnabled()).isFalse();
    assertThatThrownBy(() -> bean.activatePlan(PLAN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jmxControl=true");
    assertThatThrownBy(bean::stopAll).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("with control enabled a plan is activated, listed and stopped")
  void controlEnabled() {
    final ChaosDiagnosticsMBean bean =
        new ChaosDiagnosticsMBean(runtime.diagnostics(), runtime, true);

    assertThat(bean.activatePlan(PLAN)).isEqualTo("jmx-delay");
    assertThat(bean.listScenarios())
        .singleElement()
        .asString()
        .startsWith("jmx-delay state=ACTIVE matched=0 applied=0");

    assertThat(bean.stopScenario("jmx-delay")).isTrue();
    assertThat(bean.stopScenario("jmx-delay")).isFalse();
    assertThat(bean.activatePlan(PLAN)).isEqualTo("jmx-delay");
    assertThat(bean.stopAll()).isEqualTo(1);
  }

  @Test
  @DisplayName("an invalid plan is an IllegalArgumentException with the parse message")
  void invalidPlan() {
    final ChaosDiagnosticsMBean bean =
        new ChaosDiagnosticsMBean(runtime.diagnostics(), runtime, true);

    assertThatThrownBy(() -> bean.activatePlan("{"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed JSON");
  }

  @Test
  @DisplayName("jmxControl is read from the agent args, then the property and environment")
  void controlSetting() {
    assertThat(ChaosAgentBootstrap.jmxControl("jmxControl=true", Map.of())).isTrue();
    assertThat(ChaosAgentBootstrap.jmxControl("", Map.of("MACSTAB_CHAOS_JMX_CONTROL", "true")))
        .isTrue();
    assertThat(ChaosAgentBootstrap.jmxControl("", Map.of())).isFalse();
  }
}

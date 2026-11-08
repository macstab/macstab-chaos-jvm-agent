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
    final ChaosDiagnosticsMBean bean = new ChaosDiagnosticsMBean(runtime, false);

    assertThat(bean.isControlEnabled()).isFalse();
    assertThatThrownBy(() -> bean.activatePlan(PLAN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jmxControl=true");
    assertThatThrownBy(bean::stopAll).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("with control enabled a plan is activated, listed and stopped")
  void controlEnabled() {
    final ChaosDiagnosticsMBean bean = new ChaosDiagnosticsMBean(runtime, true);

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
    final ChaosDiagnosticsMBean bean = new ChaosDiagnosticsMBean(runtime, true);

    assertThatThrownBy(() -> bean.activatePlan("{"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed JSON");
  }

  @Test
  @DisplayName("a read-only bean, or one without a control plane, has control disabled")
  void readOnlyBean() {
    runtime.activate(com.macstab.chaos.jvm.startup.ChaosPlanMapper.read(PLAN));
    final ChaosDiagnosticsMBean readOnly = new ChaosDiagnosticsMBean(runtime, false);

    assertThat(readOnly.isControlEnabled()).isFalse();
    assertThat(readOnly.listScenarios()).singleElement().asString().startsWith("jmx-delay ");
    assertThat(readOnly.debugDump()).contains("jmx-delay");
  }

  @Test
  @DisplayName("a plan the runtime refuses is an IllegalArgumentException with its reason")
  void refusedPlan() {
    final ChaosDiagnosticsMBean bean = new ChaosDiagnosticsMBean(runtime, true);
    bean.activatePlan(PLAN);

    assertThatThrownBy(() -> bean.activatePlan(PLAN.replace("\"jmx\"}", "\"jmx-again\"}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already active");
  }

  @Test
  @DisplayName("a scenario stops with its whole plan, so stopAll counts each plan once")
  void scenariosStopWithTheirPlan() {
    final ChaosDiagnosticsMBean bean = new ChaosDiagnosticsMBean(runtime, true);
    bean.activatePlan(
        """
        {"metadata":{"name":"pair"},"scenarios":[
          {"id":"pair-a","scope":"JVM","selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
           "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}},
          {"id":"pair-b","scope":"JVM","selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
           "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
        """);
    bean.activatePlan(PLAN);

    assertThat(bean.stopScenario("pair-a")).isTrue();
    assertThat(bean.stopScenario("pair-b")).as("stopped with pair-a").isFalse();
    assertThat(bean.listScenarios()).noneMatch(line -> line.startsWith("pair-"));
    assertThat(bean.listScenarios()).anyMatch(line -> line.startsWith("jmx-delay "));

    bean.activatePlan(PLAN.replace("jmx", "two"));
    bean.activatePlan(
        """
        {"metadata":{"name":"trio"},"scenarios":[
          {"id":"trio-a","scope":"JVM","selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
           "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}},
          {"id":"trio-b","scope":"JVM","selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
           "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
        """);
    assertThat(bean.stopAll()).as("jmx, two and trio: three plans").isEqualTo(3);
    assertThat(bean.listScenarios()).isEmpty();
  }

  @Test
  @DisplayName("a plan that finished on its own is pruned: stopAll does not count it")
  void finishedPlansArePruned() throws Throwable {
    final ChaosDiagnosticsMBean bean = new ChaosDiagnosticsMBean(runtime, true);
    bean.activatePlan(
        """
        {"metadata":{"name":"once"},"scenarios":[{"id":"gc-once","scope":"JVM",
         "selector":{"type":"jvmRuntime","operations":["SYSTEM_GC_REQUEST"]},
         "effect":{"type":"suppress"},"activationPolicy":{"maxApplications":1}}]}
        """);
    assertThat(runtime.beforeGcRequest()).as("the one application").isTrue();

    assertThat(bean.stopAll()).as("the exhausted plan is not running").isZero();
    assertThat(bean.listScenarios()).as("its controller left the registry").isEmpty();
  }

  @Test
  @DisplayName("a finished plan of several scenarios is closed once; running plans are kept")
  void finishedPlanOfSeveralScenariosIsClosedOnce() throws Throwable {
    final ChaosDiagnosticsMBean bean = new ChaosDiagnosticsMBean(runtime, true);
    bean.activatePlan(
        """
        {"metadata":{"name":"twice"},"scenarios":[
          {"id":"gc-a","scope":"JVM","selector":{"type":"jvmRuntime","operations":["SYSTEM_GC_REQUEST"]},
           "effect":{"type":"suppress"},"activationPolicy":{"maxApplications":1}},
          {"id":"gc-b","scope":"JVM","selector":{"type":"jvmRuntime","operations":["SYSTEM_GC_REQUEST"]},
           "effect":{"type":"suppress"},"activationPolicy":{"maxApplications":1}}]}
        """);
    bean.activatePlan(PLAN);
    // Each request applies at least one scenario that is still active: two spend both.
    runtime.beforeGcRequest();
    runtime.beforeGcRequest();

    assertThat(bean.stopScenario("gc-a")).as("pruned, not stopped").isFalse();
    assertThat(bean.listScenarios()).singleElement().asString().startsWith("jmx-delay ");
    assertThat(bean.stopAll()).as("only the running plan").isEqualTo(1);
  }

  @Test
  @DisplayName("jmxControl is read from the agent args, then the property and environment")
  void controlSetting() {
    assertThat(ChaosAgentBootstrap.jmxControl("jmxControl=true", Map.of())).isTrue();
    assertThat(ChaosAgentBootstrap.jmxControl("", Map.of("MACSTAB_CHAOS_JMX_CONTROL", "true")))
        .isTrue();
    assertThat(ChaosAgentBootstrap.jmxControl("", Map.of())).isFalse();
    // The standard boolean literals of every other switch.
    assertThat(ChaosAgentBootstrap.jmxControl("jmxControl=on", Map.of())).isTrue();
    assertThat(ChaosAgentBootstrap.jmxControl("", Map.of("MACSTAB_CHAOS_JMX_CONTROL", "1")))
        .isTrue();
    assertThat(ChaosAgentBootstrap.jmxControl("jmxControl=off", Map.of())).isFalse();
  }

  @Test
  @DisplayName("hides scenarios of a plan with jmxEnabled=false from listScenarios and debugDump")
  void hidesPlanScenarios() {
    final ChaosDiagnosticsMBean bean = new ChaosDiagnosticsMBean(runtime, true);
    bean.activatePlan(
        """
        {"metadata": {"name": "secret"},
         "observability": {"jmxEnabled": false, "structuredLoggingEnabled": true,
                           "debugDumpOnStart": false},
         "scenarios": [{"id": "jmx-hidden", "scope": "JVM",
           "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
           "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]}
        """);
    bean.activatePlan(
        """
        {"scenarios": [{"id": "jmx-shown", "scope": "JVM",
           "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
           "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]}
        """);

    assertThat(bean.listScenarios())
        .anyMatch(line -> line.startsWith("jmx-shown "))
        .noneMatch(line -> line.startsWith("jmx-hidden "));
    assertThat(bean.debugDump()).contains("jmx-shown state=").doesNotContain("jmx-hidden");
  }
}

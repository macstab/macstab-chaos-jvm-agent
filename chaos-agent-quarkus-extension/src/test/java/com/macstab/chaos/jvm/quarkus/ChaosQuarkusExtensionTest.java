package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Extension-level tests that exercise {@link ChaosQuarkusExtension} without spinning up a Quarkus
 * application; no {@code @QuarkusTest} runs in this build. The class-loader split of a Quarkus test
 * (the producer in Quarkus's loader, this extension in the system loader) is covered by {@link
 * ChaosArcProducerTestModeTest}.
 */
@ExtendWith(ChaosQuarkusExtension.class)
@DisplayName("ChaosQuarkusExtension")
@SuppressWarnings("removal")
class ChaosQuarkusExtensionTest {

  private static ChaosSession outerSession;

  @BeforeAll
  static void captureOuterSession(final ChaosSession session) {
    outerSession = session;
  }

  @Nested
  @DisplayName("nested classes")
  class NestedClasses {

    @Test
    @DisplayName("a nested class shares the outer class's session")
    void sharesTheOuterSession(final ChaosSession session) {
      assertThat(session).isSameAs(outerSession);
    }
  }

  @Nested
  @DisplayName("parameter injection")
  class ParameterInjection {

    @Test
    @DisplayName("injects ChaosSession as parameter")
    void injectsChaosSession(final ChaosSession session) {
      assertThat(session).isNotNull();
    }

    @Test
    @DisplayName("injects ChaosControlPlane as parameter")
    void injectsChaosControlPlane(final ChaosControlPlane controlPlane) {
      assertThat(controlPlane).isNotNull();
    }

    @Test
    @DisplayName("injects both ChaosSession and ChaosControlPlane simultaneously")
    void injectsBothParameters(final ChaosSession session, final ChaosControlPlane controlPlane) {
      assertThat(session).isNotNull();
      assertThat(controlPlane).isNotNull();
    }
  }

  @Nested
  @DisplayName("session lifecycle")
  class SessionLifecycle {

    @Test
    @DisplayName("session is open and usable during test execution")
    void sessionIsOpenDuringTest(final ChaosSession session) {
      // A closed session refuses activations; an open one runs them.
      try (ChaosActivationHandle handle =
          session.activate(
              com.macstab.chaos.jvm.api.ChaosScenario.builder("open-session")
                  .scope(com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope.SESSION)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .build())) {
        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      }
    }

    @Test
    @DisplayName("session id is non-null")
    void sessionIdIsNonNull(final ChaosSession session) {
      assertThat(session.id()).isNotNull();
    }
  }

  @Nested
  @DisplayName("annotation parsing")
  class AnnotationParsing {

    @ChaosScenario(id = "delay-jdbc", selector = "jdbc", effect = "delay:PT0.1S")
    void annotatedDelay() {}

    @ChaosScenario(id = "suppress-http", selector = "httpClient", effect = "suppress")
    void annotatedSuppress() {}

    @ChaosScenario(id = "freeze-clock", selector = "jvmRuntime", effect = "freeze")
    void annotatedFreeze() {}

    @Test
    @DisplayName("delay:PT0.1S maps to a 100 ms DelayEffect")
    void delayAnnotationMapsToDelayEffect() throws Exception {
      final ChaosScenario annotation =
          AnnotationParsing.class
              .getDeclaredMethod("annotatedDelay")
              .getAnnotation(ChaosScenario.class);
      final com.macstab.chaos.jvm.api.ChaosScenario scenario =
          ChaosQuarkusExtension.toScenario(annotation);
      assertThat(scenario.id()).isEqualTo("delay-jdbc");
      assertThat(scenario.selector()).isInstanceOf(ChaosSelector.JdbcSelector.class);
      assertThat(scenario.effect()).isInstanceOf(ChaosEffect.DelayEffect.class);
      final ChaosEffect.DelayEffect delay = (ChaosEffect.DelayEffect) scenario.effect();
      assertThat(delay.minDelay()).isEqualTo(Duration.ofMillis(100));
      assertThat(delay.maxDelay()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("suppress maps to a SuppressEffect and httpClient selector")
    void suppressAnnotationMapsToSuppressEffect() throws Exception {
      final ChaosScenario annotation =
          AnnotationParsing.class
              .getDeclaredMethod("annotatedSuppress")
              .getAnnotation(ChaosScenario.class);
      final com.macstab.chaos.jvm.api.ChaosScenario scenario =
          ChaosQuarkusExtension.toScenario(annotation);
      assertThat(scenario.selector()).isInstanceOf(ChaosSelector.HttpClientSelector.class);
      assertThat(scenario.effect()).isInstanceOf(ChaosEffect.SuppressEffect.class);
    }

    @Test
    @DisplayName("freeze maps to a ClockSkewEffect with FREEZE mode")
    void freezeAnnotationMapsToClockSkewEffect() throws Exception {
      final ChaosScenario annotation =
          AnnotationParsing.class
              .getDeclaredMethod("annotatedFreeze")
              .getAnnotation(ChaosScenario.class);
      final com.macstab.chaos.jvm.api.ChaosScenario scenario =
          ChaosQuarkusExtension.toScenario(annotation);
      assertThat(scenario.selector()).isInstanceOf(ChaosSelector.JvmRuntimeSelector.class);
      assertThat(scenario.effect()).isInstanceOf(ChaosEffect.ClockSkewEffect.class);
      final ChaosEffect.ClockSkewEffect skew = (ChaosEffect.ClockSkewEffect) scenario.effect();
      assertThat(skew.mode()).isEqualTo(ChaosEffect.ClockSkewMode.FREEZE);
      assertThat(skew.skewAmount()).as("frozen at the activation time, not ahead of it").isZero();
      assertThat(((ChaosSelector.JvmRuntimeSelector) scenario.selector()).operations())
          .as("the clock reads that are actually instrumented, not System.currentTimeMillis")
          .containsExactlyInAnyOrder(
              OperationType.INSTANT_NOW,
              OperationType.LOCAL_DATE_TIME_NOW,
              OperationType.ZONED_DATE_TIME_NOW,
              OperationType.DATE_NEW);
    }

    @ChaosScenario(id = "delay-clock", selector = "jvmRuntime", effect = "delay:PT0.1S")
    void annotatedClockDelay() {}

    @ChaosScenario(id = "freeze-jdbc", selector = "jdbc", effect = "freeze")
    void annotatedJdbcFreeze() {}

    @Test
    @DisplayName("a delay on jvmRuntime is rejected because clock reads only take a skew")
    void clockDelayIsRejected() throws Exception {
      final ChaosScenario annotation =
          AnnotationParsing.class
              .getDeclaredMethod("annotatedClockDelay")
              .getAnnotation(ChaosScenario.class);
      assertThatThrownBy(() -> ChaosQuarkusExtension.toScenario(annotation))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("freeze");
    }

    @Test
    @DisplayName("freeze on a non-clock selector is rejected")
    void freezeOnNonClockSelectorIsRejected() throws Exception {
      final ChaosScenario annotation =
          AnnotationParsing.class
              .getDeclaredMethod("annotatedJdbcFreeze")
              .getAnnotation(ChaosScenario.class);
      assertThatThrownBy(() -> ChaosQuarkusExtension.toScenario(annotation))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("jvmRuntime");
    }
  }

  @Nested
  @DisplayName("method-level @ChaosScenario activation")
  class MethodLevelActivation {

    @Test
    @ChaosScenario(id = "method-delay", selector = "jdbc", effect = "delay:PT0.05S")
    @DisplayName("method-level annotation activates a scenario on the class session")
    void methodAnnotationActivatesScenario(
        final ChaosSession session, final ChaosControlPlane controlPlane) {
      assertThat(session).isNotNull();
      assertThat(controlPlane).isNotNull();
      assertThat(controlPlane.diagnostics().scenario("method-delay"))
          .as(
              "method-delay scenario is visible in diagnostics after beforeTestExecution activation")
          .isPresent();
    }
  }
}

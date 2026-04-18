package com.macstab.chaos.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.ChaosSession;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Extension-level tests that exercise {@link ChaosQuarkusExtension} without spinning up a Quarkus
 * context. A full {@code @QuarkusTest} integration lives in the downstream example module.
 */
@ExtendWith(ChaosQuarkusExtension.class)
@DisplayName("ChaosQuarkusExtension")
class ChaosQuarkusExtensionTest {

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
      assertThatCode(() -> session.bind().close()).doesNotThrowAnyException();
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
      final com.macstab.chaos.api.ChaosScenario scenario =
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
      final com.macstab.chaos.api.ChaosScenario scenario =
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
      final com.macstab.chaos.api.ChaosScenario scenario =
          ChaosQuarkusExtension.toScenario(annotation);
      assertThat(scenario.selector()).isInstanceOf(ChaosSelector.JvmRuntimeSelector.class);
      assertThat(scenario.effect()).isInstanceOf(ChaosEffect.ClockSkewEffect.class);
      final ChaosEffect.ClockSkewEffect skew = (ChaosEffect.ClockSkewEffect) scenario.effect();
      assertThat(skew.mode()).isEqualTo(ChaosEffect.ClockSkewMode.FREEZE);
    }
  }

  @Nested
  @DisplayName("method-level @ChaosScenario activation")
  @ExtendWith(ChaosQuarkusExtension.class)
  class MethodLevelActivation {

    @Test
    @ChaosScenario(id = "method-delay", selector = "jdbc", effect = "delay:PT0.05S")
    @DisplayName("method-level annotation activates a scenario on the class session")
    void methodAnnotationActivatesScenario(
        final ChaosSession session, final ChaosControlPlane controlPlane) {
      assertThat(session).isNotNull();
      assertThat(controlPlane).isNotNull();
      assertThat(controlPlane.diagnostics().scenario("method-delay"))
          .as("method-delay scenario is visible in diagnostics after beforeEach activation")
          .isPresent();
    }
  }
}

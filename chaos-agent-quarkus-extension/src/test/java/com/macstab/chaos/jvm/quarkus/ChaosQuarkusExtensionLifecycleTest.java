package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The deprecated {@code @ChaosScenario} path of the extension until its removal: method-level
 * JVM-scoped scenarios end with their method, and every selector and effect identifier maps to its
 * scenario or is rejected with a message naming the valid ones.
 */
@SuppressWarnings("removal")
@DisplayName("ChaosQuarkusExtension lifecycle and parsing")
class ChaosQuarkusExtensionLifecycleTest {

  @Nested
  @ExtendWith(ChaosQuarkusExtension.class)
  @DisplayName("method-level JVM scenarios")
  class MethodScoped {

    // Both methods declare the same JVM-scoped id: the second could only activate it if the
    // first method's activation was stopped when that method ended.
    @Test
    @ChaosScenario(id = "method-scoped", selector = "executor", effect = "delay:PT0.001S")
    @DisplayName("first activation of the id")
    void first(final ChaosControlPlane controlPlane) {
      assertThat(controlPlane.diagnostics().scenario("method-scoped")).isPresent();
    }

    @Test
    @ChaosScenario(id = "method-scoped", selector = "executor", effect = "delay:PT0.001S")
    @DisplayName("second activation of the same id")
    void second(final ChaosControlPlane controlPlane) {
      assertThat(controlPlane.diagnostics().scenario("method-scoped")).isPresent();
    }
  }

  @Nested
  @ExtendWith(ChaosQuarkusExtension.class)
  @DisplayName("a method-level session-scoped scenario ends with the method")
  class MethodScopedSession {

    // Both methods declare the same session-scoped id in the class-long session: the second could
    // only activate it if the first method's activation was stopped when that method ended.
    @Test
    @ChaosScenario(
        id = "method-scoped-session",
        selector = "executor",
        effect = "delay:PT0.001S",
        scope = "SESSION")
    @DisplayName("first activation of the id")
    void first(final ChaosControlPlane controlPlane) {
      assertThat(controlPlane.diagnostics().scenario("method-scoped-session")).isPresent();
    }

    @Test
    @ChaosScenario(
        id = "method-scoped-session",
        selector = "executor",
        effect = "delay:PT0.001S",
        scope = "SESSION")
    @DisplayName("second activation of the same id")
    void second(final ChaosControlPlane controlPlane) {
      assertThat(controlPlane.diagnostics().scenario("method-scoped-session")).isPresent();
    }
  }

  @Nested
  @DisplayName("identifier parsing")
  class Parsing {

    @ChaosScenario(id = "e", selector = "executor", effect = "delay:PT0.002S")
    void executor() {}

    @ChaosScenario(id = "h", selector = "httpClient", effect = "delay:PT1S", scope = "SESSION")
    void http() {}

    @ChaosScenario(id = "j", selector = "jdbc", effect = "delay:PT1S")
    void jdbc() {}

    @ChaosScenario(id = "x", selector = " kafka ", effect = "suppress")
    void unknownSelector() {}

    @ChaosScenario(id = "y", selector = "executor", effect = "explode")
    void unknownEffect() {}

    @ChaosScenario(id = "z", selector = "executor", effect = "delay:soon")
    void badDuration() {}

    @Test
    @DisplayName("each selector identifier maps to its selector and operations")
    void selectors() throws Exception {
      assertThat(parse("executor").selector())
          .isEqualTo(
              ChaosSelector.executor(
                  java.util.EnumSet.of(
                      OperationType.EXECUTOR_SUBMIT, OperationType.EXECUTOR_WORKER_RUN)));
      assertThat(parse("http").selector()).isInstanceOf(ChaosSelector.HttpClientSelector.class);
      assertThat(parse("http").scope())
          .isEqualTo(com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope.SESSION);
      assertThat(parse("jdbc").selector()).isEqualTo(ChaosSelector.jdbc());
      assertThat(parse("jdbc").effect())
          .isEqualTo(ChaosEffect.delay(java.time.Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("unknown identifiers and unparseable durations name what is accepted")
    void rejections() {
      assertThatThrownBy(() -> parse("unknownSelector"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expected one of executor, jvmRuntime, httpClient, jdbc");
      assertThatThrownBy(() -> parse("unknownEffect"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expected delay:<duration>, suppress, or freeze");
      assertThatThrownBy(() -> parse("badDuration"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ISO-8601");
    }

    private com.macstab.chaos.jvm.api.ChaosScenario parse(final String method) throws Exception {
      final Method annotated = Parsing.class.getDeclaredMethod(method);
      return ChaosQuarkusExtension.toScenario(annotated.getAnnotation(ChaosScenario.class));
    }
  }
}

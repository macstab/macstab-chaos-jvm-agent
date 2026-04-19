package com.macstab.chaos.quarkus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative chaos scenario activation for Quarkus tests.
 *
 * <p>When placed on a test class or method, the {@link ChaosQuarkusExtension} reads the annotation
 * metadata, builds a matching {@link com.macstab.chaos.api.ChaosScenario}, and activates it on the
 * class-scoped {@link com.macstab.chaos.api.ChaosSession} opened for the test.
 *
 * <p>Multiple {@code @ChaosScenario} annotations may be placed on the same element — each is
 * activated independently. Test-method annotations take precedence for parity with the usual JUnit
 * annotation-override rules.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @QuarkusChaosTest
 * @ChaosScenario(id = "slow-jdbc", selector = "jdbc", effect = "delay:PT0.1S")
 * class OrderServiceTest {
 *
 *   @Test
 *   @ChaosScenario(id = "reject-http", selector = "httpClient", effect = "suppress")
 *   void placesOrder() {
 *     // both the class-level slow-jdbc scenario and the method-level reject-http scenario
 *     // are active while this test runs.
 *   }
 * }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ChaosScenarios.class)
public @interface ChaosScenario {

  /**
   * Unique identifier for this scenario; used in diagnostics and JFR events. Must be non-blank.
   *
   * @return the scenario identifier
   */
  String id();

  /**
   * Selector identifier, one of {@code "executor"}, {@code "jvmRuntime"}, {@code "httpClient"}, or
   * {@code "jdbc"}. The runtime validator enforces compatibility with {@link #effect()} at
   * activation time.
   *
   * @return the selector identifier
   */
  String selector();

  /**
   * Effect identifier.
   *
   * <p>Supported values:
   *
   * <ul>
   *   <li>{@code "delay:<iso-duration>"} — delay for the given ISO-8601 duration (e.g., {@code
   *       "delay:PT0.1S"} for 100 ms).
   *   <li>{@code "suppress"} — silently discard the matched operation.
   *   <li>{@code "freeze"} — shorthand for a clock-skew freeze effect (paired with {@code
   *       "jvmRuntime"}).
   * </ul>
   *
   * @return the effect identifier
   */
  String effect();

  /**
   * Scope of the scenario, matching {@link com.macstab.chaos.api.ChaosScenario.ScenarioScope}.
   * Defaults to {@code "JVM"} to match the task specification; the extension automatically falls
   * back to {@code SESSION} when the declared scope is {@code JVM} so the scenario can be activated
   * on the test's class-scoped session without scope conflicts.
   *
   * @return the scenario scope name
   */
  String scope() default "JVM";
}

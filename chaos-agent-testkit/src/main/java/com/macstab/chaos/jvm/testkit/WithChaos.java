package com.macstab.chaos.jvm.testkit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates chaos for a test, declared in the same JSON model as the agent's startup plan: either
 * inline ({@link #value()}) or from a class-path resource ({@link #resource()}). The JSON is a
 * whole plan ({@code {"metadata": ..., "scenarios": [...]}}) or a single scenario object.
 *
 * <p>Scenarios with {@code "scope": "SESSION"} run in the test's session (and so only for the
 * test's own threads and the work it hands off); {@code "JVM"} scenarios apply JVM-wide while the
 * test runs. Both are stopped when the test (method-level annotation) or the test class
 * (class-level annotation, Spring and Micronaut) ends.
 *
 * <p>Honoured by the testkit's {@link ChaosAgentExtension} and by the Spring Boot, Micronaut and
 * Quarkus test extensions, so the same annotation works in every stack.
 *
 * <pre>{@code
 * @ExtendWith(ChaosAgentExtension.class)
 * @WithChaos("""
 *     {"id": "slow-db", "scope": "SESSION",
 *      "selector": {"type": "jdbc", "operations": ["JDBC_STATEMENT_EXECUTE"]},
 *      "effect": {"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"}}
 *     """)
 * class RepositoryTimeoutTest { ... }
 * }</pre>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(WithChaos.List.class)
public @interface WithChaos {

  /**
   * Inline JSON: a plan or a single scenario.
   *
   * @return the JSON, or empty when {@link #resource()} is used
   */
  String value() default "";

  /**
   * Class-path resource holding the JSON, e.g. {@code "chaos/slow-db.json"} (a leading {@code /} or
   * {@code classpath:} is accepted).
   *
   * @return the resource, or empty when {@link #value()} is used
   */
  String resource() default "";

  /** Container for repeated {@link WithChaos} annotations. */
  @Documented
  @Inherited
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    /**
     * The repeated annotations.
     *
     * @return the annotations
     */
    WithChaos[] value();
  }
}

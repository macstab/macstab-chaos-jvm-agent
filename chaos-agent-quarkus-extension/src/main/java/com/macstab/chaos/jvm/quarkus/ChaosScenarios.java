package com.macstab.chaos.jvm.quarkus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for {@link ChaosScenario} so callers may repeat {@code @ChaosScenario} on a
 * single test class or method. Callers do not use this type directly; Java's {@code @Repeatable}
 * machinery wraps repeated {@code @ChaosScenario} declarations transparently.
 *
 * @deprecated The container of the deprecated {@link ChaosScenario}; use {@link
 *     com.macstab.chaos.jvm.testkit.WithChaos}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Deprecated(since = "2.0", forRemoval = true)
@SuppressWarnings("removal")
public @interface ChaosScenarios {
  /**
   * The array of repeated {@link ChaosScenario} declarations on the annotated element.
   *
   * @return one or more chaos scenario annotations
   */
  ChaosScenario[] value();
}

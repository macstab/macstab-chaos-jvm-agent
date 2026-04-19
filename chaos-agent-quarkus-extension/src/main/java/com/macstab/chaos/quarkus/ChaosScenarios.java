package com.macstab.chaos.quarkus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for {@link ChaosScenario} so callers may repeat {@code @ChaosScenario} on a
 * single test class or method. Callers do not use this type directly; Java's {@code @Repeatable}
 * machinery wraps repeated {@code @ChaosScenario} declarations transparently.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ChaosScenarios {
  /**
   * The array of repeated {@link ChaosScenario} declarations on the annotated element.
   *
   * @return one or more chaos scenario annotations
   */
  ChaosScenario[] value();
}

package com.macstab.chaos.spring.boot3.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContextInitializer;

/**
 * Meta-annotation combining {@link SpringBootTest} and the {@link ChaosAgentExtension} so that a
 * test class opts into chaos instrumentation with a single annotation.
 *
 * <p>Each annotated class gets a JVM-scope {@link com.macstab.chaos.api.ChaosSession} opened once
 * before the first test and closed after the last one. The session can be injected as a method
 * parameter.
 *
 * <pre>{@code
 * @ChaosTest
 * class OrderServiceChaosTest {
 *     @Test
 *     void slowDatabaseRejectsOrdersGracefully(ChaosSession chaos) {
 *         chaos.activate(...);
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@ExtendWith(ChaosAgentExtension.class)
public @interface ChaosTest {

  /**
   * Properties to apply to the test context.
   *
   * @return forwarded to {@code @SpringBootTest.properties}
   */
  String[] properties() default {};

  /**
   * Component classes to use for loading the application context.
   *
   * @return forwarded to {@code @SpringBootTest.classes}
   */
  Class<?>[] classes() default {};

  /**
   * Type of web environment to create for the test.
   *
   * @return forwarded to {@code @SpringBootTest.webEnvironment}
   */
  WebEnvironment webEnvironment() default WebEnvironment.MOCK;

  /**
   * Application arguments to pass to the application under test.
   *
   * @return forwarded to {@code @SpringBootTest.args}
   */
  String[] args() default {};

  /**
   * Application context initializers to apply before context refresh.
   *
   * @return forwarded to {@code @SpringBootTest.initializers}
   */
  Class<? extends ApplicationContextInitializer<?>>[] initializers() default {};
}

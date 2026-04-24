package com.macstab.chaos.jvm.micronaut;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Meta-annotation combining {@link MicronautTest} and the {@link ChaosMicronautExtension} so that a
 * test class opts into chaos instrumentation with a single annotation.
 *
 * <p>Each annotated class gets a class-scoped {@link com.macstab.chaos.jvm.api.ChaosSession} opened
 * once before the first test and closed after the last one. The session can be injected as a test
 * method parameter.
 *
 * <pre>{@code
 * @MicronautChaosTest
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
@MicronautTest
@ExtendWith(ChaosMicronautExtension.class)
public @interface MicronautChaosTest {}

package com.macstab.chaos.jvm.quarkus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Meta-annotation that bundles {@code @QuarkusTest} and {@link ChaosQuarkusExtension} into a single
 * declaration.
 *
 * <p>Users apply {@code @QuarkusChaosTest} to their test class and the extension takes care of
 * installing the chaos agent, opening a class-scoped {@link
 * com.macstab.chaos.jvm.api.ChaosSession}, and reading any {@link ChaosScenario} annotations on the
 * class or test methods.
 *
 * <p>{@link io.quarkus.test.junit.QuarkusTest} is a {@code compileOnly} dependency: the test
 * classpath that uses this annotation brings Quarkus, the agent itself does not need it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @QuarkusChaosTest
 * class MyChaosTest {
 *
 *   @Test
 *   void runsWithChaos(ChaosSession session) {
 *     // session is injected by the extension; @QuarkusTest gives access to CDI beans
 *   }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@io.quarkus.test.junit.QuarkusTest
@ExtendWith(ChaosQuarkusExtension.class)
public @interface QuarkusChaosTest {}

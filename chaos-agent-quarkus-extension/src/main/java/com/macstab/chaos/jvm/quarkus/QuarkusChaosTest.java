package com.macstab.chaos.jvm.quarkus;

import java.lang.annotation.ElementType;
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
 * <p>The {@code @QuarkusTest} reference is intentionally string-based (via {@link
 * io.quarkus.test.junit.QuarkusTest}): it is declared at compile time but only resolved at runtime
 * by the JUnit extension engine. The chaos agent itself does not require Quarkus on the classpath;
 * the dependency is {@code compileOnly}.
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
@io.quarkus.test.junit.QuarkusTest
@ExtendWith(ChaosQuarkusExtension.class)
public @interface QuarkusChaosTest {}

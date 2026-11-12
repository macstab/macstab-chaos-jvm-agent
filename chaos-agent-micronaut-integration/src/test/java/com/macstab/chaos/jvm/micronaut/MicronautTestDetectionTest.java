package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * The control-plane parameter is left to Micronaut's extension only where that extension resolves
 * parameters, and finding out needs no micronaut-test on the class path.
 */
@DisplayName("ChaosMicronautExtension and @MicronautTest")
class MicronautTestDetectionTest {

  @MicronautTest
  static final class ResolvingTest {}

  @MicronautTest(resolveParameters = false)
  static final class NotResolvingTest {}

  @MicronautChaosTest
  static final class MetaAnnotatedTest {}

  static final class PlainTest {}

  @Test
  @DisplayName(
      "leaves the control plane to Micronaut, which resolves it, also as a meta-annotation")
  void leavesItToMicronaut() throws Exception {
    final ChaosMicronautExtension extension = new ChaosMicronautExtension();

    assertThat(extension.supportsParameter(controlPlane(), context(ResolvingTest.class))).isFalse();
    assertThat(extension.supportsParameter(controlPlane(), context(MetaAnnotatedTest.class)))
        .isFalse();
    assertThat(extension.supportsParameter(controlPlane(), context(PlainTest.class))).isTrue();
  }

  @Test
  @DisplayName("resolves the control plane where @MicronautTest resolves no parameters")
  void resolvesWhereMicronautDoesNot() throws Exception {
    assertThat(
            new ChaosMicronautExtension()
                .supportsParameter(controlPlane(), context(NotResolvingTest.class)))
        .isTrue();
  }

  @Test
  @DisplayName("decides without micronaut-test on the class path")
  void withoutMicronautTest() throws Exception {
    final URL extensionClasses =
        ChaosMicronautExtension.class.getProtectionDomain().getCodeSource().getLocation();
    // The extension's own classes are loaded again, by a loader that cannot see micronaut-test.
    final ClassLoader withoutMicronautTest =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          protected Class<?> loadClass(final String name, final boolean resolve)
              throws ClassNotFoundException {
            if (name.startsWith("io.micronaut.test.")
                || name.startsWith(ChaosMicronautExtension.class.getPackageName() + ".")) {
              throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
          }
        };
    try (URLClassLoader isolated =
        new URLClassLoader(new URL[] {extensionClasses}, withoutMicronautTest)) {
      final ParameterResolver extension =
          (ParameterResolver)
              isolated
                  .loadClass(ChaosMicronautExtension.class.getName())
                  .getConstructor()
                  .newInstance();

      assertThat(extension.supportsParameter(controlPlane(), context(PlainTest.class))).isTrue();
    }
  }

  @SuppressWarnings("unused")
  private static void target(final ChaosControlPlane plane) {}

  private static ParameterContext controlPlane() throws Exception {
    final Parameter parameter =
        MicronautTestDetectionTest.class.getDeclaredMethod("target", ChaosControlPlane.class)
            .getParameters()[0];
    return (ParameterContext)
        Proxy.newProxyInstance(
            ParameterContext.class.getClassLoader(),
            new Class<?>[] {ParameterContext.class},
            (self, method, args) ->
                method.getName().equals("getParameter")
                    ? parameter
                    : method.isDefault()
                        ? java.lang.reflect.InvocationHandler.invokeDefault(self, method, args)
                        : null);
  }

  /** A class context for {@code testClass}, without a parent. */
  private static ExtensionContext context(final Class<?> testClass) {
    return (ExtensionContext)
        Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] {ExtensionContext.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "getTestClass" -> Optional.of(testClass);
                  case "getParent" -> Optional.empty();
                  default -> null;
                });
  }
}

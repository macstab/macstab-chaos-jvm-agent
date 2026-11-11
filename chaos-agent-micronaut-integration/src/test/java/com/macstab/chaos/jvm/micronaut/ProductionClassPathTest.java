package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An application ships this module for its {@code /chaos} endpoint and its control-plane bean,
 * without {@code chaos-agent-testkit}: the module declares the testkit for compilation only, so a
 * production context must start and create its beans without it.
 */
@DisplayName("the production class path")
class ProductionClassPathTest {

  private static final String TESTKIT_CLASS =
      "com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane";

  @Test
  @DisplayName("the module's runtime class path and its api variant leave the testkit out")
  void publishedVariantsLeaveTheTestkitOut() {
    final List<String> runtime = buildList("chaos.production.runtimeClasspath", File.pathSeparator);
    final List<String> api = buildList("chaos.production.apiDependencies", ",");

    assertThat(runtime)
        .anyMatch(name -> name.startsWith("chaos-agent-api"))
        .noneMatch(name -> name.startsWith("chaos-agent-testkit"));
    assertThat(api)
        .anyMatch(dependency -> dependency.endsWith(":chaos-agent-api"))
        .noneMatch(dependency -> dependency.endsWith(":chaos-agent-testkit"));
  }

  /** The file names or coordinates the build hands the test in {@code property}. */
  private static List<String> buildList(final String property, final String separator) {
    final String value =
        Objects.requireNonNull(System.getProperty(property), property + ", set by the build");
    return Arrays.stream(value.split(separator, -1)).filter(entry -> !entry.isEmpty()).toList();
  }

  @Test
  @DisplayName("an application context without the testkit starts and creates the chaos beans")
  void startsWithoutTheTestkit() throws Exception {
    try (URLClassLoader loader = productionLoader()) {
      assertThatThrownBy(() -> loader.loadClass(TESTKIT_CLASS))
          .isInstanceOf(ClassNotFoundException.class);
      final Thread thread = Thread.currentThread();
      final ClassLoader previous = thread.getContextClassLoader();
      thread.setContextClassLoader(loader);
      try {
        final Class<?> applicationContext =
            loader.loadClass("io.micronaut.context.ApplicationContext");
        final Class<?> builderType =
            loader.loadClass("io.micronaut.context.ApplicationContextBuilder");
        Object builder =
            applicationContext
                .getMethod("builder", ClassLoader.class, String[].class)
                .invoke(null, loader, new String[0]);
        builder = builderType.getMethod("deduceEnvironment", Boolean.class).invoke(builder, false);
        builder =
            builderType
                .getMethod("properties", Map.class)
                .invoke(builder, Map.of("macstab.chaos.management.enabled", "true"));
        try (AutoCloseable context =
            (AutoCloseable) builderType.getMethod("start").invoke(builder)) {
          final Object endpoint =
              applicationContext
                  .getMethod("getBean", Class.class)
                  .invoke(context, loader.loadClass(ChaosManagementEndpoint.class.getName()));
          final Collection<?> definitions =
              (Collection<?>) applicationContext.getMethod("getAllBeanDefinitions").invoke(context);
          final var beanType =
              loader.loadClass("io.micronaut.inject.BeanDefinition").getMethod("getBeanType");
          final List<String> beanTypes = new ArrayList<>();
          for (final Object definition : definitions) {
            beanTypes.add(((Class<?>) beanType.invoke(definition)).getName());
          }

          assertThat(endpoint.getClass().getName())
              .isEqualTo(ChaosManagementEndpoint.class.getName());
          assertThat(beanTypes)
              .contains(ChaosFactory.class.getName())
              .doesNotContain(ChaosTestFactory.class.getName());
        }
      } finally {
        thread.setContextClassLoader(previous);
      }
    }
  }

  /** The test class path without the testkit and without this module's test classes. */
  private static URLClassLoader productionLoader() throws Exception {
    final List<URL> urls = new ArrayList<>();
    for (final String entry : System.getProperty("java.class.path").split(File.pathSeparator, -1)) {
      final String path = entry.replace('\\', '/');
      if (path.isEmpty()
          || path.contains("chaos-agent-testkit")
          || path.contains("/build/classes/java/test")
          || path.contains("/build/resources/test")) {
        continue;
      }
      urls.add(Path.of(entry).toUri().toURL());
    }
    return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }
}

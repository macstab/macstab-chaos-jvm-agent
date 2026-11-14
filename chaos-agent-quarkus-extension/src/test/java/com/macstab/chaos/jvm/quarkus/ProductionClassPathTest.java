package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An application ships this module for its control-plane bean and the agent's runtime-init install,
 * without {@code chaos-agent-testkit}: the module declares the testkit for compilation only, so
 * neither what its consumers resolve nor a plain {@code @QuarkusTest} needs it.
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

  @Test
  @DisplayName(
      "in a @QuarkusTest without the testkit the bean is the untracked plane, and nothing warns")
  void plainQuarkusTestWithoutTheTestkitIsSilent() throws Exception {
    final Logger logger = Logger.getLogger(ChaosArcProducer.class.getName());
    final List<LogRecord> records = new ArrayList<>();
    final Handler capture =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(capture);
    try (URLClassLoader loader = withoutTheTestkit()) {
      assertThatThrownBy(() -> loader.loadClass(TESTKIT_CLASS))
          .isInstanceOf(ClassNotFoundException.class);
      final Class<?> planeType = loader.loadClass("com.macstab.chaos.jvm.api.ChaosControlPlane");
      final Object plane =
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {planeType},
              (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
              });
      final Method callExtension =
          loader
              .loadClass(ChaosArcProducer.class.getName())
              .getDeclaredMethod("callExtension", String.class, planeType, ClassLoader.class);
      callExtension.setAccessible(true);

      final Object tracked = callExtension.invoke(null, "contextPlane", plane, loader);
      callExtension.invoke(null, "releaseContextPlane", plane, loader);

      assertThat(tracked).as("the producer hands out the plane itself").isNull();
      assertThat(records)
          .as("a plain @QuarkusTest logs no warning")
          .noneMatch(record -> record.getLevel().intValue() >= Level.WARNING.intValue());
    } finally {
      logger.removeHandler(capture);
    }
  }

  @Test
  @DisplayName("the testkit is looked up where the test run has it, not only on the system loader")
  void testkitLoaderFindsTheTestRunsLoader() throws Exception {
    final ClassLoader withTestkit = ProductionClassPathTest.class.getClassLoader();
    try (URLClassLoader without = withoutTheTestkit()) {
      // Under continuous testing the system loader lacks the testkit; Quarkus's own loader has it.
      assertThat(ChaosArcProducer.testkitLoader(without, null, withTestkit)).isSameAs(withTestkit);
      assertThat(ChaosArcProducer.testkitLoader(withTestkit, without)).isSameAs(withTestkit);
      assertThat(ChaosArcProducer.testkitLoader(null, without)).as("none has it").isSameAs(without);
    }
  }

  /** The file names or coordinates the build hands the test in {@code property}. */
  private static List<String> buildList(final String property, final String separator) {
    final String value =
        Objects.requireNonNull(System.getProperty(property), property + ", set by the build");
    return Arrays.stream(value.split(separator, -1)).filter(entry -> !entry.isEmpty()).toList();
  }

  /**
   * The test class path without the testkit and without this module's test classes: the class path
   * of a {@code @QuarkusTest} whose project does not declare the testkit.
   */
  private static URLClassLoader withoutTheTestkit() throws Exception {
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

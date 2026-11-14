package com.macstab.chaos.jvm.examples.quarkus;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Hands {@code @QuarkusTest} the application model that the Quarkus Gradle plugin would build: from
 * the class paths this module's build wrote ({@code chaos.quarkus.modelInputs}), it builds the
 * model of this application and serializes it where Quarkus reads its test model from ({@value
 * BootstrapConstants#SERIALIZED_TEST_APP_MODEL}). Runtime artifacts are on the runtime and the
 * deployment class path, deployment artifacts on the deployment class path only, and a runtime
 * artifact with {@code META-INF/quarkus-extension.properties} is a runtime extension.
 */
public final class QuarkusAppModel implements LauncherSessionListener {

  private static final String EXTENSION_PROPERTIES = "META-INF/quarkus-extension.properties";

  /** Called by the JUnit launcher, from {@code META-INF/services}. */
  public QuarkusAppModel() {}

  @Override
  public void launcherSessionOpened(final LauncherSession session) {
    final String inputs = System.getProperty("chaos.quarkus.modelInputs");
    if (inputs == null
        || System.getProperty(BootstrapConstants.SERIALIZED_TEST_APP_MODEL) != null) {
      return;
    }
    try {
      System.setProperty(
          BootstrapConstants.SERIALIZED_TEST_APP_MODEL, write(Path.of(inputs)).toString());
    } catch (final IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }

  private static Path write(final Path inputs) throws IOException {
    final Path buildDir = inputs.getParent();
    final String[] app = System.getProperty("chaos.quarkus.appCoordinates").split(":", -1);
    final List<Path> appPaths = new ArrayList<>();
    for (final Path candidate :
        // In a test model the application holds the test output too, so test classes are beans.
        List.of(
            buildDir.resolve("classes/java/main"),
            buildDir.resolve("resources/main"),
            buildDir.resolve("classes/java/test"),
            buildDir.resolve("resources/test"))) {
      if (Files.isDirectory(candidate)) {
        appPaths.add(candidate);
      }
    }
    final ApplicationModelBuilder model = new ApplicationModelBuilder();
    model.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId(app[0])
            .setArtifactId(app[1])
            .setVersion(app[2])
            .setResolvedPaths(PathList.from(appPaths)));

    final Map<String, ResolvedDependencyBuilder> dependencies = new LinkedHashMap<>();
    for (final String line : Files.readAllLines(inputs, StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }
      final String[] parts = line.split(" ", 3);
      final boolean runtime = parts[0].equals("R");
      final String[] gav = parts[1].split(":", -1);
      final Path path = Path.of(parts[2]);
      final ResolvedDependencyBuilder dependency =
          dependencies.computeIfAbsent(
              gav[0] + ":" + gav[1],
              key ->
                  ResolvedDependencyBuilder.newInstance()
                      .setGroupId(gav[0])
                      .setArtifactId(gav[1])
                      .setVersion(gav[2])
                      .setResolvedPath(path));
      dependency.setDeploymentCp();
      if (runtime && !dependency.isFlagSet(DependencyFlags.RUNTIME_CP)) {
        dependency.setRuntimeCp();
        final Properties extension = extensionProperties(path);
        if (extension != null) {
          dependency
              .setRuntimeExtensionArtifact()
              .setFlags(DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT);
          model.handleExtensionProperties(extension, dependency.getKey());
        }
      }
    }
    dependencies.values().forEach(model::addDependency);

    final Path serialized = buildDir.resolve("quarkus-test-app-model.dat");
    try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(serialized))) {
      out.writeObject(model.build());
    }
    return serialized;
  }

  private static @Nullable Properties extensionProperties(final Path artifact) throws IOException {
    if (!Files.isRegularFile(artifact) || !artifact.toString().endsWith(".jar")) {
      return null;
    }
    try (ZipFile jar = new ZipFile(artifact.toFile())) {
      final ZipEntry entry = jar.getEntry(EXTENSION_PROPERTIES);
      if (entry == null) {
        return null;
      }
      final Properties properties = new Properties();
      try (InputStream in = jar.getInputStream(entry)) {
        properties.load(in);
      }
      return properties;
    }
  }
}

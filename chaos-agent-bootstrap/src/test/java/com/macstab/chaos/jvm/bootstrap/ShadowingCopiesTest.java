package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ChaosAgentBootstrap#reportShadowingCopies}: agent copies of different versions on one
 * class path are reported once, on standard error; copies of one version, and copies without a
 * version, are not.
 */
@DisplayName("Agent copies on the class path")
class ShadowingCopiesTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("copies of different versions are reported, naming each jar and version")
  void differentVersionsAreReported() throws Exception {
    final Path older = agentJar("older.jar", "1.0.0");
    final Path newer = agentJar("newer.jar", "2.0.0");

    final String reported = reportFor(older, newer);

    assertThat(reported)
        .contains("[chaos-agent] agent copies of different versions are on the class path")
        .contains(older + " (1.0.0), " + newer + " (2.0.0)")
        .contains("Keep one agent version.");
  }

  @Test
  @DisplayName("copies of one version, or without a manifest or a version, are not reported")
  void sameOrUnknownVersionsAreNotReported() throws Exception {
    final String reported =
        reportFor(
            agentJar("first.jar", "2.0.0"),
            agentJar("second.jar", "2.0.0"),
            agentJar("no-version.jar", null),
            agentJarWithoutManifest("no-manifest.jar"));

    assertThat(reported).isEmpty();
  }

  @Test
  @DisplayName(
      "a class loader whose copies cannot be listed is not compared, and the start goes on")
  void unlistableCopiesAreNotCompared() {
    final ClassLoader unlistable =
        new ClassLoader(null) {
          @Override
          public java.util.Enumeration<URL> getResources(final String name)
              throws java.io.IOException {
            throw new java.io.IOException("unreadable class path");
          }
        };
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final PrintStream original = System.err;
    try (PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setErr(capture);
      ChaosAgentBootstrap.reportShadowingCopies(unlistable);
    } finally {
      System.setErr(original);
    }

    assertThat(captured.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  /** Runs the report over a class loader of {@code jars} and returns what it wrote to stderr. */
  private static String reportFor(final Path... jars) throws Exception {
    final URL[] urls = new URL[jars.length];
    for (int i = 0; i < jars.length; i++) {
      urls[i] = jars[i].toUri().toURL();
    }
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final PrintStream original = System.err;
    try (URLClassLoader loader = new URLClassLoader(urls, null);
        PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setErr(capture);
      ChaosAgentBootstrap.reportShadowingCopies(loader);
    } finally {
      System.setErr(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  private Path agentJar(final String name, final @Nullable String version) throws Exception {
    final Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (version != null) {
      manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
    }
    final Path jar = tempDir.resolve(name);
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file, manifest)) {
      writeEntryClass(out);
    }
    return jar;
  }

  private Path agentJarWithoutManifest(final String name) throws Exception {
    final Path jar = tempDir.resolve(name);
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file)) {
      writeEntryClass(out);
    }
    return jar;
  }

  private static void writeEntryClass(final JarOutputStream out) throws Exception {
    out.putNextEntry(new JarEntry("com/macstab/chaos/jvm/bootstrap/ChaosAgentBootstrap.class"));
    out.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
    out.closeEntry();
  }
}

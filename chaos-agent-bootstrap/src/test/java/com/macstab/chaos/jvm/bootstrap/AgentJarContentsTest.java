package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosPlan;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Published jar contents")
class AgentJarContentsTest {

  private static JarFile agentJar() throws IOException {
    final String path = System.getProperty("chaos.bootstrap.agentJar");
    assertThat(path).as("chaos.bootstrap.agentJar must be set by the build").isNotBlank();
    return new JarFile(path);
  }

  private static String text(final JarFile jar, final String name) throws IOException {
    final ZipEntry entry = jar.getEntry(name);
    assertThat(entry).as("%s in %s", name, jar.getName()).isNotNull();
    try (InputStream in = jar.getInputStream(entry)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Nested
  @DisplayName("the shaded agent jar")
  class ShadedAgentJar {

    @Test
    @DisplayName("carries the project LICENSE as META-INF/LICENSE")
    void carriesProjectLicense() throws IOException {
      try (JarFile jar = agentJar()) {
        assertThat(text(jar, "META-INF/LICENSE").strip()).startsWith("Apache License");
      }
    }

    @Test
    @DisplayName("carries a merged NOTICE naming the bundled libraries' copyright holders")
    void carriesMergedNotice() throws IOException {
      try (JarFile jar = agentJar()) {
        final String notice = text(jar, "META-INF/NOTICE");
        assertThat(notice)
            .contains("Rafael Winterhalter")
            .contains("Jackson")
            .doesNotContain("Apache Software Foundation");
      }
    }

    @Test
    @DisplayName("keeps the ASM licence shipped by ByteBuddy")
    void keepsAsmLicense() throws IOException {
      try (JarFile jar = agentJar()) {
        assertThat(jar.getEntry("META-INF/licenses/ASM")).isNotNull();
      }
    }
  }

  @Nested
  @DisplayName("the other published jars")
  class OtherPublishedJars {

    @Test
    @DisplayName("the API jar carries the project LICENSE")
    void apiJarCarriesLicense() throws IOException, URISyntaxException {
      final Path apiJar =
          Path.of(ChaosPlan.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      assertThat(apiJar.getFileName().toString())
          .as("API on the class path as a jar")
          .endsWith(".jar");
      try (JarFile jar = new JarFile(apiJar.toFile())) {
        assertThat(text(jar, "META-INF/LICENSE").strip()).startsWith("Apache License");
      }
    }
  }
}

package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.macstab.chaos.jvm.startup.AgentSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The start-up switches read from the agent arguments before the property and environment. */
@DisplayName("Start-up settings")
class StartupSettingsTest {

  @Test
  @DisplayName("the weaving mode in the agent args wins over the environment")
  void weavingModeFromArgs() {
    final Map<String, String> eagerEnvironment =
        Map.of(ChaosAgentBootstrap.INSTRUMENTATION_ENV, "eager");

    assertThat(
            ChaosAgentBootstrap.eagerInstrumentation(
                AgentSettings.of("instrumentation= EAGER ", Map.of())))
        .isTrue();
    assertThat(
            ChaosAgentBootstrap.eagerInstrumentation(
                AgentSettings.of("instrumentation=onDemand", eagerEnvironment)))
        .as("the agent argument is read first")
        .isFalse();
    assertThat(ChaosAgentBootstrap.eagerInstrumentation(AgentSettings.of("", eagerEnvironment)))
        .isTrue();
    assertThat(ChaosAgentBootstrap.eagerInstrumentation(AgentSettings.of("", Map.of()))).isFalse();
  }

  @Test
  @DisplayName("a list in the agent args wins over the environment and drops blank entries")
  void listFromArgs() {
    final Map<String, String> environment =
        Map.of(ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ENV, "env.example");

    assertThat(
            ChaosAgentBootstrap.listSetting(
                AgentSettings.of("sessionHeaderHosts= a.example , ,b.example,", environment),
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ARG,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_PROPERTY,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ENV))
        .containsExactly("a.example", "b.example");
    assertThat(
            ChaosAgentBootstrap.listSetting(
                AgentSettings.of("", environment),
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ARG,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_PROPERTY,
                ChaosAgentBootstrap.SESSION_HEADER_HOSTS_ENV))
        .containsExactly("env.example");
    assertThat(
            ChaosAgentBootstrap.clockCallSites(
                AgentSettings.of("clockCallSites=com.example", Map.of())))
        .containsExactly("com.example");
  }

  @Test
  @DisplayName("dynamic attach is opted into with any standard true literal")
  void dynamicAttachLiterals() {
    assertThat(
            ChaosAgentBootstrap.dynamicAttachAllowed(
                new java.util.Properties(),
                Map.of(ChaosAgentBootstrap.ALLOW_DYNAMIC_ATTACH_ENV, "yes")))
        .isTrue();
    final java.util.Properties properties = new java.util.Properties();
    properties.setProperty(ChaosAgentBootstrap.ALLOW_DYNAMIC_ATTACH_PROPERTY, "1");
    assertThat(ChaosAgentBootstrap.dynamicAttachAllowed(properties, Map.of())).isTrue();
    assertThat(ChaosAgentBootstrap.dynamicAttachAllowed(new java.util.Properties(), Map.of()))
        .isFalse();
  }

  @Test
  @DisplayName("bridgeDir names a trusted directory, or nothing when it is not set")
  void bridgeDirTrusted(@TempDir final Path tempDir) throws Exception {
    final Path bridge = Files.createDirectory(tempDir.resolve("bridge"));

    assertThat(ChaosAgentBootstrap.bridgeDir(AgentSettings.of("bridgeDir=" + bridge, Map.of())))
        .isEqualTo(bridge.toRealPath());
    assertThat(ChaosAgentBootstrap.bridgeDir(AgentSettings.of("", Map.of()))).isNull();
  }

  @Test
  @DisplayName(
      "bridgeDir is the real path it was checked at: a link re-pointed later moves nothing")
  void bridgeDirIsTheCheckedRealPath(@TempDir final Path tempDir) throws Exception {
    final Path trusted = Files.createDirectory(tempDir.resolve("trusted"));
    final Path other = Files.createDirectory(tempDir.resolve("other"));
    final Path link = Files.createSymbolicLink(tempDir.resolve("bridge"), trusted);

    final Path directory =
        ChaosAgentBootstrap.bridgeDir(AgentSettings.of("bridgeDir=" + link, Map.of()));
    Files.delete(link);
    Files.createSymbolicLink(link, other);
    final Path jar =
        Files.createTempFile(java.util.Objects.requireNonNull(directory), "bridge", ".jar");

    assertThat(jar.toRealPath().getParent()).isEqualTo(trusted.toRealPath());
  }

  @Test
  @DisplayName("a bridgeDir that does not exist is refused: it cannot be resolved")
  void missingBridgeDirRefused(@TempDir final Path tempDir) {
    final Path missing = tempDir.resolve("missing");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ChaosAgentBootstrap.bridgeDir(AgentSettings.of("bridgeDir=" + missing, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bridgeDir " + missing + " is refused: it cannot be resolved");
  }

  @Test
  @DisplayName("a world-writable bridgeDir without the sticky bit is refused with the reason")
  void bridgeDirRefused(@TempDir final Path tempDir) throws Exception {
    assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
    final Path open = Files.createDirectory(tempDir.resolve("open"));
    Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));

    assertThatThrownBy(
            () -> ChaosAgentBootstrap.bridgeDir(AgentSettings.of("bridgeDir=" + open, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("[chaos-agent] bridgeDir " + open + " is refused: ");
  }
}

package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The layering of every published module, read from the imports of its production sources: the
 * instrumentation module's {@code ArchitectureTest} reads class files, but its test class path
 * holds only the API, the core and the instrumentation, so it cannot see the startup configuration,
 * the bootstrap or the integrations. This test covers those (ADR 0003: integrations reach the agent
 * through {@code ChaosPlatform}, {@code ChaosManagement} and {@code startup.*}, never through the
 * core). It sees imports only, not fully qualified names in code.
 */
@DisplayName("Module layering of the production sources")
class ModuleLayeringTest {

  private static final String AGENT = "com.macstab.chaos.jvm.";

  private static final Pattern IMPORT =
      Pattern.compile(
          "^import\\s+(?:static\\s+)?(com\\.macstab\\.chaos\\.jvm\\.[\\w.]+);", Pattern.MULTILINE);

  /** The agent packages each module may not import. */
  private static final Map<String, Set<String>> FORBIDDEN =
      Map.ofEntries(
          Map.entry("chaos-agent-api", Set.of("core", "instrumentation", "startup", "bootstrap")),
          Map.entry("chaos-agent-startup-config", Set.of("core", "instrumentation", "bootstrap")),
          Map.entry("chaos-agent-core", Set.of("instrumentation", "startup", "bootstrap")),
          Map.entry("chaos-agent-instrumentation-jdk", Set.of("startup", "bootstrap")));

  /** The modules that integrate the agent with a framework or a tool. */
  private static final List<String> INTEGRATIONS =
      List.of(
          "chaos-agent-micrometer",
          "chaos-agent-micronaut-integration",
          "chaos-agent-opentelemetry",
          "chaos-agent-quarkus-extension",
          "chaos-agent-quarkus-extension-deployment",
          "chaos-agent-quarkus-management",
          "chaos-agent-quarkus-management-deployment",
          "chaos-agent-spring-boot-common",
          "chaos-agent-spring-boot3-starter",
          "chaos-agent-spring-boot3-test-starter",
          "chaos-agent-spring-boot4-starter",
          "chaos-agent-spring-boot4-test-starter",
          "chaos-agent-testkit");

  /** The bootstrap types an integration may use (configuration-reference §14.2). */
  private static final Set<String> BOOTSTRAP_ENTRY_POINTS =
      Set.of(AGENT + "bootstrap.ChaosPlatform", AGENT + "bootstrap.ChaosManagement");

  /**
   * The testkit's assertions read the bridge's depth when it is on the boot path, linked only then
   * (compileOnly; {@code ChaosAssertions}): the one integration use of the instrumentation.
   */
  private static final String TESTKIT_BRIDGE = AGENT + "instrumentation.bridge.BootstrapDispatcher";

  @Test
  @DisplayName("the API, startup configuration, core and instrumentation import no layer above")
  void agentModules() throws IOException {
    final List<String> violations = new ArrayList<>();
    for (final Map.Entry<String, Set<String>> module : FORBIDDEN.entrySet()) {
      for (final Import used : imports(module.getKey())) {
        if (module.getValue().contains(layer(used.type()))) {
          violations.add(used.toString());
        }
      }
    }

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("the integrations import no core, and of the bootstrap only its entry points")
  void integrations() throws IOException {
    final List<String> violations = new ArrayList<>();
    int checked = 0;
    for (final String module : INTEGRATIONS) {
      final List<Import> used = imports(module);
      checked += used.size();
      for (final Import imported : used) {
        final String layer = layer(imported.type());
        final boolean allowed =
            switch (layer) {
              case "core" -> false;
              case "instrumentation" ->
                  module.equals("chaos-agent-testkit") && imported.type().equals(TESTKIT_BRIDGE);
              case "bootstrap" -> BOOTSTRAP_ENTRY_POINTS.contains(imported.type());
              default -> true;
            };
        if (!allowed) {
          violations.add(imported.toString());
        }
      }
    }

    assertThat(checked).as("imports read from the integration sources").isPositive();
    assertThat(violations).isEmpty();
  }

  /** The agent package below {@code com.macstab.chaos.jvm} that {@code type} belongs to. */
  private static String layer(final String type) {
    final String rest = type.substring(AGENT.length());
    final int dot = rest.indexOf('.');
    return dot < 0 ? rest : rest.substring(0, dot);
  }

  /** The agent imports of every production source of {@code module}. */
  private static List<Import> imports(final String module) throws IOException {
    final Path sources = repositoryRoot().resolve(module).resolve("src/main/java");
    assertThat(sources).as("production sources of %s", module).isDirectory();
    final List<Import> found = new ArrayList<>();
    try (Stream<Path> files = Files.walk(sources)) {
      for (final Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        final Matcher matcher = IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (matcher.find()) {
          found.add(new Import(module, sources.relativize(file).toString(), matcher.group(1)));
        }
      }
    }
    return found;
  }

  /** The repository root: the parent of this module's directory, the tests' working directory. */
  private static Path repositoryRoot() {
    final Path module = Path.of("").toAbsolutePath();
    assertThat(module.resolve("../chaos-agent-api")).isDirectory();
    return module.getParent();
  }

  /** One agent import of one source file. */
  private record Import(String module, String file, String type) {
    @Override
    public String toString() {
      return module + "/" + file + " imports " + type;
    }
  }
}

package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.CidrBlock;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps the documentation honest: every JVM name in this repository's {@code README.md} and {@code
 * docs/*.md} (the cross-layer mapping in {@code docs/cross-layer.md} among them) and in the native
 * library's {@code docs/DSL.md} must exist in the agent's API, and every {@code json} example that
 * is a plan or a scenario must parse. Schema sketches with placeholders are fenced as {@code jsonc}
 * and only have their {@code "type"} names checked.
 *
 * <p>Checked in code spans and fenced blocks: calls on the API types ({@code ChaosEffect.errno(}),
 * builder chains ({@code ActivationPolicy.builder().probability(}), nested types and enum constants
 * ({@code ChaosEffect.Errno.EIO}, {@code OperationType.FILE_IO_SYNC}), plan JSON {@code "type"}
 * names, and {@code json} blocks. The native library's document is checked only when the {@code
 * CHAOS_DSL_PATH} environment variable or the {@code chaos.dsl.path} system property names it (the
 * {@code cross-layer-docs} CI job does), so a stale neighbouring checkout cannot fail this build.
 */
@DisplayName("Cross-layer documentation matches the API")
class CrossLayerDocSyncTest {

  private static final Map<String, Class<?>> API_TYPES =
      Map.of(
          "ChaosEffect", ChaosEffect.class,
          "ChaosSelector", ChaosSelector.class,
          "ActivationPolicy", ActivationPolicy.class,
          "NamePattern", NamePattern.class,
          "PathPattern", PathPattern.class,
          "CidrBlock", CidrBlock.class,
          "ChaosScenario", ChaosScenario.class,
          "ChaosControlPlane", ChaosControlPlane.class,
          "ChaosSession", ChaosSession.class,
          "ChaosDiagnostics", ChaosDiagnostics.class);

  private static final String TYPE_NAMES = String.join("|", API_TYPES.keySet());

  private static final Pattern MEMBER_CALL =
      Pattern.compile("(?<![\\w.])(" + TYPE_NAMES + ")\\.([a-z]\\w*)\\s*\\(");
  private static final Pattern NESTED =
      Pattern.compile("(?<![\\w.])(" + TYPE_NAMES + ")\\.([A-Z]\\w*)(?:\\.([A-Z][A-Z0-9_]*)\\b)?");
  private static final Pattern OPERATION =
      Pattern.compile("(?<![\\w.])OperationType\\.([A-Za-z_]\\w*)");
  private static final Pattern BUILDER_CHAIN =
      Pattern.compile(
          "(?<![\\w.])(ActivationPolicy|ChaosScenario)\\.builder\\((?:[^()]|\\([^()]*\\))*\\)"
              + "((?:\\s*\\.\\w+\\((?:[^()]|\\([^()]*\\))*\\))*)");
  private static final Pattern CHAINED_CALL = Pattern.compile("\\.(\\w+)\\(");
  private static final Pattern JSON_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"(\\w+)\"");
  private static final Pattern FENCED =
      Pattern.compile("^```(\\w*)[^\\n]*\\n(.*?)^```", Pattern.MULTILINE | Pattern.DOTALL);
  private static final Pattern SCENARIO_KEYS = Pattern.compile("\"(id|selector|effect)\"\\s*:");
  private static final Pattern INLINE = Pattern.compile("`([^`\\n]+)`");

  @ParameterizedTest(name = "{0}")
  @MethodSource("agentDocuments")
  @DisplayName("agent documentation")
  void agentDocumentation(final String document) throws IOException {
    final Path doc = repositoryRoot().resolve(document);

    assertThat(violations(Files.readString(doc))).as(doc.toString()).isEmpty();
  }

  static Stream<String> agentDocuments() throws IOException {
    final Path root = repositoryRoot();
    try (Stream<Path> docs = Files.list(root.resolve("docs"))) {
      final List<String> names =
          docs.filter(f -> f.toString().endsWith(".md"))
              .map(f -> root.relativize(f).toString())
              .sorted()
              .collect(Collectors.toCollection(ArrayList::new));
      names.add(0, "README.md");
      return names.stream();
    }
  }

  @Test
  @DisplayName("the native library's docs/DSL.md")
  void nativeLibraryDsl() throws IOException {
    final Optional<Path> doc = nativeDsl();
    assumeTrue(doc.isPresent(), "set CHAOS_DSL_PATH to the native library's docs/DSL.md");

    assertThat(violations(Files.readString(doc.get()))).as(doc.get().toString()).isEmpty();
  }

  @Test
  @DisplayName("the checker reports names the API does not have")
  void reportsUnknownNames() {
    final String stale =
        """
        Use `ActivationPolicy.timeWindow(a, b)`, `ChaosEffect.throwException(x)`,
        `ChaosSelector.fileIO(OperationType.FSYNC)`, `ChaosEffect.Errno.EWHATEVER`,
        `ActivationPolicy.builder().probability(0.5).session("x").build()` and
        `{"type": "byteCorruption"}`.

        ```json
        {"id": "broken", "selector": {"type": "fileIo"}}
        ```
        """;

    assertThat(violations(stale))
        .anySatisfy(v -> assertThat(v).contains("ActivationPolicy.timeWindow"))
        .anySatisfy(v -> assertThat(v).contains("ChaosEffect.throwException"))
        .anySatisfy(v -> assertThat(v).contains("ChaosSelector.fileIO"))
        .anySatisfy(v -> assertThat(v).contains("OperationType.FSYNC"))
        .anySatisfy(v -> assertThat(v).contains("ChaosEffect.Errno.EWHATEVER"))
        .anySatisfy(v -> assertThat(v).contains("ActivationPolicy.Builder.session"))
        .anySatisfy(v -> assertThat(v).contains("byteCorruption"))
        .anySatisfy(v -> assertThat(v).contains("json block"));
  }

  static List<String> violations(final String markdown) {
    final List<String> violations = new ArrayList<>();
    final List<String> code = new ArrayList<>();
    final Matcher fenced = FENCED.matcher(markdown);
    final StringBuilder prose = new StringBuilder();
    int last = 0;
    while (fenced.find()) {
      prose.append(markdown, last, fenced.start());
      last = fenced.end();
      code.add(fenced.group(2));
      if (fenced.group(1).equals("json")) {
        final long line = markdown.substring(0, fenced.start()).lines().count() + 1;
        checkJsonBlock(fenced.group(2), line, violations);
      }
    }
    prose.append(markdown.substring(last));
    final Matcher inline = INLINE.matcher(prose);
    while (inline.find()) {
      code.add(inline.group(1));
    }
    final Set<String> jsonTypes = jsonTypeNames();
    for (final String snippet : code) {
      checkMembers(snippet, violations);
      checkNested(snippet, violations);
      checkOperations(snippet, violations);
      checkBuilderChains(snippet, violations);
      final Matcher type = JSON_TYPE.matcher(snippet);
      while (type.find()) {
        if (!jsonTypes.contains(type.group(1))) {
          violations.add("plan JSON type \"" + type.group(1) + "\" does not exist");
        }
      }
    }
    return violations.stream().distinct().toList();
  }

  private static void checkMembers(final String snippet, final List<String> violations) {
    final Matcher call = MEMBER_CALL.matcher(snippet);
    while (call.find()) {
      final Class<?> type = API_TYPES.get(call.group(1));
      if (!hasMethod(type, call.group(2))) {
        violations.add(call.group(1) + "." + call.group(2) + "(...) does not exist");
      }
    }
  }

  private static void checkNested(final String snippet, final List<String> violations) {
    final Matcher nested = NESTED.matcher(snippet);
    while (nested.find()) {
      final Class<?> owner = API_TYPES.get(nested.group(1));
      final Optional<Class<?>> type =
          Arrays.stream(owner.getClasses())
              .filter(c -> c.getSimpleName().equals(nested.group(2)))
              .findFirst();
      final String name = nested.group(1) + "." + nested.group(2);
      if (type.isEmpty()) {
        if (!hasConstantField(owner, nested.group(2))) {
          violations.add(name + " does not exist");
        }
      } else if (nested.group(3) != null && !hasConstant(type.get(), nested.group(3))) {
        violations.add(name + "." + nested.group(3) + " does not exist");
      }
    }
  }

  private static void checkOperations(final String snippet, final List<String> violations) {
    final Matcher operation = OPERATION.matcher(snippet);
    while (operation.find()) {
      if (!hasConstant(OperationType.class, operation.group(1))) {
        violations.add("OperationType." + operation.group(1) + " does not exist");
      }
    }
  }

  private static void checkBuilderChains(final String snippet, final List<String> violations) {
    final Matcher chain = BUILDER_CHAIN.matcher(snippet);
    while (chain.find()) {
      final Class<?> builder =
          chain.group(1).equals("ActivationPolicy")
              ? ActivationPolicy.Builder.class
              : ChaosScenario.Builder.class;
      for (final String method : topLevelCalls(chain.group(2))) {
        if (!hasMethod(builder, method)) {
          violations.add(chain.group(1) + ".Builder." + method + "(...) does not exist");
        }
      }
    }
  }

  /** The method names of {@code .name(...)} calls outside any argument list. */
  private static List<String> topLevelCalls(final String chain) {
    final List<String> names = new ArrayList<>();
    int depth = 0;
    for (int i = 0; i < chain.length(); i++) {
      final char c = chain.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == '.' && depth == 0) {
        final Matcher call = CHAINED_CALL.matcher(chain).region(i, chain.length());
        if (call.lookingAt()) {
          names.add(call.group(1));
        }
      }
    }
    return names;
  }

  private static void checkJsonBlock(
      final String json, final long line, final List<String> violations) {
    final String trimmed = json.strip();
    final boolean plan = trimmed.contains("\"scenarios\"");
    if (!plan && !SCENARIO_KEYS.matcher(trimmed).find()) {
      // A fragment (one selector, effect or policy object): its type names are checked with the
      // code spans; it cannot be parsed on its own.
      return;
    }
    final String document =
        plan ? trimmed : "{\"metadata\":{\"name\":\"doc\"},\"scenarios\":[" + trimmed + "]}";
    try {
      ChaosPlanMapper.read(document);
    } catch (final RuntimeException invalid) {
      violations.add(
          "json block at line "
              + line
              + " does not parse as a plan: "
              + invalid.getMessage()
              + "\n"
              + trimmed.lines().limit(3).collect(Collectors.joining("\n")));
    }
  }

  private static boolean hasMethod(final Class<?> type, final String name) {
    return Arrays.stream(type.getMethods()).map(Method::getName).anyMatch(name::equals);
  }

  private static boolean hasConstantField(final Class<?> type, final String name) {
    return Arrays.stream(type.getFields())
        .anyMatch(
            field ->
                field.getName().equals(name)
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers()));
  }

  private static boolean hasConstant(final Class<?> type, final String name) {
    return type.isEnum()
        && Arrays.stream(type.getEnumConstants()).anyMatch(c -> ((Enum<?>) c).name().equals(name));
  }

  private static Set<String> jsonTypeNames() {
    final Set<String> names = new HashSet<>();
    for (final Class<?> mixin : ApiJsonMixins.class.getDeclaredClasses()) {
      final JsonSubTypes subTypes = mixin.getAnnotation(JsonSubTypes.class);
      if (subTypes != null) {
        Arrays.stream(subTypes.value()).map(JsonSubTypes.Type::name).forEach(names::add);
      }
    }
    return names;
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("repository root not found above " + Path.of(""));
    }
    return current;
  }

  private static Optional<Path> nativeDsl() {
    // Opt-in only: a stale neighbouring checkout must not fail this repository's build.
    return Optional.ofNullable(System.getenv("CHAOS_DSL_PATH"))
        .or(() -> Optional.ofNullable(System.getProperty("chaos.dsl.path")))
        .map(Path::of);
  }
}

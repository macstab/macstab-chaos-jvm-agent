package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reference tables of the documentation list what the code defines, no more and no less. The
 * source is the published plan schema, which {@link PlanSchemaTest} derives from the plan mapper:
 * the selector and effect {@code type} names and the operations of each selector.
 */
@DisplayName("Reference tables match the plan model")
class ReferenceTablesSyncTest {

  private static final Pattern CODE = Pattern.compile("`([A-Za-z0-9_]+)`");

  /** Selector type name to its operations (empty for {@code stress}). */
  private static final Map<String, Set<String>> SELECTORS = new TreeMap<>();

  private static final Set<String> EFFECTS = new TreeSet<>();
  private static Path root;

  @BeforeAll
  static void readSchema() throws IOException {
    final JsonNode schema;
    try (InputStream in =
        ReferenceTablesSyncTest.class
            .getClassLoader()
            .getResourceAsStream(PlanSchemaTest.RESOURCE)) {
      schema = new ObjectMapper().readTree(in);
    }
    final JsonNode defs = schema.get("$defs");
    for (final JsonNode choice : defs.get("ChaosSelector").get("oneOf")) {
      final JsonNode selector = definition(defs, choice);
      final Set<String> operations = new TreeSet<>();
      selector
          .path("properties")
          .path("operations")
          .path("items")
          .path("enum")
          .forEach(operation -> operations.add(operation.asText()));
      SELECTORS.put(typeName(selector), operations);
    }
    for (final JsonNode choice : defs.get("ChaosEffect").get("oneOf")) {
      EFFECTS.add(typeName(definition(defs, choice)));
    }
    root = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(root.resolve("docs"))) {
      root = root.getParent();
    }
  }

  @Test
  @DisplayName("each selector's Operations in the configuration reference are its operations")
  void selectorOperations() throws IOException {
    final String reference = Files.readString(root.resolve("docs/configuration-reference.md"));
    final SoftAssertions softly = new SoftAssertions();
    for (final Map.Entry<String, Set<String>> selector : SELECTORS.entrySet()) {
      final String name = selector.getKey();
      final Set<String> documented = documentedOperations(reference, name);
      softly.assertThat(documented).as("selector %s", name).isNotNull();
      if (documented != null) {
        softly
            .assertThat(documented)
            .as("operations of selector %s", name)
            .isEqualTo(selector.getValue());
      }
    }
    softly.assertAll();
  }

  @Test
  @DisplayName("every effect has its section in the configuration reference")
  void effectSections() throws IOException {
    final Set<String> documented = new TreeSet<>();
    final Matcher heading =
        Pattern.compile("^#### (.*)$", Pattern.MULTILINE)
            .matcher(Files.readString(root.resolve("docs/configuration-reference.md")));
    while (heading.find()) {
      final Matcher name = CODE.matcher(heading.group(1));
      while (name.find()) {
        documented.add(name.group(1));
      }
    }
    assertThat(documented).containsAll(EFFECTS);
  }

  @Test
  @DisplayName("the type lists of the plan specification are the selector and effect types")
  void specificationTypeLists() throws IOException {
    final String spec = Files.readString(root.resolve("docs/plan-schema.md"));
    assertThat(codeNames(section(spec, "## 5. Selector types"))).isEqualTo(SELECTORS.keySet());
    assertThat(codeNames(section(spec, "## 6. Effect types"))).isEqualTo(EFFECTS);
  }

  @Test
  @DisplayName("every count of operation, selector and effect types in the docs is the code's")
  void statedCounts() throws IOException {
    final Map<String, Integer> actual =
        Map.of(
            "operation",
            (int)
                java.util.Arrays.stream(com.macstab.chaos.jvm.api.OperationType.values())
                    .filter(type -> type != com.macstab.chaos.jvm.api.OperationType.LIFECYCLE)
                    .count(),
            "selector",
            SELECTORS.size(),
            "effect",
            EFFECTS.size());
    final Pattern count = Pattern.compile("(?<!Phase )\\b(\\d+) (operation|selector|effect) types");
    final java.util.List<String> wrong = new java.util.ArrayList<>();
    int found = 0;
    final java.util.List<Path> documents = new java.util.ArrayList<>();
    documents.add(root.resolve("README.md"));
    try (var docs = Files.list(root.resolve("docs"))) {
      docs.filter(path -> path.toString().endsWith(".md")).sorted().forEach(documents::add);
    }
    for (final Path document : documents) {
      final Matcher claim = count.matcher(Files.readString(document));
      while (claim.find()) {
        found++;
        if (Integer.parseInt(claim.group(1)) != actual.get(claim.group(2))) {
          wrong.add(
              root.relativize(document)
                  + ": "
                  + claim.group()
                  + ", not "
                  + actual.get(claim.group(2)));
        }
      }
    }
    assertThat(found).as("counts stated in the docs").isPositive();
    assertThat(wrong).isEmpty();
  }

  @Test
  @DisplayName(
      "every count of the evaluation pipeline's steps in the docs is the semantics table's")
  void statedPipelineSteps() throws IOException {
    final String policy =
        section(
            Files.readString(root.resolve("docs/semantics.md")),
            "## 3. The policy of one scenario");
    final int steps =
        (int) policy.lines().filter(line -> line.matches("\\|\\s*\\d+\\s*\\|.*")).count();
    assertThat(steps).as("steps in semantics.md §3").isPositive();
    final Pattern count =
        Pattern.compile(
            "\\b(\\d+)(?:-(?:step|check|gate)| (?:steps|checks|gates)\\b)(?=[^.\\n]*pipeline|\\s*pass)");
    final java.util.List<String> wrong = new java.util.ArrayList<>();
    int found = 0;
    final java.util.List<Path> documents = new java.util.ArrayList<>();
    documents.add(root.resolve("README.md"));
    try (var docs = Files.list(root.resolve("docs"))) {
      docs.filter(path -> path.toString().endsWith(".md")).sorted().forEach(documents::add);
    }
    for (final Path document : documents) {
      final Matcher claim = count.matcher(Files.readString(document));
      while (claim.find()) {
        found++;
        if (Integer.parseInt(claim.group(1)) != steps) {
          wrong.add(root.relativize(document) + ": " + claim.group() + ", not " + steps);
        }
      }
    }
    assertThat(found).as("step counts stated in the docs").isPositive();
    assertThat(wrong).isEmpty();
  }

  /**
   * The operations the reference documents for a selector: the {@code **Operations:**} line of its
   * {@code ### `name`} section, or its row of the client-library table; {@code null} if neither.
   * The stress selector has no operations; its section must name none.
   */
  private static Set<String> documentedOperations(final String reference, final String name) {
    final Matcher heading =
        Pattern.compile("^### `" + name + "` — .*$", Pattern.MULTILINE).matcher(reference);
    if (heading.find()) {
      final String body = section(reference.substring(heading.start()), heading.group());
      final Matcher line =
          Pattern.compile("^\\*\\*Operations:\\*\\*(.*)$", Pattern.MULTILINE).matcher(body);
      return line.find() ? codeNames(line.group(1)) : Set.of();
    }
    final Matcher row =
        Pattern.compile("^\\| `" + name + "` +\\|[^|]*\\|([^|]*)\\|", Pattern.MULTILINE)
            .matcher(reference);
    return row.find() ? codeNames(row.group(1)) : null;
  }

  /** From {@code heading} to the next heading of the same or a higher level. */
  private static String section(final String text, final String heading) {
    final int start = text.indexOf(heading);
    assertThat(start).as("heading %s", heading).isNotNegative();
    final String level = heading.substring(0, heading.indexOf(' ') + 1);
    final Matcher next =
        Pattern.compile("^#{1," + (level.length() - 1) + "} ", Pattern.MULTILINE).matcher(text);
    final int bodyStart = start + heading.length();
    return text.substring(bodyStart, next.find(bodyStart) ? next.start() : text.length());
  }

  private static Set<String> codeNames(final String text) {
    final Set<String> names = new TreeSet<>();
    final Matcher name = CODE.matcher(text);
    while (name.find()) {
      names.add(name.group(1));
    }
    return names;
  }

  private static JsonNode definition(final JsonNode defs, final JsonNode reference) {
    return defs.get(reference.get("$ref").asText().substring("#/$defs/".length()));
  }

  private static String typeName(final JsonNode definition) {
    return definition.get("properties").get("type").get("const").asText();
  }

  @Test
  @DisplayName("the docs index links every document in docs/")
  void docsIndexIsComplete() throws IOException {
    final String index = Files.readString(root.resolve("docs/README.md"));
    final java.util.List<String> missing = new java.util.ArrayList<>();
    try (var docs = Files.list(root.resolve("docs"))) {
      docs.map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".md") && !name.equals("README.md"))
          .filter(name -> !index.contains("(" + name + ")"))
          .sorted()
          .forEach(missing::add);
    }
    assertThat(missing).as("documents not linked from docs/README.md").isEmpty();
  }
}

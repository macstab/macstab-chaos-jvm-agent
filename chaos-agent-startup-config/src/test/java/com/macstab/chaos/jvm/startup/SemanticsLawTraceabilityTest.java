package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every law of {@code docs/semantics.md} is verified by at least one test tagged {@code law-<id>}
 * ({@code @Tag("law-L14")}), and every such tag names a law that exists. A new law without a test,
 * or a test left pointing at a renumbered law, fails the build.
 */
@DisplayName("Every semantics law has a test")
class SemanticsLawTraceabilityTest {

  private static final Pattern LAW = Pattern.compile("^\\*\\*(L\\d+[a-z]?) — ", Pattern.MULTILINE);
  private static final Pattern TAG = Pattern.compile("@Tag\\(\"law-(L\\d+[a-z]?)\"\\)");

  @Test
  @DisplayName("each law has a tagged test and each tag names a law")
  void lawsAndTestsMatch() throws IOException {
    final Path root = repositoryRoot();
    final Set<String> laws = new TreeSet<>();
    final Matcher law = LAW.matcher(Files.readString(root.resolve("docs/semantics.md")));
    while (law.find()) {
      laws.add(law.group(1));
    }
    final Map<String, Set<String>> tests = taggedTests(root);

    assertThat(laws).as("laws found in docs/semantics.md").hasSizeGreaterThanOrEqualTo(22);
    SoftAssertions.assertSoftly(
        softly -> {
          softly
              .assertThat(laws)
              .as("laws without a test tagged law-<id>")
              .allMatch(tests::containsKey);
          softly.assertThat(tests.keySet()).as("law tags naming no law").allMatch(laws::contains);
        });
  }

  /** Law id to the test files that carry its tag, over every module's test sources. */
  private static Map<String, Set<String>> taggedTests(final Path root) throws IOException {
    final Map<String, Set<String>> tests = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (final Path file :
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> path.toString().contains("/src/test/java/"))
              .filter(path -> !path.toString().contains("/build/"))
              .toList()) {
        final Matcher tag = TAG.matcher(Files.readString(file));
        while (tag.find()) {
          tests
              .computeIfAbsent(tag.group(1), id -> new TreeSet<>())
              .add(root.relativize(file).toString());
        }
      }
    }
    return tests;
  }

  private static Path repositoryRoot() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isRegularFile(root.resolve("docs/semantics.md"))) {
      root = root.getParent();
    }
    assertThat(root).as("repository root with docs/semantics.md").isNotNull();
    return root;
  }
}

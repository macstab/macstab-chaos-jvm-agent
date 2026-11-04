package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * The fast paths of the patterns against their definitions. A GLOB is the regex in which '*' is
 * {@code .*} (no line terminator), '?' is one code point and every other character is literal; the
 * allocation-free matcher must agree with that regex on every candidate, including those it hands
 * to the regex (line terminators, surrogates). A path pattern matches a path exactly as it matches
 * the path's lexically normalised form.
 */
class PatternDifferentialTest {

  @Property(tries = 3000)
  void globMatchesLikeItsRegex(
      @ForAll("globs") final String glob, @ForAll("candidates") final String candidate) {
    assertThat(NamePattern.glob(glob).matches(candidate))
        .as("glob %s on %s", glob, escape(candidate))
        .isEqualTo(reference(glob).matcher(candidate).matches());
  }

  @Property(tries = 3000)
  void pathMatchesLikeItsNormalisedForm(
      @ForAll("paths") final String prefix, @ForAll("paths") final String path) {
    final PathPattern pattern;
    try {
      pattern = PathPattern.under(prefix);
    } catch (final IllegalArgumentException blank) {
      return;
    }
    assertThat(pattern.matches(path))
        .as("%s under %s", path, prefix)
        .isEqualTo(pattern.matches(PathPattern.normalize(path)));
    assertThat(PathPattern.normalize(PathPattern.normalize(path)))
        .as("normalising is idempotent")
        .isEqualTo(PathPattern.normalize(path));
  }

  @Test
  void regexPatternsBeyondTheCacheCapacityStillMatch() {
    // More distinct patterns than the cache holds: the ones past capacity are compiled each time
    // and must match all the same.
    for (int i = 0; i < 1_100; i++) {
      final NamePattern pattern = NamePattern.regex("svc-" + i + "-[a-z]+");
      assertThat(pattern.matches("svc-" + i + "-abc")).as("pattern %d", i).isTrue();
      assertThat(pattern.matches("svc-" + i + "-ABC")).as("pattern %d", i).isFalse();
    }
  }

  @Test
  void patternLengthIsBoundedAtItsMaximum() {
    assertThat(NamePattern.glob("a".repeat(4096)).matches("a".repeat(4096))).isTrue();
    assertThatThrownBy(() -> NamePattern.glob("a".repeat(4097)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── generators and the reference ─────────────────────────────────────────

  @Provide
  Arbitrary<String> globs() {
    return Arbitraries.strings().withChars("ab*?.+(\\$").ofMinLength(1).ofMaxLength(6);
  }

  @Provide
  Arbitrary<String> candidates() {
    return Arbitraries.strings()
        .withChars('a', 'b', '.', '+', '(', '\\', '$', '\n', '\r', '\u0085', ' ', ' ')
        .withChars("😀")
        .ofMaxLength(7);
  }

  @Provide
  Arbitrary<String> paths() {
    return Arbitraries.of("", "/", "a", "b", ".", "..", "\\")
        .list()
        .ofMaxSize(6)
        .map(segments -> String.join("/", segments))
        .map(path -> path.replace("/\\/", "\\"));
  }

  private static Pattern reference(final String glob) {
    final StringBuilder regex = new StringBuilder();
    for (final char c : glob.toCharArray()) {
      regex.append(
          switch (c) {
            case '*' -> ".*";
            // one code point: a surrogate pair or any other single char, a line terminator too
            case '?' -> "(?:[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]|[^\\uD800-\\uDFFF])";
            default -> Pattern.quote(String.valueOf(c));
          });
    }
    return Pattern.compile(regex.toString());
  }

  private static String escape(final String text) {
    final StringBuilder escaped = new StringBuilder();
    for (final char c : text.toCharArray()) {
      escaped.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c));
    }
    return escaped.toString();
  }
}

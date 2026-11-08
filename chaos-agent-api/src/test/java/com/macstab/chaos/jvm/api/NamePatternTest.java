package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.NamePattern.MatchMode;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("NamePattern")
class NamePatternTest {

  @Nested
  @DisplayName("ANY mode")
  class AnyMode {

    @Test
    @DisplayName("matches arbitrary string")
    void matchesArbitraryString() {
      assertThat(NamePattern.any().matches("anything.goes.Here")).isTrue();
    }

    @Test
    @DisplayName("matches null candidate")
    void matchesNullCandidate() {
      assertThat(NamePattern.any().matches(null)).isTrue();
    }

    @Test
    @DisplayName("matches empty string")
    void matchesEmptyString() {
      assertThat(NamePattern.any().matches("")).isTrue();
    }

    @Test
    @DisplayName("factory produces ANY mode")
    void factoryProducesAnyMode() {
      assertThat(NamePattern.any().mode()).isEqualTo(MatchMode.ANY);
    }

    @Test
    @DisplayName("null mode is normalised to ANY")
    void nullModeNormalisedToAny() {
      assertThat(new NamePattern(null, null).mode()).isEqualTo(MatchMode.ANY);
    }

    @Test
    @DisplayName("null value is normalised to '*'")
    void nullValueNormalisedToStar() {
      assertThat(new NamePattern(null, null).value()).isEqualTo("*");
    }
  }

  @Nested
  @DisplayName("EXACT mode")
  class ExactMode {

    @Test
    @DisplayName("matches identical string")
    void matchesIdenticalString() {
      assertThat(NamePattern.exact("java.sql.Connection").matches("java.sql.Connection")).isTrue();
    }

    @Test
    @DisplayName("does not match longer string")
    void doesNotMatchSubstring() {
      assertThat(NamePattern.exact("java.sql.Connection").matches("java.sql.Connection2"))
          .isFalse();
    }

    @Test
    @DisplayName("does not match prefix of value")
    void doesNotMatchPrefix() {
      assertThat(NamePattern.exact("java.sql").matches("java.sql.Connection")).isFalse();
    }

    @Test
    @DisplayName("does not match null candidate")
    void doesNotMatchNull() {
      assertThat(NamePattern.exact("x").matches(null)).isFalse();
    }

    @Test
    @DisplayName("factory produces EXACT mode")
    void factoryProducesExactMode() {
      assertThat(NamePattern.exact("foo").mode()).isEqualTo(MatchMode.EXACT);
    }

    @Test
    @DisplayName("blank value throws")
    void blankValueThrows() {
      assertThatThrownBy(() -> NamePattern.exact("   "))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty value throws")
    void emptyValueThrows() {
      assertThatThrownBy(() -> NamePattern.exact("")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("PREFIX mode")
  class PrefixMode {

    @Test
    @DisplayName("matches string starting with value")
    void matchesStringStartingWithValue() {
      assertThat(NamePattern.prefix("io.lettuce").matches("io.lettuce.core.RedisClient")).isTrue();
    }

    @Test
    @DisplayName("does not match unrelated string")
    void doesNotMatchUnrelatedString() {
      assertThat(NamePattern.prefix("io.lettuce").matches("com.example.Foo")).isFalse();
    }

    @Test
    @DisplayName("does not match null candidate")
    void doesNotMatchNull() {
      assertThat(NamePattern.prefix("io.lettuce").matches(null)).isFalse();
    }

    @Test
    @DisplayName("factory produces PREFIX mode")
    void factoryProducesPrefixMode() {
      assertThat(NamePattern.prefix("com.example").mode()).isEqualTo(MatchMode.PREFIX);
    }

    @Test
    @DisplayName("blank value throws")
    void blankValueThrows() {
      assertThatThrownBy(() -> NamePattern.prefix("")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("GLOB mode")
  class GlobMode {

    @Test
    @DisplayName("wildcard matches and non-match")
    void wildcardMatchesExpectedValue() {
      assertThat(
              NamePattern.glob("java.util.concurrent.*")
                  .matches("java.util.concurrent.ThreadPoolExecutor"))
          .isTrue();
      assertThat(NamePattern.glob("java.util.concurrent.*").matches("java.lang.Thread")).isFalse();
    }

    @Test
    @DisplayName("'*' matches empty suffix")
    void starMatchesEmptySuffix() {
      assertThat(NamePattern.glob("com.example.*").matches("com.example.")).isTrue();
    }

    @Test
    @DisplayName("'?' matches single character only")
    void questionMarkMatchesSingleCharacter() {
      assertThat(NamePattern.glob("worker-?").matches("worker-1")).isTrue();
      assertThat(NamePattern.glob("worker-?").matches("worker-10")).isFalse();
    }

    @Test
    @DisplayName("does not match null candidate")
    void doesNotMatchNull() {
      assertThat(NamePattern.glob("com.example.*").matches(null)).isFalse();
    }

    @Test
    @DisplayName("dot in glob is literal, not regex wildcard")
    void globEscapesDotInPattern() {
      assertThat(NamePattern.glob("com.example.Foo").matches("com.example.Foo")).isTrue();
      assertThat(NamePattern.glob("com.example.Foo").matches("comXexampleYFoo")).isFalse();
    }

    @Test
    @DisplayName("regex metacharacters '[' ']' '{' '}' in glob are literal, not regex syntax")
    void globEscapesCharacterClassAndRepetitionMetacharacters() {
      // Passed through verbatim to the regex compiler, these chars would make "class[0-9]*"
      // behave like the regex "class[0-9].*" \u2014 matching "class4",
      // which is wrong for a literal glob containing '[' and ']'.
      assertThat(NamePattern.glob("class[0-9]*").matches("class[0-9]foo")).isTrue();
      assertThat(NamePattern.glob("class[0-9]*").matches("class4")).isFalse();
      assertThat(NamePattern.glob("count{1,3}").matches("count{1,3}")).isTrue();
      assertThat(NamePattern.glob("count{1,3}").matches("counttt")).isFalse();
    }

    @Test
    @DisplayName("oversized glob is rejected at construction, not lazily at match time")
    void oversizedGlobRejectedAtConstruction() {
      final String tooLong = "a".repeat(4097);
      assertThatThrownBy(() -> NamePattern.glob(tooLong))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maximum length");
    }

    @Test
    @DisplayName("factory produces GLOB mode")
    void factoryProducesGlobMode() {
      assertThat(NamePattern.glob("com.example.*").mode()).isEqualTo(MatchMode.GLOB);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank value throws")
    void blankValueThrows(String value) {
      assertThatThrownBy(() -> NamePattern.glob(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("REGEX mode")
  class RegexMode {

    @Test
    @DisplayName("matches and non-match on digit pattern")
    void matchesExpectedValue() {
      assertThat(NamePattern.regex("worker-[0-9]+").matches("worker-42")).isTrue();
      assertThat(NamePattern.regex("worker-[0-9]+").matches("worker-x")).isFalse();
    }

    @Test
    @DisplayName("match is anchored to full string")
    void anchoredFullMatch() {
      assertThat(NamePattern.regex("worker").matches("worker-extra")).isFalse();
    }

    @Test
    @DisplayName("alternation is supported")
    void matchesAlternation() {
      assertThat(NamePattern.regex(".*Service(Impl)?").matches("FooServiceImpl")).isTrue();
      assertThat(NamePattern.regex(".*Service(Impl)?").matches("FooService")).isTrue();
      assertThat(NamePattern.regex(".*Service(Impl)?").matches("FooDao")).isFalse();
    }

    @Test
    @DisplayName("does not match null candidate")
    void doesNotMatchNull() {
      assertThat(NamePattern.regex(".*").matches(null)).isFalse();
    }

    @Test
    @DisplayName("factory produces REGEX mode")
    void factoryProducesRegexMode() {
      assertThat(NamePattern.regex(".*").mode()).isEqualTo(MatchMode.REGEX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank value throws")
    void blankValueThrows(String value) {
      assertThatThrownBy(() -> NamePattern.regex(value))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("oversized regex is rejected at construction, not lazily at match time")
    void oversizedRegexRejectedAtConstruction() {
      final String tooLong = "a".repeat(4097);
      assertThatThrownBy(() -> NamePattern.regex(tooLong))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maximum length");
    }
  }

  @Nested
  @DisplayName("null candidate edge cases")
  class NullCandidateEdgeCases {

    @Test
    @DisplayName("all non-ANY modes return false for null candidate")
    void nonAnyModesReturnFalseForNullCandidate() {
      assertThat(NamePattern.exact("x").matches(null)).isFalse();
      assertThat(NamePattern.prefix("x").matches(null)).isFalse();
      assertThat(NamePattern.glob("x*").matches(null)).isFalse();
      assertThat(NamePattern.regex("x.*").matches(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("factory methods")
  class FactoryMethods {

    @Test
    @DisplayName("all factory methods return NamePattern instances")
    void factoryMethodsReturnNamePatternInstances() {
      assertThat(NamePattern.any()).isInstanceOf(NamePattern.class);
      assertThat(NamePattern.exact("foo")).isInstanceOf(NamePattern.class);
      assertThat(NamePattern.prefix("foo")).isInstanceOf(NamePattern.class);
      assertThat(NamePattern.glob("foo*")).isInstanceOf(NamePattern.class);
      assertThat(NamePattern.regex("foo.*")).isInstanceOf(NamePattern.class);
    }
  }

  @Nested
  @DisplayName("constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("ANY mode accepts blank value")
    void anyModeAcceptsBlankValue() {
      NamePattern pattern = new NamePattern(MatchMode.ANY, "");
      assertThat(pattern.mode()).isEqualTo(MatchMode.ANY);
    }

    @Test
    @DisplayName("ANY keeps '*' and a blank value, as the singleton and written plans carry them")
    void anyModeAcceptsStar() {
      assertThat(new NamePattern(MatchMode.ANY, "*")).isEqualTo(NamePattern.any());
      assertThat(new NamePattern(MatchMode.ANY, null)).isEqualTo(NamePattern.any());
      assertThat(new NamePattern(MatchMode.ANY, "")).isEqualTo(NamePattern.any());
    }

    @Test
    @DisplayName("ANY refuses any other value instead of dropping it")
    void anyModeRefusesValue() {
      assertThatThrownBy(() -> new NamePattern(MatchMode.ANY, "x"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a value without a mode is refused instead of matching everything")
    void valueWithoutMode() {
      assertThatThrownBy(() -> new NamePattern(null, "com.example.*"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a mode without a value is refused instead of matching everything")
    void modeWithoutValue() {
      assertThatThrownBy(() -> NamePattern.glob(null)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> NamePattern.exact(null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> NamePattern.prefix(null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> NamePattern.regex(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("non-ANY mode rejects blank value")
    void nonAnyModeRejectsBlankValue() {
      assertThatThrownBy(() -> new NamePattern(MatchMode.EXACT, "   "))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new NamePattern(MatchMode.PREFIX, ""))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new NamePattern(MatchMode.GLOB, ""))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new NamePattern(MatchMode.REGEX, ""))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("GLOB matching without regex")
  class GlobEquivalence {

    @Test
    @DisplayName("agrees with the regex the glob compiles to on random globs and candidates")
    void agreesWithRegex() {
      final SplittableRandom random = new SplittableRandom(42L);
      for (int round = 0; round < 20_000; round++) {
        final String glob = randomString(random, "ab.*?\\", 1 + random.nextInt(7));
        if (glob.isBlank()) {
          continue;
        }
        final String candidate = randomString(random, "ab.x\\\n\u2028", random.nextInt(9));
        assertThat(NamePattern.glob(glob).matches(candidate))
            .as("glob %s against %s", glob, candidate)
            .isEqualTo(referenceRegex(glob).matcher(candidate).matches());
      }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\u2028b", "a\uD83D\uDE00b", "\uD83D"})
    @DisplayName(
        "'*' and '?' match line terminators; '?' matches a surrogate pair as one character")
    void specialCandidates(final String candidate) {
      for (final String glob : new String[] {"a*b", "a?b", "*", "?", "a*", "*b"}) {
        assertThat(NamePattern.glob(glob).matches(candidate))
            .as("glob %s against %s", glob, candidate)
            .isEqualTo(referenceRegex(glob).matcher(candidate).matches());
      }
    }

    private static String randomString(
        final SplittableRandom random, final String alphabet, final int length) {
      final StringBuilder builder = new StringBuilder(length);
      for (int i = 0; i < length; i++) {
        builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      return builder.toString();
    }

    /**
     * The definition of the GLOB semantics: the DOTALL regex in which '*' is {@code .*} and '?' is
     * {@code .}, which Java reads as one code point.
     */
    private static Pattern referenceRegex(final String glob) {
      final StringBuilder builder = new StringBuilder("^");
      for (int i = 0; i < glob.length(); i++) {
        final char c = glob.charAt(i);
        switch (c) {
          case '*' -> builder.append(".*");
          case '?' -> builder.append('.');
          default -> builder.append(Pattern.quote(String.valueOf(c)));
        }
      }
      return Pattern.compile(builder.append('$').toString(), Pattern.DOTALL);
    }
  }

  @Nested
  @DisplayName("regex cache")
  class RegexCache {

    @Test
    @DisplayName("is cleared when full, then caches the new expression")
    void clearedWhenFull() {
      // A test-owned cache filled to the capacity of the JVM-wide one (1024).
      final java.util.Map<String, java.util.regex.Pattern> cache =
          new java.util.concurrent.ConcurrentHashMap<>();
      for (int i = 0; i < 1024; i++) {
        cache.put("filler-" + i, java.util.regex.Pattern.compile("filler-" + i));
      }

      final java.util.regex.Pattern first = NamePattern.cachedCompile(cache, "new.*", "new.*");
      final java.util.regex.Pattern second = NamePattern.cachedCompile(cache, "new.*", "new.*");

      assertThat(second).isSameAs(first);
      assertThat(cache).hasSize(1);
    }
  }
}

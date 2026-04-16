package io.macstab.chaos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.macstab.chaos.api.NamePattern.MatchMode;
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
}

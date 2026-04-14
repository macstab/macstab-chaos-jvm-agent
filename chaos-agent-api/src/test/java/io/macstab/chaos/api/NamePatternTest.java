package io.macstab.chaos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.macstab.chaos.api.NamePattern.MatchMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamePatternTest {

  // ── ANY mode ──────────────────────────────────────────────────────────────

  @Test
  void anyMatchesArbitraryString() {
    assertTrue(NamePattern.any().matches("anything.goes.Here"));
  }

  @Test
  void anyMatchesNullCandidate() {
    assertTrue(NamePattern.any().matches(null));
  }

  @Test
  void anyMatchesEmptyString() {
    assertTrue(NamePattern.any().matches(""));
  }

  @Test
  void anyFactoryProducesCorrectMode() {
    assertEquals(MatchMode.ANY, NamePattern.any().mode());
  }

  @Test
  void nullModeNormalisedToAny() {
    NamePattern pattern = new NamePattern(null, null);
    assertEquals(MatchMode.ANY, pattern.mode());
  }

  @Test
  void nullValueNormalisedToStar() {
    NamePattern pattern = new NamePattern(null, null);
    assertEquals("*", pattern.value());
  }

  // ── EXACT mode ────────────────────────────────────────────────────────────

  @Test
  void exactMatchesIdenticalString() {
    assertTrue(NamePattern.exact("java.sql.Connection").matches("java.sql.Connection"));
  }

  @Test
  void exactDoesNotMatchSubstring() {
    assertFalse(NamePattern.exact("java.sql.Connection").matches("java.sql.Connection2"));
  }

  @Test
  void exactDoesNotMatchPrefix() {
    assertFalse(NamePattern.exact("java.sql").matches("java.sql.Connection"));
  }

  @Test
  void exactDoesNotMatchNull() {
    assertFalse(NamePattern.exact("x").matches(null));
  }

  @Test
  void exactFactoryProducesCorrectMode() {
    assertEquals(MatchMode.EXACT, NamePattern.exact("foo").mode());
  }

  @Test
  void exactBlankValueThrows() {
    assertThrows(IllegalArgumentException.class, () -> NamePattern.exact("   "));
  }

  @Test
  void exactEmptyValueThrows() {
    assertThrows(IllegalArgumentException.class, () -> NamePattern.exact(""));
  }

  // ── PREFIX mode ───────────────────────────────────────────────────────────

  @Test
  void prefixMatchesStringStartingWithValue() {
    assertTrue(NamePattern.prefix("io.lettuce").matches("io.lettuce.core.RedisClient"));
  }

  @Test
  void prefixDoesNotMatchUnrelatedString() {
    assertFalse(NamePattern.prefix("io.lettuce").matches("com.example.Foo"));
  }

  @Test
  void prefixDoesNotMatchNull() {
    assertFalse(NamePattern.prefix("io.lettuce").matches(null));
  }

  @Test
  void prefixFactoryProducesCorrectMode() {
    assertEquals(MatchMode.PREFIX, NamePattern.prefix("com.example").mode());
  }

  @Test
  void prefixBlankValueThrows() {
    assertThrows(IllegalArgumentException.class, () -> NamePattern.prefix(""));
  }

  // ── GLOB mode ─────────────────────────────────────────────────────────────

  @Test
  void globMatchesExpectedValue() {
    assertTrue(
        NamePattern.glob("java.util.concurrent.*")
            .matches("java.util.concurrent.ThreadPoolExecutor"));
    assertFalse(NamePattern.glob("java.util.concurrent.*").matches("java.lang.Thread"));
  }

  @Test
  void globStarMatchesEmptySuffix() {
    assertTrue(NamePattern.glob("com.example.*").matches("com.example."));
  }

  @Test
  void globQuestionMarkMatchesSingleCharacter() {
    assertTrue(NamePattern.glob("worker-?").matches("worker-1"));
    assertFalse(NamePattern.glob("worker-?").matches("worker-10"));
  }

  @Test
  void globDoesNotMatchNull() {
    assertFalse(NamePattern.glob("com.example.*").matches(null));
  }

  @Test
  void globEscapesDotInPattern() {
    assertTrue(NamePattern.glob("com.example.Foo").matches("com.example.Foo"));
    assertFalse(NamePattern.glob("com.example.Foo").matches("comXexampleYFoo"));
  }

  @Test
  void globFactoryProducesCorrectMode() {
    assertEquals(MatchMode.GLOB, NamePattern.glob("com.example.*").mode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void globBlankValueThrows(String value) {
    assertThrows(IllegalArgumentException.class, () -> NamePattern.glob(value));
  }

  // ── REGEX mode ────────────────────────────────────────────────────────────

  @Test
  void regexMatchesExpectedValue() {
    assertTrue(NamePattern.regex("worker-[0-9]+").matches("worker-42"));
    assertFalse(NamePattern.regex("worker-[0-9]+").matches("worker-x"));
  }

  @Test
  void regexAnchoredFullMatch() {
    assertFalse(NamePattern.regex("worker").matches("worker-extra"));
  }

  @Test
  void regexMatchesAlternation() {
    assertTrue(NamePattern.regex(".*Service(Impl)?").matches("FooServiceImpl"));
    assertTrue(NamePattern.regex(".*Service(Impl)?").matches("FooService"));
    assertFalse(NamePattern.regex(".*Service(Impl)?").matches("FooDao"));
  }

  @Test
  void regexDoesNotMatchNull() {
    assertFalse(NamePattern.regex(".*").matches(null));
  }

  @Test
  void regexFactoryProducesCorrectMode() {
    assertEquals(MatchMode.REGEX, NamePattern.regex(".*").mode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void regexBlankValueThrows(String value) {
    assertThrows(IllegalArgumentException.class, () -> NamePattern.regex(value));
  }

  // ── null candidate edge cases ─────────────────────────────────────────────

  @Test
  void nonAnyModesReturnFalseForNullCandidate() {
    assertFalse(NamePattern.exact("x").matches(null));
    assertFalse(NamePattern.prefix("x").matches(null));
    assertFalse(NamePattern.glob("x*").matches(null));
    assertFalse(NamePattern.regex("x.*").matches(null));
  }

  // ── factory methods return correct instances ──────────────────────────────

  @Test
  void factoryMethodsReturnNamePatternInstances() {
    assertInstanceOf(NamePattern.class, NamePattern.any());
    assertInstanceOf(NamePattern.class, NamePattern.exact("foo"));
    assertInstanceOf(NamePattern.class, NamePattern.prefix("foo"));
    assertInstanceOf(NamePattern.class, NamePattern.glob("foo*"));
    assertInstanceOf(NamePattern.class, NamePattern.regex("foo.*"));
  }

  // ── constructor validation ─────────────────────────────────────────────────

  @Test
  void constructorWithAnyModeAcceptsBlankValue() {
    NamePattern pattern = new NamePattern(MatchMode.ANY, "");
    assertEquals(MatchMode.ANY, pattern.mode());
  }

  @Test
  void constructorWithNonAnyModeRejectsBlankValue() {
    assertThrows(
        IllegalArgumentException.class, () -> new NamePattern(MatchMode.EXACT, "   "));
    assertThrows(
        IllegalArgumentException.class, () -> new NamePattern(MatchMode.PREFIX, ""));
    assertThrows(
        IllegalArgumentException.class, () -> new NamePattern(MatchMode.GLOB, ""));
    assertThrows(
        IllegalArgumentException.class, () -> new NamePattern(MatchMode.REGEX, ""));
  }
}

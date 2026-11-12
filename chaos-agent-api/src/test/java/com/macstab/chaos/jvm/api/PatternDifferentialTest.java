package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * The fast paths of the patterns against their definitions (the GLOB matcher's is {@code
 * NamePatternTest.GlobEquivalence}). A path pattern matches a path exactly as it matches the path's
 * lexically normalised form.
 */
class PatternDifferentialTest {

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
  Arbitrary<String> paths() {
    return Arbitraries.of("", "/", "a", "b", ".", "..", "\\")
        .list()
        .ofMaxSize(6)
        .map(segments -> String.join("/", segments))
        .map(path -> path.replace("/\\/", "\\"));
  }
}

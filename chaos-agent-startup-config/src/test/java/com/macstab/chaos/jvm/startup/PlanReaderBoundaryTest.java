package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * The plan reader's limits and its failure reports, at their exact boundaries: the size cap counts
 * UTF-8 bytes (checked against an independent count over code points), a plan of exactly the cap is
 * read and one byte more is not, and every kind of failure is reported with its position and its
 * kind.
 */
class PlanReaderBoundaryTest {

  private static final String SCENARIO =
      "{\"id\": \"s\", \"selector\": {\"type\": \"executor\", \"operations\": [\"EXECUTOR_SUBMIT\"]},"
          + " \"effect\": {\"type\": \"suppress\"}}";

  @Property(tries = 2000)
  void byteCountIsTheUtf8Length(
      @ForAll("texts") final String text, @ForAll @IntRange(min = 1, max = 64) final int cap) {
    final int exact = utf8Length(text);
    final int counted = ChaosPlanMapper.utf8ByteLengthCapped(text, cap);
    if (exact < cap) {
      assertThat(counted).as("exact below the cap").isEqualTo(exact);
    } else {
      assertThat(counted).as("at least the cap once it is reached").isGreaterThanOrEqualTo(cap);
    }
    assertThat(ChaosPlanMapper.utf8ByteLengthCapped(text, Integer.MAX_VALUE)).isEqualTo(exact);
  }

  @Test
  void aPlanOfExactlyTheMaximumSizeIsRead() {
    final String plan = "{\"scenarios\": [" + SCENARIO + "]}";
    final String padded = plan + " ".repeat(ChaosPlanMapper.MAX_JSON_LENGTH - plan.length());

    assertThat(ChaosPlanMapper.read(padded).scenarios()).hasSize(1);
    assertThatThrownBy(() -> ChaosPlanMapper.read(padded + " "))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("exceeds maximum size");
    // Counted in bytes, not chars: one two-byte character in place of a space crosses the cap.
    assertThatThrownBy(() -> ChaosPlanMapper.read(padded.substring(1) + "é"))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("exceeds maximum size");
  }

  @Test
  void eachFailureKindIsReportedWithItsPosition() {
    assertFailure(
        "{\"scenarios\": [\n" + SCENARIO.replace("{\"id\"", "{\"precedenc\": 1, \"id\"") + "]}",
        2,
        "unknown property 'precedenc' at scenarios[0]");
    assertFailure(
        "{\"scenarios\": [" + SCENARIO.replace("\"suppress\"", "\"nope\"") + "]}",
        1,
        "unknown or missing type at scenarios[0].effect");
    assertFailure(
        "{\"scenarios\": ["
            + SCENARIO.replace("\"suppress\"}", "\"trickle\", \"maxBytes\": 0}")
            + "]}",
        1,
        "invalid value: maxBytes must be >= 1, got 0 at scenarios[0].effect");
    assertFailure(
        "{\"scenarios\": [" + SCENARIO.replace("[\"EXECUTOR_SUBMIT\"]", "7") + "]}",
        1,
        "value of the wrong type at scenarios[0].selector.operations");
    assertFailure("{\"scenarios\": [\n\n" + SCENARIO + ",]}", 3, "malformed JSON at scenarios");
  }

  private static void assertFailure(final String json, final int line, final String reason) {
    assertThatThrownBy(() -> ChaosPlanMapper.read(json))
        .isInstanceOfSatisfying(
            ConfigLoadException.class,
            failure -> {
              assertThat(failure.line()).as("line").isEqualTo(line);
              assertThat(failure.column()).as("column").isPositive();
              assertThat(failure.reason()).isEqualTo(reason);
              assertThat(failure.getMessage())
                  .isEqualTo(
                      "failed to parse chaos plan JSON at line "
                          + line
                          + ", column "
                          + failure.column()
                          + ": "
                          + reason);
            });
  }

  @Provide
  Arbitrary<String> texts() {
    return Arbitraries.strings()
        .withChars('a', '{', '\u007f', '\u0080', '߿', 'ࠀ', '￿', '\uD83D', '\uDE00')
        .ofMaxLength(20);
  }

  /** The UTF-8 length over code points; a lone surrogate counts as its replacement, 3 bytes. */
  private static int utf8Length(final String text) {
    return text.codePoints().map(cp -> cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4).sum();
  }
}

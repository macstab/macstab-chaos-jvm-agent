package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LogText")
class LogTextTest {

  @Nested
  @DisplayName("characters that end or forge a line")
  class LineBreakers {

    @Test
    @DisplayName("CR, LF, NUL, TAB and the other C0 controls become U+FFFD")
    void c0Controls() {
      assertThat(LogText.sanitize("/etc/plan.json\n2026 FORGED\r\u0000"))
          .isEqualTo("/etc/plan.json�2026 FORGED��");
      assertThat(LogText.sanitize("a\tb")).isEqualTo("a�b");
      assertThat(LogText.sanitize("a\u0001b\u001bc")).isEqualTo("a�b�c");
    }

    @Test
    @DisplayName("DEL, the C1 controls (NEL), U+2028 and U+2029 become U+FFFD")
    void delC1AndUnicodeSeparators() {
      assertThat(LogText.sanitize("a\u007fb\u0080c\u0085d\u009fe f g")).isEqualTo("a�b�c�d�e�f�g");
    }

    @Test
    @DisplayName("printable text, including non-ASCII, is kept")
    void printableTextKept() {
      assertThat(LogText.sanitize("plan été   😀")).isEqualTo("plan été   😀");
    }
  }

  @Nested
  @DisplayName("length")
  class Length {

    @Test
    @DisplayName("is capped at 512 characters with a marker")
    void defaultCap() {
      assertThat(LogText.sanitize("x".repeat(600)))
          .isEqualTo("x".repeat(LogText.MAX_LENGTH) + "...[truncated]");
      assertThat(LogText.sanitize("x".repeat(LogText.MAX_LENGTH)))
          .isEqualTo("x".repeat(LogText.MAX_LENGTH));
    }

    @Test
    @DisplayName("takes a caller's cap")
    void callerCap() {
      assertThat(LogText.sanitize("abcdef", 3)).isEqualTo("abc...[truncated]");
      assertThat(LogText.sanitize("abc", 3)).isEqualTo("abc");
    }

    @Test
    @DisplayName("never splits a surrogate pair at the cap")
    void surrogatePairKept() {
      assertThat(LogText.sanitize("ab😀cd", 3)).isEqualTo("ab...[truncated]");
      assertThat(LogText.sanitize("ab😀cd", 4)).isEqualTo("ab😀...[truncated]");
    }
  }

  @Test
  @DisplayName("null or empty text is (no message)")
  void noMessage() {
    assertThat(LogText.sanitize(null)).isEqualTo("(no message)");
    assertThat(LogText.sanitize("")).isEqualTo("(no message)");
  }
}

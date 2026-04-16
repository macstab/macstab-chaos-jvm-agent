package com.macstab.chaos.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AgentArgsParser")
class AgentArgsParserTest {

  @Nested
  @DisplayName("valid input")
  class ValidInput {

    @Test
    @DisplayName("null input yields empty args")
    void nullInput() {
      AgentArgs args = AgentArgsParser.parse(null);
      assertThat(args.values()).isEmpty();
    }

    @Test
    @DisplayName("blank input yields empty args")
    void blankInput() {
      AgentArgs args = AgentArgsParser.parse("   ");
      assertThat(args.values()).isEmpty();
    }

    @Test
    @DisplayName("single key=value pair")
    void singlePair() {
      AgentArgs args = AgentArgsParser.parse("configFile=/tmp/plan.json");
      assertThat(args.get("configFile")).isEqualTo("/tmp/plan.json");
    }

    @Test
    @DisplayName("multiple key=value pairs separated by semicolons")
    void multiplePairs() {
      AgentArgs args = AgentArgsParser.parse("configFile=/tmp/plan.json;debugDump=true");
      assertThat(args.get("configFile")).isEqualTo("/tmp/plan.json");
      assertThat(args.get("debugDump")).isEqualTo("true");
    }

    @Test
    @DisplayName("trailing semicolon is tolerated")
    void trailingSemicolon() {
      AgentArgs args = AgentArgsParser.parse("key=value;");
      assertThat(args.get("key")).isEqualTo("value");
    }

    @Test
    @DisplayName("whitespace around keys and values is trimmed")
    void whitespaceTrimmed() {
      AgentArgs args = AgentArgsParser.parse("  key  =  value  ");
      assertThat(args.get("key")).isEqualTo("value");
    }

    @Test
    @DisplayName("empty value is permitted")
    void emptyValue() {
      AgentArgs args = AgentArgsParser.parse("key=");
      assertThat(args.get("key")).isEmpty();
    }

    @Test
    @DisplayName("value containing equals sign is preserved")
    void equalsInValue() {
      AgentArgs args = AgentArgsParser.parse("json={\"a\"=\"b\"}");
      assertThat(args.get("json")).isEqualTo("{\"a\"=\"b\"}");
    }
  }

  @Nested
  @DisplayName("escaping")
  class Escaping {

    @Test
    @DisplayName("escaped semicolon is literal")
    void escapedSemicolon() {
      AgentArgs args = AgentArgsParser.parse("key=val\\;ue");
      assertThat(args.get("key")).isEqualTo("val;ue");
    }

    @Test
    @DisplayName("escaped backslash is literal")
    void escapedBackslash() {
      AgentArgs args = AgentArgsParser.parse("path=C\\\\temp");
      assertThat(args.get("path")).isEqualTo("C\\temp");
    }

    @Test
    @DisplayName("escaped equals sign is literal in value")
    void escapedEquals() {
      AgentArgs args = AgentArgsParser.parse("key=a\\=b");
      assertThat(args.get("key")).isEqualTo("a=b");
    }
  }

  @Nested
  @DisplayName("error handling")
  class ErrorHandling {

    @Test
    @DisplayName("trailing backslash throws with position")
    void trailingBackslash() {
      assertThatThrownBy(() -> AgentArgsParser.parse("key=value\\"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("trailing backslash");
    }

    @Test
    @DisplayName("missing equals sign throws")
    void missingEquals() {
      assertThatThrownBy(() -> AgentArgsParser.parse("justAKey"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expected key=value");
    }

    @Test
    @DisplayName("empty key throws")
    void emptyKey() {
      assertThatThrownBy(() -> AgentArgsParser.parse("=value"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expected key=value");
    }

    @Test
    @DisplayName("oversized input throws")
    void oversizedInput() {
      String huge = "k=" + "x".repeat(9000);
      assertThatThrownBy(() -> AgentArgsParser.parse(huge))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maximum length");
    }
  }

  @Nested
  @DisplayName("duplicate keys")
  class DuplicateKeys {

    @Test
    @DisplayName("last value wins on duplicate keys")
    void lastValueWins() {
      AgentArgs args = AgentArgsParser.parse("key=first;key=second");
      assertThat(args.get("key")).isEqualTo("second");
    }
  }

  @Nested
  @DisplayName("AgentArgs boolean accessor")
  class BooleanAccessor {

    @Test
    @DisplayName("'true' yields true")
    void trueValue() {
      AgentArgs args = AgentArgsParser.parse("flag=true");
      assertThat(args.getBoolean("flag", false)).isTrue();
    }

    @Test
    @DisplayName("'TRUE' yields true (case-insensitive)")
    void trueCaseInsensitive() {
      AgentArgs args = AgentArgsParser.parse("flag=TRUE");
      assertThat(args.getBoolean("flag", false)).isTrue();
    }

    @Test
    @DisplayName("'false' yields false")
    void falseValue() {
      AgentArgs args = AgentArgsParser.parse("flag=false");
      assertThat(args.getBoolean("flag", true)).isFalse();
    }

    @Test
    @DisplayName("typo like 'tru' yields default")
    void typoYieldsDefault() {
      AgentArgs args = AgentArgsParser.parse("flag=tru");
      assertThat(args.getBoolean("flag", true)).isTrue();
      assertThat(args.getBoolean("flag", false)).isFalse();
    }

    @Test
    @DisplayName("missing key yields default")
    void missingKeyYieldsDefault() {
      AgentArgs args = AgentArgsParser.parse("other=value");
      assertThat(args.getBoolean("flag", true)).isTrue();
    }
  }
}

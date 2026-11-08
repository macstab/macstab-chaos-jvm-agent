package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
    @DisplayName("a trailing backslash is kept literally")
    void trailingBackslash() {
      assertThat(AgentArgsParser.parse("key=value\\").get("key")).isEqualTo("value\\");
    }

    @Test
    @DisplayName("a bare key is a boolean flag, not an error that aborts the JVM")
    void bareKeyIsFlag() {
      assertThat(AgentArgsParser.parse("debugDumpOnStart").getBoolean("debugDumpOnStart", false))
          .isTrue();
    }

    @Test
    @DisplayName("backslashes of a Windows path are kept")
    void windowsPath() {
      assertThat(AgentArgsParser.parse("configFile=C:\\chaos\\plan.json").get("configFile"))
          .isEqualTo("C:\\chaos\\plan.json");
    }

    @Test
    @DisplayName("JSON escapes in an inline plan are kept, an escaped separator is unescaped")
    void jsonEscapesAndSeparator() {
      final AgentArgs args =
          AgentArgsParser.parse("configJson={\"p\":\"a\\\"b\\;c\"};debugDump=true");
      assertThat(args.get("configJson")).isEqualTo("{\"p\":\"a\\\"b;c\"}");
      assertThat(args.get("debugDump")).isEqualTo("true");
    }

    @Test
    @DisplayName("empty key throws")
    void emptyKey() {
      assertThatThrownBy(() -> AgentArgsParser.parse("=value"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expected key=value");
    }

    @Test
    @DisplayName("a key of only whitespace throws instead of being stored under no name")
    void blankKey() {
      assertThatThrownBy(() -> AgentArgsParser.parse("  =value"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("invalid agent arg token '  =value': blank key before '='");
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

    @Test
    @DisplayName("blank value yields default")
    void blankValueYieldsDefault() {
      AgentArgs args = AgentArgsParser.parse("flag=  ");
      assertThat(args.getBoolean("flag", true)).isTrue();
      assertThat(args.getBoolean("flag", false)).isFalse();
    }

    @Test
    @DisplayName("unrecognised value warns on stderr with the key and the literal")
    void typoWarns() {
      AgentArgs args = AgentArgsParser.parse("flag=tru");
      assertThat(stderrOf(() -> args.getBoolean("flag", false)))
          .contains("'flag'")
          .contains("'tru'")
          .contains("Using default: false");
    }

    @Test
    @DisplayName("the quiet parser is tri-state and prints nothing")
    void quietParser() {
      final String printed =
          stderrOf(
              () -> {
                assertThat(AgentArgs.parseBooleanQuietly(" On ")).isTrue();
                assertThat(AgentArgs.parseBooleanQuietly("0")).isFalse();
                assertThat(AgentArgs.parseBooleanQuietly("tru")).isNull();
                assertThat(AgentArgs.parseBooleanQuietly(" ")).isNull();
                assertThat(AgentArgs.parseBooleanQuietly(null)).isNull();
              });
      assertThat(printed).isEmpty();
    }
  }

  @Nested
  @DisplayName("AgentArgs long accessor")
  class LongAccessor {

    @Test
    @DisplayName("parses the value with surrounding whitespace stripped")
    void parsesStripped() {
      assertThat(new AgentArgs(Map.of("n", " 42 ")).getLong("n", 7)).isEqualTo(42L);
    }

    @Test
    @DisplayName("missing, blank or non-numeric values yield default")
    void fallsBackToDefault() {
      AgentArgs args = new AgentArgs(Map.of("blank", "  ", "text", "forty-two"));
      assertThat(args.getLong("missing", 7)).isEqualTo(7L);
      assertThat(args.getLong("blank", 7)).isEqualTo(7L);
      assertThat(args.getLong("text", 7)).isEqualTo(7L);
    }
  }

  @Nested
  @DisplayName("duplicate key warnings")
  class DuplicateKeyWarnings {

    @Test
    @DisplayName("a repeated key=value warns on stderr, distinct keys do not")
    void repeatedPairWarns() {
      assertThat(stderrOf(() -> AgentArgsParser.parse("key=first;key=second")))
          .contains("duplicate agent arg key 'key'");
      assertThat(stderrOf(() -> AgentArgsParser.parse("a=1;b=2"))).isEmpty();
    }

    @Test
    @DisplayName("a repeated bare flag warns on stderr, a single flag does not")
    void repeatedFlagWarns() {
      assertThat(stderrOf(() -> AgentArgsParser.parse("debugDump;debugDump")))
          .contains("duplicate agent arg key 'debugDump'");
      assertThat(stderrOf(() -> AgentArgsParser.parse("debugDump;other=1"))).isEmpty();
    }
  }

  @Test
  @DisplayName("input of exactly the maximum length is accepted")
  void inputAtMaximumLengthAccepted() {
    String atLimit = "k=" + "x".repeat(8192 - 2);
    assertThat(AgentArgsParser.parse(atLimit).get("k")).hasSize(8190);
  }

  private static String stderrOf(final Runnable action) {
    final PrintStream original = System.err;
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}

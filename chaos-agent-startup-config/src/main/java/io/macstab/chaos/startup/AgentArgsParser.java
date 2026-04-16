package io.macstab.chaos.startup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the raw {@code -javaagent:} argument string into an {@link AgentArgs} value object.
 *
 * <p>Format: semicolon-separated {@code key=value} pairs. Backslash escapes the next character (use
 * {@code \\} for a literal backslash, {@code \;} for a literal semicolon, {@code \=} for a literal
 * equals sign inside a value).
 *
 * <p>Whitespace around keys and values is trimmed. Blank tokens between semicolons are silently
 * skipped. Duplicate keys emit a warning to stderr and the last value wins.
 *
 * <p>Example: {@code configFile=/tmp/plan.json;debugDump=true}
 */
public final class AgentArgsParser {

  private static final int MAX_ARG_LENGTH = 8192;

  private AgentArgsParser() {}

  /**
   * Parses the raw agent argument string.
   *
   * @param agentArgs the raw string from {@code -javaagent:agent.jar=<agentArgs>}, may be null
   * @return parsed arguments, never null
   * @throws IllegalArgumentException if a token is malformed or the input exceeds safety limits
   */
  public static AgentArgs parse(final String agentArgs) {
    if (agentArgs == null || agentArgs.isBlank()) {
      return new AgentArgs(Map.of());
    }
    if (agentArgs.length() > MAX_ARG_LENGTH) {
      throw new IllegalArgumentException(
          "agent args exceed maximum length of " + MAX_ARG_LENGTH + " characters");
    }

    final Map<String, String> values = new LinkedHashMap<>();
    final StringBuilder current = new StringBuilder();
    boolean escaped = false;

    for (int i = 0; i < agentArgs.length(); i++) {
      final char ch = agentArgs.charAt(i);
      if (escaped) {
        current.append(ch);
        escaped = false;
        continue;
      }
      if (ch == '\\') {
        if (i == agentArgs.length() - 1) {
          throw new IllegalArgumentException(
              "trailing backslash at position " + i + " in agent args");
        }
        escaped = true;
        continue;
      }
      if (ch == ';') {
        addEntry(values, current.toString());
        current.setLength(0);
        continue;
      }
      current.append(ch);
    }
    addEntry(values, current.toString());
    return new AgentArgs(values);
  }

  private static void addEntry(final Map<String, String> target, final String token) {
    if (token.isBlank()) {
      return;
    }
    final int separator = token.indexOf('=');
    if (separator <= 0) {
      throw new IllegalArgumentException(
          "invalid agent arg token '" + token + "': expected key=value");
    }
    final String key = token.substring(0, separator).trim();
    final String value = token.substring(separator + 1).trim();
    if (target.containsKey(key)) {
      System.err.println(
          "[chaos-agent] duplicate agent arg key '"
              + key
              + "': previous value overwritten (last-wins)");
    }
    target.put(key, value);
  }
}

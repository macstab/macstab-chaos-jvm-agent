package io.macstab.chaos.startup;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentArgsParser {
  private AgentArgsParser() {}

  public static AgentArgs parse(String agentArgs) {
    if (agentArgs == null || agentArgs.isBlank()) {
      return new AgentArgs(Map.of());
    }
    Map<String, String> values = new LinkedHashMap<>();
    StringBuilder current = new StringBuilder();
    boolean escaped = false;
    for (int i = 0; i < agentArgs.length(); i++) {
      char character = agentArgs.charAt(i);
      if (escaped) {
        current.append(character);
        escaped = false;
        continue;
      }
      if (character == '\\') {
        escaped = true;
        continue;
      }
      if (character == ';') {
        addEntry(values, current.toString());
        current.setLength(0);
        continue;
      }
      current.append(character);
    }
    addEntry(values, current.toString());
    return new AgentArgs(Map.copyOf(values));
  }

  private static void addEntry(Map<String, String> values, String token) {
    if (token.isBlank()) {
      return;
    }
    int separator = token.indexOf('=');
    if (separator <= 0 || separator == token.length() - 1) {
      throw new IllegalArgumentException(
          "invalid agent arg token '" + token + "', expected key=value");
    }
    values.put(token.substring(0, separator).trim(), token.substring(separator + 1).trim());
  }
}

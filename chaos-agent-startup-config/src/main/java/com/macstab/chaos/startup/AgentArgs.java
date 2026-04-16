package com.macstab.chaos.startup;

import java.util.Map;

/**
 * Immutable view of parsed agent arguments.
 *
 * <p>All lookups are case-sensitive and return {@code null} when the key is absent. Boolean lookups
 * use strict matching: only the literal strings {@code "true"} and {@code "false"}
 * (case-insensitive) are recognised; everything else yields the supplied default.
 *
 * @param values unmodifiable key-value map; never null
 */
public record AgentArgs(Map<String, String> values) {

  /** Defensive copy to guarantee immutability regardless of caller. */
  public AgentArgs {
    values = Map.copyOf(values);
  }

  /** Returns the raw value for {@code key}, or {@code null} if absent. */
  public String get(final String key) {
    return values.get(key);
  }

  /**
   * Returns the boolean value for {@code key}.
   *
   * <p>Only the literals {@code "true"} and {@code "false"} (case-insensitive) are accepted. Any
   * other value — including blank, {@code "yes"}, or typos — returns {@code defaultValue}.
   */
  public boolean getBoolean(final String key, final boolean defaultValue) {
    final String raw = values.get(key);
    if (raw == null) {
      return defaultValue;
    }
    if ("true".equalsIgnoreCase(raw)) {
      return true;
    }
    if ("false".equalsIgnoreCase(raw)) {
      return false;
    }
    return defaultValue;
  }
}

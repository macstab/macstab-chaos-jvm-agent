package com.macstab.chaos.jvm.startup;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable view of parsed agent arguments.
 *
 * <p>Key lookups are case-sensitive and return {@code null} when the key is absent. Boolean lookups
 * accept the canonical set {@code true|false|1|0|yes|no|on|off} (case-insensitive). Unrecognised
 * values fall back to the supplied default after a warning to stderr, so operators notice typos
 * instead of silently getting the default.
 *
 * @param values unmodifiable key-value map; never null
 */
public record AgentArgs(Map<String, String> values) {

  /** Case-insensitive literals accepted as boolean {@code true}. */
  private static final Set<String> TRUE_LITERALS = Set.of("true", "1", "yes", "on");

  /** Case-insensitive literals accepted as boolean {@code false}. */
  private static final Set<String> FALSE_LITERALS = Set.of("false", "0", "no", "off");

  /** Defensive copy to guarantee immutability regardless of caller. */
  public AgentArgs {
    values = Map.copyOf(values);
  }

  /**
   * Returns the raw value for {@code key}, or {@code null} if absent.
   *
   * @param key argument key to look up
   * @return the raw string value, or {@code null} if the key is absent
   */
  public String get(final String key) {
    return values.get(key);
  }

  /**
   * Returns the long value for {@code key}, or {@code defaultValue} if absent or unparseable.
   *
   * <p>The raw string is stripped of leading/trailing whitespace before parsing. Any non-numeric
   * value (including blank) silently yields {@code defaultValue}.
   *
   * @param key the argument key to look up
   * @param defaultValue value returned when the key is absent or its value is not a valid long
   * @return the parsed long, or {@code defaultValue}
   */
  public long getLong(final String key, final long defaultValue) {
    final String raw = values.get(key);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(raw.strip());
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  /**
   * Returns the boolean value for {@code key}.
   *
   * <p>Accepts {@code true|false|1|0|yes|no|on|off} (case-insensitive) with surrounding whitespace
   * trimmed. Any other non-blank value emits a one-line warning to {@code System.err} naming the
   * key and the offending literal, then returns {@code defaultValue}. Blank or missing keys
   * silently fall back.
   *
   * @param key argument key to look up
   * @param defaultValue value returned when the key is absent or its value is unrecognised
   * @return the parsed boolean, or {@code defaultValue}
   */
  public boolean getBoolean(final String key, final boolean defaultValue) {
    final String raw = values.get(key);
    if (raw == null) {
      return defaultValue;
    }
    final String stripped = raw.strip();
    if (stripped.isEmpty()) {
      return defaultValue;
    }
    final String normalized = stripped.toLowerCase(Locale.ROOT);
    if (TRUE_LITERALS.contains(normalized)) {
      return true;
    }
    if (FALSE_LITERALS.contains(normalized)) {
      return false;
    }
    // Without this warning the operator who wrote `enabled=tru` (or pasted an inline
    // `enabled=yeah` from a config management tool) sees no indication that the flag was
    // silently ignored and diagnostics proceed with the default value. Printing the key
    // and the raw value lets them find and fix the typo.
    System.err.println(
        "[chaos-agent] agent arg '"
            + key
            + "' has unrecognised boolean value '"
            + raw
            + "'; expected true|false|1|0|yes|no|on|off. Using default: "
            + defaultValue);
    return defaultValue;
  }
}

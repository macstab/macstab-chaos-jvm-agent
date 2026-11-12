package com.macstab.chaos.jvm.startup;

import com.macstab.chaos.jvm.api.Internal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
  public @Nullable String get(final String key) {
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
   * @deprecated no agent argument is a plain long any more ({@code configWatchInterval} and the
   *     other numbers are read by {@code AgentSettings} with their own validation); read the value
   *     with {@code get(key)} and parse it. To be removed in the next major release.
   */
  @Deprecated(since = "2.0.0", forRemoval = true)
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
    return parseBoolean(key, values.get(key), defaultValue);
  }

  /**
   * Parses a boolean flag the way agent args do ({@code true|false|1|0|yes|no|on|off}, case
   * insensitive); also used for the equivalent environment variables so both spellings behave the
   * same.
   *
   * @param name the arg or variable name, for the warning on an unrecognised value
   * @param raw the raw value; {@code null} or blank means {@code defaultValue}
   * @param defaultValue the value when {@code raw} is absent or unrecognised
   * @return the parsed flag
   */
  public static boolean parseBoolean(
      final String name, final @Nullable String raw, final boolean defaultValue) {
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    final Boolean parsed = parseBooleanQuietly(raw);
    if (parsed != null) {
      return parsed;
    }
    // Without this warning the operator who wrote `enabled=tru` (or pasted an inline
    // `enabled=yeah` from a config management tool) sees no indication that the flag was
    // silently ignored and diagnostics proceed with the default value. Printing the key
    // and the raw value lets them find and fix the typo.
    System.err.println(
        "[chaos-agent] agent arg '"
            + name
            + "' has unrecognised boolean value '"
            + raw
            + "'; expected true|false|1|0|yes|no|on|off. Using default: "
            + defaultValue);
    return defaultValue;
  }

  /**
   * Parses a boolean the way {@link #parseBoolean(String, String, boolean)} does, without printing
   * anything: for callers that report an unrecognised value through their own log.
   *
   * @param raw the raw value
   * @return {@code true} or {@code false} for {@code true|false|1|0|yes|no|on|off} (case
   *     insensitive, surrounding whitespace ignored); {@code null} for {@code null}, blank or any
   *     other text
   */
  @Internal
  public static @Nullable Boolean parseBooleanQuietly(final @Nullable String raw) {
    if (raw == null) {
      return null;
    }
    final String normalized = raw.strip().toLowerCase(Locale.ROOT);
    if (TRUE_LITERALS.contains(normalized)) {
      return Boolean.TRUE;
    }
    if (FALSE_LITERALS.contains(normalized)) {
      return Boolean.FALSE;
    }
    return null;
  }
}

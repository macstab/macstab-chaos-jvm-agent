package com.macstab.chaos.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A named string-matching predicate used by selectors to filter JVM operations by class name,
 * method name, thread name, resource name, or any other string-valued attribute.
 *
 * <p>Five match modes are supported, in increasing power:
 *
 * <table border="1">
 *   <caption>NamePattern match modes</caption>
 *   <tr><th>Mode</th><th>Semantics</th><th>Example</th></tr>
 *   <tr><td>{@link MatchMode#ANY}</td><td>Always matches. No value needed.</td>
 *       <td>{@code NamePattern.any()}</td></tr>
 *   <tr><td>{@link MatchMode#EXACT}</td><td>Full string equality.</td>
 *       <td>{@code NamePattern.exact("java.sql.Connection")}</td></tr>
 *   <tr><td>{@link MatchMode#PREFIX}</td><td>String starts with value.</td>
 *       <td>{@code NamePattern.prefix("io.lettuce")}</td></tr>
 *   <tr><td>{@link MatchMode#GLOB}</td><td>{@code *} matches any sequence, {@code ?} matches
 *       one character.</td>
 *       <td>{@code NamePattern.glob("com.example.*.Repository")}</td></tr>
 *   <tr><td>{@link MatchMode#REGEX}</td><td>Full {@link java.util.regex.Pattern} match.</td>
 *       <td>{@code NamePattern.regex(".*Service(Impl)?")}</td></tr>
 * </table>
 *
 * <p>A {@code null} candidate string never matches any pattern except {@link MatchMode#ANY}.
 *
 * <p>Task 7: GLOB and REGEX patterns are compiled once and cached in a JVM-wide static {@link
 * ConcurrentHashMap} keyed by the pattern string. Repeated calls to {@link #matches} on hot paths
 * do not pay the {@link Pattern#compile} cost.
 */
public record NamePattern(MatchMode mode, String value) {

  /** Pre-compiled regex patterns for GLOB mode, keyed by the glob expression string. */
  private static final ConcurrentHashMap<String, Pattern> GLOB_CACHE = new ConcurrentHashMap<>();

  /** Pre-compiled patterns for REGEX mode, keyed by the raw regex string. */
  private static final ConcurrentHashMap<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

  /**
   * Canonical constructor. Normalises {@code null} mode to {@link MatchMode#ANY} and {@code null}
   * value to {@code "*"}.
   *
   * @throws IllegalArgumentException if mode is not {@link MatchMode#ANY} and value is blank
   */
  @JsonCreator
  public NamePattern(
      @JsonProperty("mode") final MatchMode mode, @JsonProperty("value") final String value) {
    this.mode = Objects.requireNonNullElse(mode, MatchMode.ANY);
    this.value = value == null ? "*" : value;
    if (this.mode != MatchMode.ANY && this.value.isBlank()) {
      throw new IllegalArgumentException("name pattern value must be non-blank");
    }
  }

  /**
   * Returns a pattern that matches any string, including {@code null}. The standard choice when a
   * selector field is not constrained.
   */
  public static NamePattern any() {
    return new NamePattern(MatchMode.ANY, "*");
  }

  /**
   * Returns a pattern that matches only strings exactly equal to {@code value}.
   *
   * @param value the exact string to match; must be non-blank
   */
  public static NamePattern exact(String value) {
    return new NamePattern(MatchMode.EXACT, value);
  }

  /**
   * Returns a pattern that matches strings starting with {@code value}. Efficient for package-
   * prefix filtering (e.g., {@code "io.lettuce"} matches all Lettuce classes).
   *
   * @param value the prefix; must be non-blank
   */
  public static NamePattern prefix(String value) {
    return new NamePattern(MatchMode.PREFIX, value);
  }

  /**
   * Returns a glob pattern where {@code *} matches any sequence of characters and {@code ?} matches
   * exactly one character.
   *
   * <p>Examples: {@code "com.example.*.Repository"}, {@code "worker-thread-?"}.
   *
   * @param value the glob expression; must be non-blank
   */
  public static NamePattern glob(String value) {
    return new NamePattern(MatchMode.GLOB, value);
  }

  /**
   * Returns a full {@link java.util.regex.Pattern}-based matcher applied against the entire
   * candidate string (implicit {@code ^...$} anchoring).
   *
   * @param value a valid Java regex; must be non-blank
   * @see Pattern#matches(String, CharSequence)
   */
  public static NamePattern regex(String value) {
    return new NamePattern(MatchMode.REGEX, value);
  }

  /**
   * Tests whether {@code candidate} matches this pattern.
   *
   * @param candidate the string to test; {@code null} matches only {@link MatchMode#ANY}
   * @return {@code true} if the candidate satisfies this pattern
   */
  public boolean matches(String candidate) {
    if (mode == MatchMode.ANY) {
      return true;
    }
    if (candidate == null) {
      return false;
    }
    return switch (mode) {
      case EXACT -> candidate.equals(value);
      case PREFIX -> candidate.startsWith(value);
      case GLOB ->
          GLOB_CACHE
              .computeIfAbsent(value, v -> Pattern.compile(toRegex(v)))
              .matcher(candidate)
              .matches();
      case REGEX ->
          REGEX_CACHE.computeIfAbsent(value, Pattern::compile).matcher(candidate).matches();
      case ANY -> true;
    };
  }

  private static String toRegex(String glob) {
    StringBuilder builder = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char current = glob.charAt(i);
      switch (current) {
        case '*' -> builder.append(".*");
        case '?' -> builder.append('.');
        case '.', '(', ')', '+', '|', '^', '$', '@', '%' -> builder.append('\\').append(current);
        case '\\' -> builder.append("\\\\");
        default -> builder.append(current);
      }
    }
    return builder.append('$').toString();
  }

  /** Determines the matching algorithm applied by {@link NamePattern#matches}. */
  public enum MatchMode {

    /** Matches every candidate including {@code null}. No value required. */
    ANY,

    /** Full equality: {@code candidate.equals(value)}. */
    EXACT,

    /** Prefix match: {@code candidate.startsWith(value)}. */
    PREFIX,

    /**
     * Glob match: {@code *} expands to any character sequence, {@code ?} to a single character.
     * Special regex characters in the glob value are escaped automatically. The compiled {@link
     * Pattern} is cached in a JVM-wide static map keyed by the glob string.
     */
    GLOB,

    /**
     * Full Java regex match anchored to the entire candidate string. The pattern is compiled once
     * and cached; subsequent calls for the same regex string pay no compile cost.
     */
    REGEX,
  }
}

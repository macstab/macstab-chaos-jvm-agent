package com.macstab.chaos.jvm.api;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

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
 *   <tr><td>{@link MatchMode#GLOB}</td><td>{@code *} matches any sequence of characters, {@code
 *       ?} exactly one (one code point: a surrogate pair is one character, a line terminator is
 *       one too); every other character matches itself.</td>
 *       <td>{@code NamePattern.glob("com.example.*.Repository")}</td></tr>
 *   <tr><td>{@link MatchMode#REGEX}</td><td>Full {@link java.util.regex.Pattern} match.</td>
 *       <td>{@code NamePattern.regex(".*Service(Impl)?")}</td></tr>
 * </table>
 *
 * <p>A {@code null} candidate string never matches any pattern except {@link MatchMode#ANY}.
 *
 * <p>A GLOB is matched directly, without a regex and without allocating. REGEX patterns are
 * compiled once and cached in a JVM-wide bounded cache keyed by the pattern string (capacity
 * {@value #CACHE_CAPACITY}); a full cache is emptied before the next expression is cached, so a
 * pattern-spamming plan cannot pin arbitrary heap and a plan's own expressions are cached again.
 *
 * <p>No input is silently reinterpreted: a mode other than {@link MatchMode#ANY} needs a non-blank
 * value, {@link MatchMode#ANY} takes none (a {@code null}, blank or {@code "*"} value, kept as
 * {@code "*"}), and a value needs a mode. Without both, the pattern is {@link MatchMode#ANY}.
 *
 * @param mode matching algorithm to apply; {@code null} together with a {@code null} value is
 *     {@link MatchMode#ANY}
 * @param value pattern string interpreted according to {@code mode}
 */
public record NamePattern(MatchMode mode, String value) {

  /**
   * Upper bound on distinct REGEX expressions cached per JVM. The cache is meant to amortise the
   * compile cost for the finite set of expressions a deployed plan actually uses; a hostile or
   * buggy plan that generates thousands of unique patterns should not be able to pin heap.
   */
  private static final int CACHE_CAPACITY = 1024;

  /**
   * Upper bound on the length of a single GLOB/REGEX pattern string. It bounds what a plan makes
   * the JVM compile and cache, not how long a match takes: Java's regex engine backtracks, and a
   * short expression such as {@code (.*a){12}} or {@code (a+)+b} takes seconds on a candidate of a
   * few dozen characters. A plan's REGEX patterns therefore come from trusted authors (see {@link
   * MatchMode#REGEX}).
   */
  private static final int MAX_PATTERN_LENGTH = 4096;

  /** Pre-compiled patterns for REGEX mode, keyed by the raw regex string. */
  private static final Map<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

  /** Compiles {@code regex} through {@code cache}; package-private as a test seam. */
  static Pattern cachedCompile(
      final Map<String, Pattern> cache, final String key, final String regex) {
    // ConcurrentHashMap for lock-free hot-path reads. Plans seed the cache eagerly at
    // construction, so at runtime the map only grows with distinct live-plan patterns.
    // At capacity the cache is emptied, then the expression is inserted: matches() compiles
    // through here on every call, so not inserting would recompile a live plan's expression on
    // every match once the cache is full. Concurrent clears can wipe each other's fresh entries;
    // that costs recompiles only, never a wrong pattern, and the capacity still bounds the heap.
    final Pattern cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    final Pattern compiled = Pattern.compile(regex);
    if (cache.size() >= CACHE_CAPACITY) {
      cache.clear();
    }
    final Pattern winner = cache.putIfAbsent(key, compiled);
    return winner != null ? winner : compiled;
  }

  /** Singleton {@link MatchMode#ANY} instance — avoids allocating on every {@link #any()} call. */
  private static final NamePattern ANY = new NamePattern(MatchMode.ANY, "*");

  /**
   * Canonical constructor. Both {@code null} make {@link MatchMode#ANY}; {@link MatchMode#ANY}'s
   * value is always {@code "*"}.
   *
   * @throws IllegalArgumentException if the mode is not {@link MatchMode#ANY} and the value is
   *     {@code null} or blank, if the mode is {@link MatchMode#ANY} and the value is other than
   *     {@code null}, blank or {@code "*"}, or if a value is given without a mode
   */
  public NamePattern(final @Nullable MatchMode mode, final @Nullable String value) {
    if (mode == null && value != null) {
      throw new IllegalArgumentException("name pattern value needs a mode");
    }
    this.mode = Objects.requireNonNullElse(mode, MatchMode.ANY);
    if (this.mode == MatchMode.ANY) {
      // ANY matches everything, so a value it would ignore is a mistake, not a setting. "*" and
      // blank stay accepted: the any() singleton and every written plan carry "*".
      if (value != null && !value.isBlank() && !value.equals("*")) {
        throw new IllegalArgumentException("name pattern mode ANY takes no value");
      }
      this.value = "*";
    } else {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("name pattern value must be non-blank");
      }
      this.value = value;
    }
    // Length guard runs at construction so oversized patterns are rejected when the selector is
    // built, not lazily on the first call to matches(). This keeps the blast radius of a hostile
    // plan contained to the parser and prevents latent surprise during live chaos execution.
    if (this.mode == MatchMode.GLOB || this.mode == MatchMode.REGEX) {
      guardPatternLength(this.value);
      // Eagerly compile a REGEX so a malformed expression fails plan construction instead of
      // silently passing validation and throwing PatternSyntaxException on the first dispatch
      // event that exercises the selector. Without this, a plan with {"mode":"REGEX","value":
      // "(a["} loads cleanly, registers as ACTIVE, and only surfaces the syntax error at
      // unpredictable hot-path invocation time — at which point the error may be swallowed at
      // FINE and the operator never learns why chaos isn't firing. Seed the cache so the first
      // runtime matches() call stays on the fast path.
      try {
        if (this.mode == MatchMode.REGEX) {
          cachedCompile(REGEX_CACHE, this.value, this.value);
        }
      } catch (final PatternSyntaxException syntaxError) {
        throw new IllegalArgumentException(
            "invalid "
                + this.mode.name().toLowerCase(java.util.Locale.ROOT)
                + " pattern: "
                + syntaxError.getMessage(),
            syntaxError);
      }
    }
  }

  /**
   * Whether this pattern matches every name, as far as its form shows: {@link MatchMode#ANY}, a
   * GLOB made of {@code *} characters only, or the REGEX {@code .*}, {@code ^.*$} or {@code .*+}.
   * The list cannot be complete (a regex such as {@code (.*)} also matches everything); it serves
   * the {@link ChaosSelector.MethodSelector} guard, whose real protection is that method hooks
   * never weave JDK classes.
   *
   * @return {@code true} for a pattern of a match-all form
   */
  boolean matchesEverything() {
    return switch (mode) {
      case ANY -> true;
      case GLOB -> value.chars().allMatch(c -> c == '*');
      case REGEX -> value.equals(".*") || value.equals("^.*$") || value.equals(".*+");
      default -> false;
    };
  }

  /**
   * Returns a pattern that matches any string, including {@code null}. The standard choice when a
   * selector field is not constrained. Returns a shared singleton, so selectors that use {@code
   * any()} allocate nothing on the plan parsing path.
   *
   * @return a NamePattern that matches any name
   */
  public static NamePattern any() {
    return ANY;
  }

  /**
   * Returns a pattern that matches only strings exactly equal to {@code value}.
   *
   * @param value the exact string to match; must be non-blank
   * @return a NamePattern matching the exact value
   */
  public static NamePattern exact(final String value) {
    return new NamePattern(MatchMode.EXACT, value);
  }

  /**
   * Returns a pattern that matches strings starting with {@code value}. Efficient for package-
   * prefix filtering (e.g., {@code "io.lettuce"} matches all Lettuce classes).
   *
   * @param value the prefix; must be non-blank
   * @return a NamePattern matching names with the given prefix
   */
  public static NamePattern prefix(final String value) {
    return new NamePattern(MatchMode.PREFIX, value);
  }

  /**
   * Returns a glob pattern where {@code *} matches any sequence of characters (line terminators
   * included) and {@code ?} matches exactly one character (one code point); every other character,
   * {@code \} and regex metacharacters included, matches itself.
   *
   * <p>Examples: {@code "com.example.*.Repository"}, {@code "worker-thread-?"}.
   *
   * @param value the glob expression; must be non-blank
   * @return a NamePattern matching names with the given glob pattern
   */
  public static NamePattern glob(final String value) {
    return new NamePattern(MatchMode.GLOB, value);
  }

  /**
   * Returns a full {@link java.util.regex.Pattern}-based matcher applied against the entire
   * candidate string (implicit {@code ^...$} anchoring).
   *
   * @param value a valid Java regex; must be non-blank
   * @return a NamePattern matching names with the given regex
   * @see Pattern#matches(String, CharSequence)
   */
  public static NamePattern regex(final String value) {
    return new NamePattern(MatchMode.REGEX, value);
  }

  /**
   * Tests whether {@code candidate} matches this pattern.
   *
   * @param candidate the string to test; {@code null} matches only {@link MatchMode#ANY}
   * @return {@code true} if the candidate satisfies this pattern
   */
  public boolean matches(final @Nullable String candidate) {
    if (candidate == null) {
      return mode == MatchMode.ANY;
    }
    return switch (mode) {
      case EXACT -> candidate.equals(value);
      case PREFIX -> candidate.startsWith(value);
      case GLOB -> globMatches(value, candidate);
      case REGEX -> compiledRegex(value).matcher(candidate).matches();
      case ANY -> true;
    };
  }

  /**
   * Matches {@code candidate} against {@code glob} by code points, without allocating: '*' matches
   * any sequence, '?' exactly one code point, anything else itself. Two pointers with backtracking
   * to the last '*': linear in the candidate for globs with one '*', and bounded by the product of
   * the lengths in the worst case, never exponential.
   */
  private static boolean globMatches(final String glob, final String candidate) {
    int patternIndex = 0;
    int candidateIndex = 0;
    int starIndex = -1;
    int starMatchEnd = 0;
    while (candidateIndex < candidate.length()) {
      if (patternIndex < glob.length()) {
        final int token = glob.codePointAt(patternIndex);
        if (token == '*') {
          starIndex = patternIndex++;
          starMatchEnd = candidateIndex;
          continue;
        }
        final int character = candidate.codePointAt(candidateIndex);
        if (token == '?' || token == character) {
          patternIndex += Character.charCount(token);
          candidateIndex += Character.charCount(character);
          continue;
        }
      }
      if (starIndex < 0) {
        return false;
      }
      // Let the last '*' take one more code point and retry the rest of the glob after it.
      patternIndex = starIndex + 1;
      starMatchEnd += Character.charCount(candidate.codePointAt(starMatchEnd));
      candidateIndex = starMatchEnd;
    }
    while (patternIndex < glob.length() && glob.charAt(patternIndex) == '*') {
      patternIndex++;
    }
    return patternIndex == glob.length();
  }

  private static Pattern compiledRegex(final String value) {
    return cachedCompile(REGEX_CACHE, value, value);
  }

  private static void guardPatternLength(final String value) {
    if (value.length() > MAX_PATTERN_LENGTH) {
      throw new IllegalArgumentException(
          "name pattern value exceeds maximum length of "
              + MAX_PATTERN_LENGTH
              + " characters (actual: "
              + value.length()
              + ")");
    }
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
     * Glob match: {@code *} matches any character sequence (line terminators included), {@code ?}
     * exactly one character (one code point), every other character itself. Matched directly,
     * without a regex.
     */
    GLOB,

    /**
     * Full Java regex match anchored to the entire candidate string. The pattern is compiled once
     * and cached; subsequent calls for the same regex string pay no compile cost.
     *
     * <p>The match runs on the intercepted call's thread, every time the selector is evaluated,
     * with Java's backtracking engine and no time limit: nested or adjacent unbounded quantifiers
     * ({@code (a+)+}, {@code (.*a){12}}) can hold that thread for seconds on a short candidate.
     * Write plans only from trusted authors, keep expressions free of such quantifiers, and prefer
     * {@link #GLOB}, {@link #PREFIX} or {@link #EXACT} where they suffice.
     */
    REGEX,
  }
}

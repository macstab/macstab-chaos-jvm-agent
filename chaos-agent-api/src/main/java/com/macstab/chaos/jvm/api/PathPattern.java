package com.macstab.chaos.jvm.api;

import org.jspecify.annotations.Nullable;

/**
 * Matches file paths under a directory, or one file.
 *
 * <p>{@code prefix} matches itself and every path below it on a {@code /} boundary: {@code
 * "/var/data"} matches {@code /var/data} and {@code /var/data/a.db} but not {@code /var/database}.
 * Both the prefix and the candidate are normalised lexically before comparing: {@code \} is treated
 * as {@code /}, repeated separators collapse, {@code .} segments are dropped and {@code ..} removes
 * the segment before it. Nothing touches the file system, so symbolic links are not resolved. A
 * relative candidate path is compared as given; it does not match an absolute prefix.
 *
 * @param prefix the directory or file to match; normalised on construction
 */
public record PathPattern(String prefix) {

  /** Normalises and validates the prefix. */
  public PathPattern {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("path prefix must be non-blank");
    }
    prefix = normalize(prefix);
  }

  /**
   * Returns a pattern matching {@code directory} and everything below it.
   *
   * @param directory the directory (or file)
   * @return the pattern
   */
  public static PathPattern under(final String directory) {
    return new PathPattern(directory);
  }

  /**
   * Returns whether {@code path} is the prefix or lies below it.
   *
   * @param path the candidate path; {@code null} never matches
   * @return {@code true} on a match
   */
  public boolean matches(final @Nullable String path) {
    if (path == null) {
      return false;
    }
    final String candidate = needsNormalizing(path) ? normalize(path) : path;
    if (!candidate.startsWith(prefix)) {
      return false;
    }
    return candidate.length() == prefix.length()
        || prefix.endsWith("/")
        || candidate.charAt(prefix.length()) == '/';
  }

  private static boolean needsNormalizing(final String path) {
    return path.isEmpty()
        || path.indexOf('\\') >= 0
        || path.contains("//")
        || path.contains("/./")
        || path.contains("/../")
        || path.endsWith("/.")
        || path.endsWith("/..")
        || path.startsWith("./")
        || path.startsWith("../")
        || (path.length() > 1 && path.endsWith("/"));
  }

  /**
   * Normalises {@code path} lexically.
   *
   * @param path the path
   * @return the normalised path; {@code "/"} for the root
   */
  static String normalize(final String path) {
    final String unified = path.replace('\\', '/');
    final boolean absolute = unified.startsWith("/");
    final java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
    for (final String segment : unified.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".")) {
        continue;
      }
      if (segment.equals("..")) {
        if (!segments.isEmpty() && !segments.peekLast().equals("..")) {
          segments.removeLast();
        } else if (!absolute) {
          segments.addLast("..");
        }
        continue;
      }
      segments.addLast(segment);
    }
    final String joined = String.join("/", segments);
    if (absolute) {
      return "/" + joined;
    }
    return joined.isEmpty() ? "." : joined;
  }
}

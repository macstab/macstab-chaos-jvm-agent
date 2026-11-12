package com.macstab.chaos.jvm.startup;

import com.macstab.chaos.jvm.api.Internal;
import org.jspecify.annotations.Nullable;

/**
 * Makes text safe to put into one log line: the agent's single sanitizer for paths, ids and error
 * messages before they reach a logger or {@code System.err}.
 *
 * <p>Control characters that could end the line or forge another one (C0 controls including TAB, CR
 * and LF, DEL, the C1 controls including NEL U+0085, LINE SEPARATOR U+2028 and PARAGRAPH SEPARATOR
 * U+2029) become U+FFFD, and the text is capped with a marker so a huge message cannot flood the
 * log. It never adds information: callers log a message, never the parser's excerpt of the input it
 * failed on.
 */
@Internal
public final class LogText {

  /** The default cap, in characters, before the {@link #TRUNCATED} marker. */
  public static final int MAX_LENGTH = 512;

  /** Appended when the text was cut at the cap. */
  static final String TRUNCATED = "...[truncated]";

  /** What a {@code null} or empty message is logged as. */
  static final String NO_MESSAGE = "(no message)";

  private LogText() {}

  /**
   * Sanitizes {@code text} and caps it at {@value #MAX_LENGTH} characters.
   *
   * @param text the text to log; {@code null} or empty gives {@code (no message)}
   * @return a single-line, bounded rendering of {@code text}
   */
  public static String sanitize(final @Nullable String text) {
    return sanitize(text, MAX_LENGTH);
  }

  /**
   * Sanitizes {@code text} and caps it at {@code maxLength} characters. A surrogate pair is never
   * split at the cap.
   *
   * @param text the text to log; {@code null} or empty gives {@code (no message)}
   * @param maxLength the number of characters kept before the {@code ...[truncated]} marker; at
   *     least 1
   * @return a single-line, bounded rendering of {@code text}
   */
  public static String sanitize(final @Nullable String text, final int maxLength) {
    if (text == null || text.isEmpty()) {
      return NO_MESSAGE;
    }
    int end = Math.min(text.length(), maxLength);
    if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    final StringBuilder sanitized = new StringBuilder(end + TRUNCATED.length());
    for (int i = 0; i < end; i++) {
      final char character = text.charAt(i);
      sanitized.append(breaksTheLine(character) ? '�' : character);
    }
    if (end < text.length()) {
      sanitized.append(TRUNCATED);
    }
    return sanitized.toString();
  }

  private static boolean breaksTheLine(final char character) {
    return character < 0x20
        || (character >= 0x7F && character <= 0x9F)
        || character == ' '
        || character == ' ';
  }
}

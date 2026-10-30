package com.macstab.chaos.jvm.startup;

/**
 * Thrown when a chaos plan cannot be loaded from any configured source during agent startup.
 *
 * <p>Carries the {@link #source} identifier so operators can immediately tell which configuration
 * path failed (agent arg, environment variable, file path, or base64 payload).
 */
public final class ConfigLoadException extends RuntimeException {

  /**
   * Identifies which configuration source triggered the failure. Values are stable tokens such as
   * {@code "inline-json"}, {@code "base64"}, {@code "file:/etc/chaos/plan.json"}, or {@code
   * "json-input"} (for parse errors on an already-loaded string). Included in operator-facing error
   * messages and log entries to pinpoint the misconfigured source without requiring a stack trace.
   */
  private final String source;

  /** 1-based line of a JSON parse failure; {@code 0} when the failure has no position. */
  private final int line;

  /** 1-based column of a JSON parse failure; {@code 0} when the failure has no position. */
  private final int column;

  /** Short description of what is wrong, without the position; never null. */
  private final String reason;

  /**
   * Constructs an exception for a configuration load failure without an underlying cause.
   *
   * @param message human-readable description of what went wrong
   * @param source stable identifier of the configuration source that failed (e.g., {@code
   *     "inline-json"}, {@code "base64"}, {@code "file:/etc/chaos/plan.json"})
   */
  public ConfigLoadException(final String message, final String source) {
    this(message, source, null, 0, 0, message);
  }

  /**
   * Constructs an exception for a configuration load failure with an underlying cause.
   *
   * @param message human-readable description of what went wrong
   * @param source stable identifier of the configuration source that failed
   * @param cause the root cause (e.g., an {@link java.io.IOException} from a file read or a Jackson
   *     {@link com.fasterxml.jackson.core.JsonProcessingException})
   */
  public ConfigLoadException(final String message, final String source, final Throwable cause) {
    this(message, source, cause, 0, 0, message);
  }

  /**
   * Constructs an exception for a parse failure with its position in the input.
   *
   * @param message human-readable description, including the position
   * @param source stable identifier of the configuration source that failed
   * @param cause the root cause, or {@code null}
   * @param line 1-based line of the failure; {@code 0} when unknown
   * @param column 1-based column of the failure; {@code 0} when unknown
   * @param reason what is wrong, without the position
   */
  public ConfigLoadException(
      final String message,
      final String source,
      final Throwable cause,
      final int line,
      final int column,
      final String reason) {
    super(message, cause);
    this.source = source;
    this.line = line;
    this.column = column;
    this.reason = reason == null ? String.valueOf(message) : reason;
  }

  /**
   * Returns the 1-based line of a JSON parse failure.
   *
   * @return the line; {@code 0} when the failure has no position
   */
  public int line() {
    return line;
  }

  /**
   * Returns the 1-based column of a JSON parse failure.
   *
   * @return the column; {@code 0} when the failure has no position
   */
  public int column() {
    return column;
  }

  /**
   * Returns what is wrong, without the position: the {@code reason} of the status file.
   *
   * @return the reason; never null
   */
  public String reason() {
    return reason;
  }

  /**
   * Returns the stable identifier of the configuration source that caused this failure.
   *
   * <p>Callers can use this value to differentiate failure modes without parsing the message
   * string:
   *
   * <pre>{@code
   * } catch (ConfigLoadException e) {
   *     if (e.getSource().startsWith("file:")) {
   *         // file-not-found or unreadable
   *     }
   * }</pre>
   *
   * @return the source identifier; never null
   */
  public String getSource() {
    return source;
  }
}

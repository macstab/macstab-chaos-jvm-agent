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

  /**
   * Constructs an exception for a configuration load failure without an underlying cause.
   *
   * @param message human-readable description of what went wrong
   * @param source stable identifier of the configuration source that failed (e.g., {@code
   *     "inline-json"}, {@code "base64"}, {@code "file:/etc/chaos/plan.json"})
   */
  public ConfigLoadException(final String message, final String source) {
    super(message);
    this.source = source;
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
    super(message, cause);
    this.source = source;
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

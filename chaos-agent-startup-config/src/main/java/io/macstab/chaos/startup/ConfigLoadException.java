package io.macstab.chaos.startup;

/**
 * Thrown when a chaos plan cannot be loaded from any configured source during agent startup.
 *
 * <p>Carries the {@link #source} identifier so operators can immediately tell which configuration
 * path failed (agent arg, environment variable, file path, or base64 payload).
 */
public final class ConfigLoadException extends RuntimeException {

  private final String source;

  public ConfigLoadException(final String message, final String source) {
    super(message);
    this.source = source;
  }

  public ConfigLoadException(final String message, final String source, final Throwable cause) {
    super(message, cause);
    this.source = source;
  }

  public String getSource() {
    return source;
  }
}

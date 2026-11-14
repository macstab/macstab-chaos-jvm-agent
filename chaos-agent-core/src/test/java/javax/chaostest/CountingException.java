package javax.chaostest;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * An injectable exception that counts its constructions: a test class in a {@code javax.} package,
 * which the injection allowlist admits.
 */
public class CountingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Constructions since the class was loaded. */
  public static final AtomicInteger CONSTRUCTED = new AtomicInteger();

  /**
   * Counts the construction.
   *
   * @param message the message
   */
  public CountingException(final String message) {
    super(message);
    CONSTRUCTED.incrementAndGet();
  }
}

package javax.chaostest;

/**
 * An injectable exception whose no-argument constructor sets its own cause: a test class in a
 * {@code javax.} package, which the injection allowlist admits.
 */
public final class PreCausedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Sets the cause {@code IllegalArgumentException("own cause")}. */
  public PreCausedException() {
    super(new IllegalArgumentException("own cause"));
  }
}

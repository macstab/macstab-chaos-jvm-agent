package javax.chaostest;

/** A fault whose only constructor is a private {@code (String)} one: the agent cannot build it. */
public final class PrivateMessageFault extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private PrivateMessageFault(final String message) {
    super(message);
  }

  /**
   * Creates the fault; keeps the private constructor used.
   *
   * @param message the message
   * @return the fault
   */
  public static PrivateMessageFault of(final String message) {
    return new PrivateMessageFault(message);
  }
}

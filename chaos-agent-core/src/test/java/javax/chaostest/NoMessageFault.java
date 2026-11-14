package javax.chaostest;

/** An injectable fault with a public no-argument constructor only. */
public final class NoMessageFault extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the fault without a message. */
  public NoMessageFault() {
    super();
  }
}

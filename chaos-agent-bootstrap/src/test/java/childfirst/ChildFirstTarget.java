package childfirst;

/**
 * An application class that a child-first class loader defines ({@code
 * ChildFirstLoaderIntegrationTest}). Its package lies outside {@code com.macstab.chaos.jvm.}, which
 * the agent never weaves.
 */
public final class ChildFirstTarget {

  /** Creates a target. */
  public ChildFirstTarget() {}

  /**
   * A method a method selector names.
   *
   * @return {@code "called"}
   */
  public String call() {
    return "called";
  }
}

package net.bytebuddy.probe;

/**
 * An application class in a {@code net.bytebuddy} package, which the agent leaves alone like
 * ByteBuddy's own classes ({@code ShadedAgentProbeMain}).
 */
public final class Target {

  /** Creates a target. */
  public Target() {}

  /**
   * A method a method selector names.
   *
   * @return {@code 1}
   */
  public int run() {
    return 1;
  }
}

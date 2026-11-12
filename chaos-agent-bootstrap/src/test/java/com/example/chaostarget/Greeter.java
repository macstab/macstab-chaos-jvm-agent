package com.example.chaostarget;

/** Application class targeted by method-weaving probes; outside the agent's own packages. */
public final class Greeter {

  /** Creates a greeter. */
  public Greeter() {}

  /**
   * Greets once.
   *
   * @param name the name
   * @return the greeting
   */
  public String greet(final String name) {
    return "hello " + name;
  }

  /**
   * Greets several times; an overload of {@link #greet(String)}.
   *
   * @param name the name
   * @param times how often
   * @return the greeting
   */
  public String greet(final String name, final int times) {
    return ("hello " + name + " ").repeat(times).trim();
  }

  /**
   * Returns a constant.
   *
   * @return 42
   */
  public int count() {
    return 42;
  }
}

package com.macstab.chaos.spring.boot4;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Spring Boot 4 runtime chaos starter.
 *
 * <p>All chaos beans are gated behind {@link #isEnabled()}; the agent is inert unless an operator
 * explicitly opts in by setting {@code macstab.chaos.enabled=true} in the Spring environment.
 */
@ConfigurationProperties("macstab.chaos")
public class ChaosProperties {

  private boolean enabled = false;
  private String configFile;
  private boolean debugDumpOnStart = false;
  private Actuator actuator = new Actuator();

  /** Default constructor invoked by Spring when the properties are bound. */
  public ChaosProperties() {}

  /**
   * Returns {@code true} when the chaos auto-configuration should activate.
   *
   * @return the current enabled flag
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Toggles the chaos auto-configuration on or off.
   *
   * @param enabled new flag value
   */
  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Returns the optional path of a chaos plan JSON file to load at startup.
   *
   * @return the file path, or {@code null} when no startup plan should be loaded
   */
  public String getConfigFile() {
    return configFile;
  }

  /**
   * Sets the optional path of a chaos plan JSON file to load at startup.
   *
   * @param configFile the file path, or {@code null}/blank to disable loading
   */
  public void setConfigFile(final String configFile) {
    this.configFile = configFile;
  }

  /**
   * Returns whether a diagnostics dump should be printed once the application context has started.
   *
   * @return the current flag value
   */
  public boolean isDebugDumpOnStart() {
    return debugDumpOnStart;
  }

  /**
   * Enables or disables the startup diagnostics dump.
   *
   * @param debugDumpOnStart new flag value
   */
  public void setDebugDumpOnStart(final boolean debugDumpOnStart) {
    this.debugDumpOnStart = debugDumpOnStart;
  }

  /**
   * Returns the nested {@link Actuator} settings.
   *
   * @return the nested actuator settings; never {@code null}
   */
  public Actuator getActuator() {
    return actuator;
  }

  /**
   * Replaces the nested {@link Actuator} settings.
   *
   * @param actuator new actuator settings
   */
  public void setActuator(final Actuator actuator) {
    this.actuator = actuator;
  }

  /** Sub-properties controlling the Actuator {@code /actuator/chaos} endpoint. */
  public static class Actuator {

    private boolean enabled = false;

    /** Default constructor invoked by Spring when the properties are bound. */
    public Actuator() {}

    /**
     * Returns {@code true} when the chaos Actuator endpoint bean should be created.
     *
     * @return the current enabled flag
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * Toggles the chaos Actuator endpoint on or off.
     *
     * @param enabled new flag value
     */
    public void setEnabled(final boolean enabled) {
      this.enabled = enabled;
    }
  }
}

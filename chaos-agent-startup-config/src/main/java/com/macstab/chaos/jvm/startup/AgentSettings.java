package com.macstab.chaos.jvm.startup;

import com.macstab.chaos.jvm.api.Internal;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The agent's settings, resolved once per start: each one is read from the agent argument, then the
 * system property, then the environment variable(s). System properties and the environment are
 * launch-time local sources, so their forms are trusted as the agent arguments are.
 *
 * <p>The agent arguments are parsed once, so a parse warning (a duplicate key) is printed once
 * however many settings are read.
 */
@Internal
public final class AgentSettings {

  private final AgentArgs args;
  private final Map<String, String> environment;

  private AgentSettings(final AgentArgs args, final Map<String, String> environment) {
    this.args = args;
    this.environment = environment;
  }

  /**
   * Parses the agent arguments once.
   *
   * @param agentArgs the raw agent arguments; {@code null} or blank for none
   * @param environment the process environment
   * @return the settings
   * @throws IllegalArgumentException if the agent arguments are malformed
   */
  public static AgentSettings of(
      final @Nullable String agentArgs, final Map<String, String> environment) {
    return new AgentSettings(AgentArgsParser.parse(agentArgs), environment);
  }

  /**
   * Returns the parsed agent arguments.
   *
   * @return the agent arguments
   */
  public AgentArgs args() {
    return args;
  }

  /**
   * Returns a setting's value.
   *
   * @param arg the agent argument name
   * @param property the system property name
   * @param envs the environment variable names, the first found wins
   * @return the first value found, or {@code null} when none is set
   */
  public @Nullable String value(final String arg, final String property, final String... envs) {
    return first(false, arg, property, envs);
  }

  /**
   * Returns a setting's first non-blank value: a blank agent argument or property falls through to
   * the next source.
   *
   * @param arg the agent argument name
   * @param property the system property name
   * @param envs the environment variable names, the first non-blank one wins
   * @return the first non-blank value found, or {@code null} when none is set
   */
  public @Nullable String nonBlankValue(
      final String arg, final String property, final String... envs) {
    return first(true, arg, property, envs);
  }

  private @Nullable String first(
      final boolean skipBlank, final String arg, final String property, final String... envs) {
    final String fromArgs = args.get(arg);
    if (usable(fromArgs, skipBlank)) {
      return fromArgs;
    }
    final String fromProperty = System.getProperty(property);
    if (usable(fromProperty, skipBlank)) {
      return fromProperty;
    }
    for (final String env : envs) {
      final String fromEnvironment = environment.get(env);
      if (usable(fromEnvironment, skipBlank)) {
        return fromEnvironment;
      }
    }
    return null;
  }

  private static boolean usable(final @Nullable String value, final boolean skipBlank) {
    return value != null && !(skipBlank && value.isBlank());
  }

  /**
   * Returns a setting's value, or the empty string when none is set.
   *
   * @param arg the agent argument name
   * @param property the system property name
   * @param envs the environment variable names, the first found wins
   * @return the stripped value, or {@code ""}
   */
  public String valueOrEmpty(final String arg, final String property, final String... envs) {
    final String value = value(arg, property, envs);
    return value == null ? "" : value.strip();
  }
}

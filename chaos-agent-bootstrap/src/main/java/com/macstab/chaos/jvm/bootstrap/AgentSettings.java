package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.startup.AgentArgsParser;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads an agent setting: the agent argument, then the system property, then the environment. */
final class AgentSettings {

  private AgentSettings() {}

  /**
   * Returns a setting's value.
   *
   * @param agentArgs the raw agent arguments
   * @param environment the process environment
   * @param arg the agent argument name
   * @param property the system property name
   * @param env the environment variable name
   * @return the first value found, or {@code null} when none is set
   */
  static @Nullable String value(
      final String agentArgs,
      final Map<String, String> environment,
      final String arg,
      final String property,
      final String env) {
    final String fromArgs = AgentArgsParser.parse(agentArgs).get(arg);
    if (fromArgs != null) {
      return fromArgs;
    }
    final String fromProperty = System.getProperty(property);
    return fromProperty != null ? fromProperty : environment.get(env);
  }

  /**
   * Returns a setting's value, or the empty string when none is set.
   *
   * @param agentArgs the raw agent arguments
   * @param environment the process environment
   * @param arg the agent argument name
   * @param property the system property name
   * @param env the environment variable name
   * @return the stripped value, or {@code ""}
   */
  static String valueOrEmpty(
      final String agentArgs,
      final Map<String, String> environment,
      final String arg,
      final String property,
      final String env) {
    final String value = value(agentArgs, environment, arg, property, env);
    return value == null ? "" : value.strip();
  }
}

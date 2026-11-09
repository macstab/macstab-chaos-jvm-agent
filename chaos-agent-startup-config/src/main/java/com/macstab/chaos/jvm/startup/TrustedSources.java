package com.macstab.chaos.jvm.startup;

import com.macstab.chaos.jvm.api.Internal;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The trust rule for the switches that enable fault injection or a management endpoint, for the
 * Quarkus and Micronaut integrations (Spring decides with its own property sources).
 *
 * <p>Plan authors are trusted; a switch is honoured only when the config source it comes from is a
 * launch-time local one (system properties, environment variables, the application's own config
 * files), unless {@value #ALLOW_REMOTE_ENABLE_PROPERTY} itself comes from such a source. A remote
 * config server therefore cannot switch chaos on. A switch from any other source, including a
 * source a future framework release renames, is ignored (fails closed) with one log line naming the
 * source. Booleans accept {@code true|false|1|0|yes|no|on|off}; any other value leaves the switch
 * off with one warning.
 */
@Internal
public final class TrustedSources {

  /** Set through a trusted source, allows any source to set a switch. */
  public static final String ALLOW_REMOTE_ENABLE_PROPERTY = "macstab.chaos.allow-remote-enable";

  /** SmallRye's system-property, environment and test-override sources, and build-time values. */
  private static final Set<String> SMALLRYE_NAMES =
      Set.of(
          "SysPropConfigSource",
          "EnvConfigSource",
          "Config Override Config Source",
          "DefaultValuesConfigSource");

  /**
   * SmallRye's sources read from a local file or the class path: {@code .env}, {@code
   * application.properties} (in the jar and in {@code config/}), {@code
   * META-INF/microprofile-config.properties} and the files of {@code quarkus.config.locations}. A
   * location fetched over HTTP is not among them.
   */
  private static final Set<String> SMALLRYE_PREFIXES =
      Set.of(
          "EnvConfigSource[source=file:",
          "PropertiesConfigSource[source=file:",
          "PropertiesConfigSource[source=jar:");

  /**
   * Micronaut's system-property, environment, command-line and {@code ApplicationContext.run(Map)}
   * sources, the {@code application} file, and the {@code @Property} values of Micronaut Test.
   */
  private static final Set<String> MICRONAUT_NAMES =
      Set.of("system", "env", "cli", "context", "application", "test-properties");

  /** The lines already logged, so a switch read on every request is reported once. */
  private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

  private TrustedSources() {}

  /** A setting's raw value and the name of the config source it came from. */
  public record Sourced(String value, String source) {}

  /**
   * Whether a SmallRye (Quarkus) config source is launch-time local.
   *
   * @param sourceName the source's name, as {@code ConfigValue.getSourceName()} reports it
   * @return {@code true} for a trusted source
   */
  public static boolean smallRye(final String sourceName) {
    return SMALLRYE_NAMES.contains(sourceName)
        || SMALLRYE_PREFIXES.stream().anyMatch(sourceName::startsWith);
  }

  /**
   * Whether a Micronaut property source is launch-time local.
   *
   * @param sourceName the source's name
   * @param activeEnvironments the active environment names, for {@code application-<env>}
   * @param configFiles the value of {@code micronaut.config.files} from the system property or the
   *     environment variable, whose files are sources named by their path; {@code null} when unset
   * @return {@code true} for a trusted source
   */
  public static boolean micronaut(
      final String sourceName,
      final Collection<String> activeEnvironments,
      final @Nullable String configFiles) {
    if (MICRONAUT_NAMES.contains(sourceName)) {
      return true;
    }
    for (final String environment : activeEnvironments) {
      if (sourceName.equals("application-" + environment)) {
        return true;
      }
    }
    if (configFiles != null) {
      for (final String file : configFiles.split(",", -1)) {
        if (file.equals(sourceName)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether a switch is on: its value is true and comes from a trusted source, or {@value
   * #ALLOW_REMOTE_ENABLE_PROPERTY} is true and comes from one. A lookup that fails counts as an
   * untrusted source.
   *
   * @param property the switch
   * @param lookup finds a setting's value and source; {@code null} when the setting is absent
   * @param trusted whether a source is launch-time local
   * @return {@code true} when the switch is on
   */
  public static boolean switchedOn(
      final String property,
      final Function<String, @Nullable Sourced> lookup,
      final Predicate<String> trusted) {
    final Sourced sourced;
    try {
      sourced = lookup.apply(property);
    } catch (final RuntimeException failure) {
      report(
          Level.WARNING,
          "[chaos-agent] "
              + property
              + " could not be read and is treated as off: "
              + LogText.sanitize(failure.getMessage()));
      return false;
    }
    if (sourced == null || !isTrue(property, sourced.value())) {
      return false;
    }
    if (trusted.test(sourced.source()) || remoteAllowed(lookup, trusted)) {
      return true;
    }
    report(
        Level.INFO,
        "[chaos-agent] "
            + property
            + " comes from the config source '"
            + LogText.sanitize(sourced.source())
            + "', which is not a launch-time local source, and is ignored; set "
            + ALLOW_REMOTE_ENABLE_PROPERTY
            + "=true through a local source to allow it");
    return false;
  }

  /**
   * Reads a switch's value: {@code true|false|1|0|yes|no|on|off}. Any other value is off, with one
   * warning naming the switch.
   *
   * @param property the switch, for the warning
   * @param value the raw value; {@code null} is off
   * @return {@code true} when the value says on
   */
  public static boolean isTrue(final String property, final @Nullable String value) {
    final Boolean parsed = AgentArgs.parseBooleanQuietly(value);
    if (parsed == null && value != null) {
      report(
          Level.WARNING,
          "[chaos-agent] "
              + property
              + " has the unrecognised boolean value '"
              + LogText.sanitize(value)
              + "' and is off; expected true|false|1|0|yes|no|on|off");
    }
    return Boolean.TRUE.equals(parsed);
  }

  private static boolean remoteAllowed(
      final Function<String, @Nullable Sourced> lookup, final Predicate<String> trusted) {
    final Sourced allow;
    try {
      allow = lookup.apply(ALLOW_REMOTE_ENABLE_PROPERTY);
    } catch (final RuntimeException failure) {
      return false;
    }
    return allow != null
        && trusted.test(allow.source())
        && isTrue(ALLOW_REMOTE_ENABLE_PROPERTY, allow.value());
  }

  private static void report(final Level level, final String line) {
    if (REPORTED.add(line)) {
      // Looked up when a line is written: a logger initialises the LogManager, which premain must
      // leave to the application.
      Logger.getLogger(TrustedSources.class.getName()).log(level, line);
    }
  }
}

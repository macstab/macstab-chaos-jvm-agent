package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.startup.TrustedSources;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Reads a switch that enables fault injection or the management endpoint from Micronaut's property
 * sources, honouring it only from a launch-time local source ({@link TrustedSources}): system
 * properties, environment variables, the command line, {@code ApplicationContext.run(Map)}, the
 * {@code application} and {@code application-<env>} files, the files of {@code
 * micronaut.config.files} and Micronaut Test's {@code @Property} values.
 */
final class TrustedSwitch {

  /** Micronaut's setting for extra config files, read by Micronaut from these two only. */
  private static final String CONFIG_FILES_PROPERTY = Environment.PROPERTY_SOURCES_KEY;

  private static final String CONFIG_FILES_ENV = "MICRONAUT_CONFIG_FILES";

  private TrustedSwitch() {}

  /**
   * Whether {@code property} is on in {@code environment}.
   *
   * @param environment the environment
   * @param property the switch
   * @return {@code true} when the switch is true and its source may set it
   */
  static boolean on(final Environment environment, final String property) {
    final String configFiles =
        System.getProperty(CONFIG_FILES_PROPERTY, System.getenv(CONFIG_FILES_ENV));
    return TrustedSources.switchedOn(
        property,
        name -> sourced(environment, name),
        source -> TrustedSources.micronaut(source, environment.getActiveNames(), configFiles));
  }

  /**
   * The value of {@code property} and the source it comes from: the source of highest precedence
   * (highest order) that holds it, under the environment-variable spelling for the {@code env}
   * source.
   */
  private static TrustedSources.@Nullable Sourced sourced(
      final Environment environment, final String property) {
    final List<PropertySource> sources = new ArrayList<>(environment.getPropertySources());
    sources.sort(Comparator.comparingInt(PropertySource::getOrder).reversed());
    for (final PropertySource source : sources) {
      final String key =
          source.getConvention() == PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE
              ? property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT)
              : property;
      final Object value = source.get(key);
      if (value != null) {
        return new TrustedSources.Sourced(value.toString(), source.getName());
      }
    }
    return null;
  }
}

package com.macstab.chaos.jvm.quarkus.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The route's switches are honoured only from a launch-time local source. */
@DisplayName("ChaosManagementRecorder")
class ChaosManagementRecorderTest {

  @Test
  @DisplayName("allow-anonymous from a remote source (consul) is ignored")
  void remoteAllowAnonymousIgnored() {
    final Config config =
        config(source("consul", Map.of(ChaosManagementRecorder.ALLOW_ANONYMOUS_PROPERTY, "true")));

    assertThat(
            ChaosManagementRecorder.flag(config, ChaosManagementRecorder.ALLOW_ANONYMOUS_PROPERTY))
        .isFalse();
  }

  @Test
  @DisplayName("the route switch from a remote source is ignored")
  void remoteEnabledIgnored() {
    final Config config =
        config(source("consul", Map.of(ChaosManagementRecorder.ENABLED_PROPERTY, "true")));

    assertThat(ChaosManagementRecorder.flag(config, ChaosManagementRecorder.ENABLED_PROPERTY))
        .isFalse();
  }

  @Test
  @DisplayName("both switches from application.properties are honoured, also spelled on")
  void localSwitchesHonoured() {
    final Config config =
        config(
            source(
                "PropertiesConfigSource[source=jar:file:/srv/app.jar!/application.properties]",
                Map.of(
                    ChaosManagementRecorder.ENABLED_PROPERTY,
                    "on",
                    ChaosManagementRecorder.ALLOW_ANONYMOUS_PROPERTY,
                    "true")));

    assertThat(ChaosManagementRecorder.flag(config, ChaosManagementRecorder.ENABLED_PROPERTY))
        .isTrue();
    assertThat(
            ChaosManagementRecorder.flag(config, ChaosManagementRecorder.ALLOW_ANONYMOUS_PROPERTY))
        .isTrue();
  }

  @Test
  @DisplayName("a remote source is honoured once allow-remote-enable comes from a local source")
  void remoteAllowed() {
    final Config config =
        config(
            source("consul", Map.of(ChaosManagementRecorder.ENABLED_PROPERTY, "true")),
            source("EnvConfigSource", Map.of("macstab.chaos.allow-remote-enable", "true")));

    assertThat(ChaosManagementRecorder.flag(config, ChaosManagementRecorder.ENABLED_PROPERTY))
        .isTrue();
  }

  private static Config config(final ConfigSource... sources) {
    return new SmallRyeConfigBuilder().withSources(sources).build();
  }

  /** A config source with a given name, the way SmallRye names its own and remote ones. */
  private static ConfigSource source(final String name, final Map<String, String> values) {
    final Map<String, String> properties = new LinkedHashMap<>(values);
    return new ConfigSource() {
      @Override
      public Set<String> getPropertyNames() {
        return properties.keySet();
      }

      @Override
      public String getValue(final String propertyName) {
        return properties.get(propertyName);
      }

      @Override
      public String getName() {
        return name;
      }

      @Override
      public int getOrdinal() {
        return 300;
      }
    };
  }
}

package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The runtime-init step installs the agent only when macstab.chaos.enabled is true. */
@DisplayName("ChaosQuarkusRecorder")
class ChaosQuarkusRecorderTest {

  private String previous;

  @BeforeEach
  void clearProperty() {
    previous = System.getProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY);
    System.clearProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY);
  }

  @AfterEach
  void restoreProperty() {
    if (previous == null) {
      System.clearProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY);
    } else {
      System.setProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY, previous);
    }
  }

  @Test
  @DisplayName(
      "has one constructor, public and without parameters: Quarkus refuses a recorder whose"
          + " constructor it cannot determine")
  void singlePublicNoArgConstructor() {
    final java.lang.reflect.Constructor<?>[] constructors =
        ChaosQuarkusRecorder.class.getDeclaredConstructors();

    assertThat(constructors).hasSize(1);
    assertThat(constructors[0].getParameterCount()).isZero();
    assertThat(java.lang.reflect.Modifier.isPublic(constructors[0].getModifiers())).isTrue();
  }

  @Test
  @DisplayName("without macstab.chaos.enabled the agent is not installed")
  void disabledByDefault() {
    assertThat(new ChaosQuarkusRecorder().installAgent().getValue()).isFalse();
  }

  @Test
  @DisplayName("macstab.chaos.enabled=true installs the agent")
  void enabled() {
    System.setProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY, "true");
    assertThat(new ChaosQuarkusRecorder().installAgent().getValue()).isTrue();
  }

  @Test
  @DisplayName("a failed self-attach is logged and answers false, without failing the start")
  void failedAttachDoesNotAbort() {
    System.setProperty(ChaosQuarkusRecorder.ENABLED_PROPERTY, "true");
    for (final Throwable failure :
        List.of(
            new IllegalStateException("attach unavailable"),
            new UnsatisfiedLinkError("no attach library"))) {
      final AtomicInteger installs = new AtomicInteger();
      final List<LogRecord> records = new CopyOnWriteArrayList<>();
      final Handler handler =
          new Handler() {
            @Override
            public void publish(final LogRecord record) {
              records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
          };
      final Logger logger = Logger.getLogger(ChaosQuarkusRecorder.class.getName());
      logger.addHandler(handler);
      try {
        final Runnable failingInstall =
            () -> {
              installs.incrementAndGet();
              if (failure instanceof RuntimeException runtime) {
                throw runtime;
              }
              throw (Error) failure;
            };

        assertThat(ChaosQuarkusRecorder.installAgent(failingInstall).getValue())
            .as("%s", failure)
            .isFalse();
      } finally {
        logger.removeHandler(handler);
      }

      assertThat(installs).hasValue(1);
      assertThat(records)
          .singleElement()
          .satisfies(
              record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getMessage())
                    .isEqualTo("[chaos-agent] failed to self-attach during Quarkus runtime-init");
                assertThat(record.getThrown()).isSameAs(failure);
              });
    }
  }

  @Nested
  @DisplayName("the switch's source")
  class SwitchSource {

    @Test
    @DisplayName("a remote source (consul) does not switch chaos on")
    void remoteSourceIgnored() {
      assertThat(ChaosQuarkusRecorder.chaosEnabled(config("consul", "true"))).isFalse();
    }

    @Test
    @DisplayName("a remote source does so once allow-remote-enable comes from a local source")
    void remoteSourceAllowed() {
      final Config config =
          config(
              source("consul", Map.of(ChaosQuarkusRecorder.ENABLED_PROPERTY, "true")),
              source("SysPropConfigSource", Map.of("macstab.chaos.allow-remote-enable", "true")));
      assertThat(ChaosQuarkusRecorder.chaosEnabled(config)).isTrue();
    }

    @Test
    @DisplayName(
        "local sources switch chaos on: system property, env, application.properties, .env")
    void localSourcesHonoured() {
      for (final String name :
          new String[] {
            "SysPropConfigSource",
            "EnvConfigSource",
            "EnvConfigSource[source=file:/srv/app/.env]",
            "PropertiesConfigSource[source=jar:file:/srv/app/app.jar!/application.properties]",
            "PropertiesConfigSource[source=file:/srv/app/config/application.properties]",
            "Config Override Config Source",
            "DefaultValuesConfigSource"
          }) {
        assertThat(ChaosQuarkusRecorder.chaosEnabled(config(name, "true"))).as(name).isTrue();
      }
    }

    @Test
    @DisplayName("a properties file fetched over HTTP (quarkus.config.locations) is not local")
    void httpLocationIgnored() {
      assertThat(
              ChaosQuarkusRecorder.chaosEnabled(
                  config("PropertiesConfigSource[source=https://config/app.properties]", "true")))
          .isFalse();
    }

    @Test
    @DisplayName("accepts on and yes; an unrecognised value leaves chaos off")
    void booleanSpellings() {
      assertThat(ChaosQuarkusRecorder.chaosEnabled(config("SysPropConfigSource", "on"))).isTrue();
      assertThat(ChaosQuarkusRecorder.chaosEnabled(config("SysPropConfigSource", "yes"))).isTrue();
      assertThat(ChaosQuarkusRecorder.chaosEnabled(config("SysPropConfigSource", "tru"))).isFalse();
    }
  }

  private static Config config(final String sourceName, final String enabled) {
    return config(source(sourceName, Map.of(ChaosQuarkusRecorder.ENABLED_PROPERTY, enabled)));
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

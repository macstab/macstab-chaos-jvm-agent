package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrustedSources")
class TrustedSourcesTest {

  private static final String SWITCH = "macstab.chaos.test-switch";

  @Nested
  @DisplayName("SmallRye (Quarkus) sources")
  class SmallRye {

    @Test
    @DisplayName("system properties, environment, .env, local properties files and test overrides")
    void localSources() {
      assertThat(
              List.of(
                  "SysPropConfigSource",
                  "EnvConfigSource",
                  "EnvConfigSource[source=file:/srv/app/.env]",
                  "PropertiesConfigSource[source=jar:file:/srv/app.jar!/application.properties]",
                  "PropertiesConfigSource[source=file:/srv/app/config/application.properties]",
                  "Config Override Config Source",
                  "DefaultValuesConfigSource"))
          .allMatch(TrustedSources::smallRye);
    }

    @Test
    @DisplayName("not a remote source or a properties file fetched over HTTP")
    void remoteSources() {
      assertThat(
              List.of(
                  "consul",
                  "PropertiesConfigSource[source=https://config/application.properties]",
                  "EnvConfigSource[source=http://config/.env]"))
          .noneMatch(TrustedSources::smallRye);
    }
  }

  @Nested
  @DisplayName("Micronaut sources")
  class Micronaut {

    @Test
    @DisplayName("system, env, cli, context, the application files and Micronaut Test's @Property")
    void localSources() {
      assertThat(
              List.of(
                  "system",
                  "env",
                  "cli",
                  "context",
                  "application",
                  "application-test",
                  "test-properties"))
          .allMatch(name -> TrustedSources.micronaut(name, List.of("test"), null));
    }

    @Test
    @DisplayName("a file of micronaut.config.files, by its path")
    void configFiles() {
      assertThat(
              TrustedSources.micronaut("/etc/app/extra.yml", List.of(), "a.yml,/etc/app/extra.yml"))
          .isTrue();
      assertThat(TrustedSources.micronaut("/etc/app/other.yml", List.of(), "a.yml")).isFalse();
    }

    @Test
    @DisplayName("not a remote source, nor an application file of an inactive environment")
    void remoteSources() {
      assertThat(TrustedSources.micronaut("consul-application", List.of("test"), null)).isFalse();
      assertThat(TrustedSources.micronaut("application-prod", List.of("test"), null)).isFalse();
    }
  }

  @Nested
  @DisplayName("a switch")
  class Switch {

    private final Logger logger = Logger.getLogger(TrustedSources.class.getName());
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler capture =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            logged.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };

    @BeforeEach
    void captureLog() {
      logger.addHandler(capture);
    }

    @AfterEach
    void releaseLog() {
      logger.removeHandler(capture);
    }

    @Test
    @DisplayName("is off when absent")
    void absent() {
      assertThat(TrustedSources.switchedOn(SWITCH, lookup(Map.of()), name -> true)).isFalse();
      assertThat(logged).isEmpty();
    }

    @Test
    @DisplayName("is on from a trusted source, in every spelling of true")
    void trusted() {
      for (final String value : List.of("true", "1", "yes", "ON")) {
        assertThat(
                TrustedSources.switchedOn(
                    SWITCH, lookup(Map.of(SWITCH, sourced(value, "local"))), "local"::equals))
            .as(value)
            .isTrue();
      }
    }

    @Test
    @DisplayName("is off for false, and off with one warning for an unrecognised value")
    void falseAndUnrecognised() {
      assertThat(
              TrustedSources.switchedOn(
                  SWITCH, lookup(Map.of(SWITCH, sourced("off", "local"))), "local"::equals))
          .isFalse();
      for (int call = 0; call < 2; call++) {
        assertThat(
                TrustedSources.switchedOn(
                    SWITCH,
                    lookup(Map.of(SWITCH, sourced("tru\nforged", "local"))),
                    "local"::equals))
            .isFalse();
      }

      assertThat(logged)
          .singleElement()
          .satisfies(
              record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getMessage())
                    .contains(SWITCH)
                    .contains("tru�forged")
                    .doesNotContain("\n");
              });
    }

    @Test
    @DisplayName("is off from an untrusted source, with one line naming the source")
    void untrusted() {
      final String source = "consul-" + System.nanoTime();
      for (int call = 0; call < 2; call++) {
        assertThat(
                TrustedSources.switchedOn(
                    SWITCH, lookup(Map.of(SWITCH, sourced("true", source))), "local"::equals))
            .isFalse();
      }

      assertThat(logged)
          .singleElement()
          .satisfies(
              record -> {
                assertThat(record.getLevel()).isEqualTo(Level.INFO);
                assertThat(record.getMessage())
                    .contains(SWITCH)
                    .contains(source)
                    .contains(TrustedSources.ALLOW_REMOTE_ENABLE_PROPERTY);
              });
    }

    @Test
    @DisplayName("is on from an untrusted source once allow-remote-enable comes from a trusted one")
    void remoteAllowed() {
      assertThat(
              TrustedSources.switchedOn(
                  SWITCH,
                  lookup(
                      Map.of(
                          SWITCH,
                          sourced("true", "consul"),
                          TrustedSources.ALLOW_REMOTE_ENABLE_PROPERTY,
                          sourced("true", "local"))),
                  "local"::equals))
          .isTrue();
    }

    @Test
    @DisplayName("stays off when allow-remote-enable comes from the untrusted source too")
    void remoteAllowedOnlyFromTrusted() {
      assertThat(
              TrustedSources.switchedOn(
                  SWITCH,
                  lookup(
                      Map.of(
                          SWITCH,
                          sourced("true", "consul"),
                          TrustedSources.ALLOW_REMOTE_ENABLE_PROPERTY,
                          sourced("true", "consul"))),
                  "local"::equals))
          .isFalse();
    }

    @Test
    @DisplayName("is off when the lookup fails, for the switch or for allow-remote-enable")
    void lookupFails() {
      assertThat(
              TrustedSources.switchedOn(
                  SWITCH,
                  name -> {
                    throw new IllegalStateException("unresolvable ${expression}");
                  },
                  name -> true))
          .isFalse();
      assertThat(
              TrustedSources.switchedOn(
                  SWITCH,
                  name -> {
                    if (name.equals(SWITCH)) {
                      return sourced("true", "consul");
                    }
                    throw new IllegalStateException("unresolvable");
                  },
                  "local"::equals))
          .isFalse();
      assertThat(logged)
          .anySatisfy(record -> assertThat(record.getMessage()).contains("treated as off"));
    }
  }

  private static TrustedSources.Sourced sourced(final String value, final String source) {
    return new TrustedSources.Sourced(value, source);
  }

  private static Function<String, TrustedSources.@Nullable Sourced> lookup(
      final Map<String, TrustedSources.Sourced> values) {
    return values::get;
  }
}

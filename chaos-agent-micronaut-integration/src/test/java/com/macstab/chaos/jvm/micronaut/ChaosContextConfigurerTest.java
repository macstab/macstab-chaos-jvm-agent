package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micronaut.context.ApplicationContextBuilder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosContextConfigurer gate")
class ChaosContextConfigurerTest {

  private final AtomicInteger installs = new AtomicInteger();
  private final ChaosContextConfigurer configurer =
      new ChaosContextConfigurer(installs::incrementAndGet);
  private String previousValue;

  @BeforeEach
  void stashExistingFlag() {
    previousValue = System.getProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
    System.clearProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
  }

  @AfterEach
  void restoreExistingFlag() {
    if (previousValue == null) {
      System.clearProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
    } else {
      System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, previousValue);
    }
  }

  // The builder is never read; null keeps Micronaut's service-loader scanning out of the test.

  @Test
  @DisplayName("does not install when macstab.chaos.enabled is absent")
  void noInstallWhenFlagAbsent() {
    configurer.configure((ApplicationContextBuilder) null);

    assertThat(installs).hasValue(0);
  }

  @Test
  @DisplayName("does not install when macstab.chaos.enabled=false")
  void noInstallWhenFlagFalse() {
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "false");

    configurer.configure((ApplicationContextBuilder) null);

    assertThat(installs).hasValue(0);
  }

  @Test
  @DisplayName("installs once when macstab.chaos.enabled=true")
  void installsWhenFlagTrue() {
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "true");

    configurer.configure((ApplicationContextBuilder) null);

    assertThat(installs).hasValue(1);
  }

  @Test
  @DisplayName("a failing install is logged and does not abort the context")
  void failingInstallDoesNotAbort() {
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "true");

    final List<LogRecord> records =
        configureLogged(new IllegalStateException("attach unavailable"));

    assertThat(installs).hasValue(1);
    assertFailureLogged(records, IllegalStateException.class);
  }

  @Test
  @DisplayName("an install failing with a LinkageError is logged and does not abort the context")
  void linkageErrorDoesNotAbort() {
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "true");

    final List<LogRecord> records = configureLogged(new UnsatisfiedLinkError("no attach library"));

    assertThat(installs).hasValue(1);
    assertFailureLogged(records, UnsatisfiedLinkError.class);
  }

  /** One WARNING naming the self-attach failure, carrying the install's throwable. */
  private static void assertFailureLogged(
      final List<LogRecord> records, final Class<? extends Throwable> failure) {
    assertThat(records)
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.getLevel()).isEqualTo(Level.WARNING);
              assertThat(record.getMessage())
                  .isEqualTo(
                      "[chaos-agent] failed to self-attach during Micronaut context bootstrap");
              assertThat(record.getThrown()).isInstanceOf(failure);
            });
  }

  /**
   * Runs the configurer with an install that counts its call and throws {@code failure}; asserts
   * that nothing reaches the caller and returns what the configurer's logger published.
   */
  private List<LogRecord> configureLogged(final Throwable failure) {
    final Logger logger = Logger.getLogger(ChaosContextConfigurer.class.getName());
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
    final Runnable install =
        () -> {
          installs.incrementAndGet();
          if (failure instanceof RuntimeException runtime) {
            throw runtime;
          }
          throw (Error) failure;
        };
    logger.addHandler(handler);
    try {
      assertThatCode(
              () -> new ChaosContextConfigurer(install).configure((ApplicationContextBuilder) null))
          .doesNotThrowAnyException();
    } finally {
      logger.removeHandler(handler);
    }
    return records;
  }
}

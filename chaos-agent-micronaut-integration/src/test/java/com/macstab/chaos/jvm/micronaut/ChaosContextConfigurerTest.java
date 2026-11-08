package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micronaut.context.ApplicationContextBuilder;
import java.util.concurrent.atomic.AtomicInteger;
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

    new ChaosContextConfigurer(
            () -> {
              throw new IllegalStateException("attach unavailable");
            })
        .configure((ApplicationContextBuilder) null);
  }

  @Test
  @DisplayName("an install failing with a LinkageError is logged and does not abort the context")
  void linkageErrorDoesNotAbort() {
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "true");

    assertThatCode(
            () ->
                new ChaosContextConfigurer(
                        () -> {
                          throw new UnsatisfiedLinkError("no attach library");
                        })
                    .configure((ApplicationContextBuilder) null))
        .doesNotThrowAnyException();
  }
}

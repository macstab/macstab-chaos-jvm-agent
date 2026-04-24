package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.micronaut.context.ApplicationContextBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosContextConfigurer gate")
class ChaosContextConfigurerTest {

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

  // configure() does not dereference the builder when the gate is closed and passes it straight
  // through to ChaosPlatform.installLocally() (which also ignores it). We intentionally pass null
  // to avoid instantiating an ApplicationContextBuilder, which drags in Micronaut's internal
  // service-loader scanner and is irrelevant to the gate contract under test.

  @Test
  @DisplayName("is a no-op when macstab.chaos.enabled is absent")
  void noOpWhenFlagAbsent() {
    assertThatCode(() -> new ChaosContextConfigurer().configure((ApplicationContextBuilder) null))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("is a no-op when macstab.chaos.enabled=false")
  void noOpWhenFlagFalse() {
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "false");
    assertThatCode(() -> new ChaosContextConfigurer().configure((ApplicationContextBuilder) null))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("installs platform without throwing when macstab.chaos.enabled=true")
  void installsWhenFlagTrue() {
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "true");
    // Installation is idempotent; the only observable contract from this test's perspective is
    // that the configurer completes successfully when the gate is open. Deeper installation
    // semantics are exercised by the ChaosMicronautExtension integration test.
    assertThatCode(() -> new ChaosContextConfigurer().configure((ApplicationContextBuilder) null))
        .doesNotThrowAnyException();
  }
}

package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Clock call-site packages")
class ClockCallSitePackagesTest {

  @Test
  @DisplayName("keeps application prefixes and drops blanks")
  void keepsApplicationPrefixes() {
    assertThat(
            JdkInstrumentationInstaller.validatedClockPackages(
                Arrays.asList(" com.example. ", "", null, "org.acme.Clock")))
        .containsExactly("com.example.", "org.acme.Clock");
  }

  @Test
  @DisplayName("refuses prefixes that would rewrite JDK classes, including ones that cover them")
  void refusesJdkPrefixes() {
    for (final String prefix :
        List.of("java.", "java.lang", "jdk.internal.", "sun.", "com.sun.", "j", "com.")) {
      assertThatThrownBy(() -> JdkInstrumentationInstaller.validatedClockPackages(List.of(prefix)))
          .as(prefix)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("JDK");
    }
  }
}

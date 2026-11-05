package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("API exceptions")
class ChaosExceptionsTest {

  private static final IllegalStateException CAUSE = new IllegalStateException("root");

  @Test
  @DisplayName("each exception keeps its message and, where given, its cause")
  void messageAndCause() {
    assertThat(new ChaosActivationException("conflict")).hasMessage("conflict").hasNoCause();
    assertThat(new ChaosActivationException("conflict", CAUSE))
        .hasMessage("conflict")
        .hasCause(CAUSE);
    assertThat(new ChaosValidationException("invalid")).hasMessage("invalid").hasNoCause();
    assertThat(new ChaosUnsupportedFeatureException("unsupported"))
        .hasMessage("unsupported")
        .hasNoCause();
    assertThat(new ChaosJdbcSuppressException("suppressed")).hasMessage("suppressed").hasNoCause();
    assertThat(new ChaosJdbcSuppressException("suppressed", CAUSE))
        .hasMessage("suppressed")
        .hasCause(CAUSE);
    assertThat(new ChaosHttpSuppressException("suppressed", CAUSE))
        .hasMessage("suppressed")
        .hasCause(CAUSE);
  }
}

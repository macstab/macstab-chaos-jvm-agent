package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ActivationPolicy.builder()")
class ActivationPolicyBuilderTest {

  @Test
  @DisplayName("defaults equal always()")
  void defaultsEqualAlways() {
    assertThat(ActivationPolicy.builder().build()).isEqualTo(ActivationPolicy.always());
  }

  @Test
  @DisplayName("sets every field")
  void setsEveryField() {
    final ActivationPolicy policy =
        ActivationPolicy.builder()
            .startMode(ActivationPolicy.StartMode.MANUAL)
            .probability(0.2)
            .activateAfterMatches(3)
            .maxApplications(100L)
            .activeFor(Duration.ofSeconds(60))
            .rateLimit(5, Duration.ofSeconds(1))
            .randomSeed(null)
            .allowDestructiveEffects(true)
            .build();

    assertThat(policy)
        .isEqualTo(
            new ActivationPolicy(
                ActivationPolicy.StartMode.MANUAL,
                0.2,
                3,
                100L,
                Duration.ofSeconds(60),
                new ActivationPolicy.RateLimit(5, Duration.ofSeconds(1)),
                null,
                true));
  }

  @Test
  @DisplayName("validates like the constructor")
  void validates() {
    assertThatThrownBy(() -> ActivationPolicy.builder().probability(2.0).build())
        .isInstanceOf(IllegalArgumentException.class);
  }
}

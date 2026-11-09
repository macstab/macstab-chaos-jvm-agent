package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetAddress;
import java.time.Clock;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Seeded effect choices")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class EffectSeedTest {

  private static ScenarioController controller(final String id, final Long seed) {
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_WRITE)))
            .effect(ChaosEffect.tornWrite())
            .activationPolicy(ActivationPolicy.builder().randomSeed(seed).build())
            .build();
    return new ScenarioController(
        scenario,
        "jvm",
        null,
        Clock.systemUTC(),
        new ObservabilityBus(ChaosMetricsSink.NOOP),
        java.util.Optional::empty);
  }

  @Test
  @DisplayName("a fixed randomSeed reproduces every choice of every match")
  void reproducible() {
    final ScenarioController first = controller("torn", 42L);
    final ScenarioController second = controller("torn", 42L);
    for (long match = 1; match <= 50; match++) {
      assertThat(first.effectSeed(match, 1L)).isEqualTo(second.effectSeed(match, 1L));
    }
  }

  @Test
  @DisplayName("matches, kinds of choice and seeds give independent choices")
  void independent() {
    final ScenarioController controller = controller("torn", 42L);
    assertThat(controller.effectSeed(1L, 1L)).isNotEqualTo(controller.effectSeed(2L, 1L));
    assertThat(controller.effectSeed(1L, 1L)).isNotEqualTo(controller.effectSeed(1L, 2L));
    assertThat(controller.effectSeed(1L, 1L))
        .isNotEqualTo(controller("torn", 43L).effectSeed(1L, 1L));
  }

  @Test
  @DisplayName("the same shuffle seed gives the same DNS order")
  void shuffleReproducible() throws Exception {
    final InetAddress[] answer = new InetAddress[16];
    for (int i = 0; i < answer.length; i++) {
      answer[i] = InetAddress.getByAddress("h", new byte[] {10, 0, 0, (byte) i});
    }
    final InetAddress[] first =
        DnsAnswers.shape(ChaosEffect.dnsShuffle(), "h", answer.clone(), null, new Random(7L));
    final InetAddress[] second =
        DnsAnswers.shape(ChaosEffect.dnsShuffle(), "h", answer.clone(), null, new Random(7L));
    assertThat(first).containsExactly(second);
    assertThat(Arrays.asList(first)).containsExactlyInAnyOrder(answer);
  }
}

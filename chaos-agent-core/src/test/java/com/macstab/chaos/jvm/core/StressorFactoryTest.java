package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which background stressor each stressor effect starts. Every effect here is sized to the minimum
 * and the stressor is closed at once: the test pins the mapping, not the load.
 */
@DisplayName("Stressor factory")
class StressorFactoryTest {

  private final StressorFactory factory = new StressorFactory(Optional::empty);

  static Stream<Arguments> effects() {
    final Duration hour = Duration.ofHours(1);
    return Stream.of(
        Arguments.of(ChaosEffect.metaspacePressure(1, 1), MetaspacePressureStressor.class),
        Arguments.of(
            ChaosEffect.directBufferPressure(1024L, 1024), DirectBufferPressureStressor.class),
        Arguments.of(ChaosEffect.gcPressure(1024L, hour), GcPressureStressor.class),
        Arguments.of(
            ChaosEffect.finalizerBacklog(1, Duration.ofMillis(1)), FinalizerBacklogStressor.class),
        Arguments.of(
            ChaosEffect.threadLeak(1, "factory-test-leak-", true), ThreadLeakStressor.class),
        Arguments.of(ChaosEffect.threadLocalLeak(1, 16), ThreadLocalLeakStressor.class),
        Arguments.of(
            ChaosEffect.monitorContention(Duration.ofMillis(1), 2),
            MonitorContentionStressor.class),
        Arguments.of(ChaosEffect.codeCachePressure(1, 1), CodeCachePressureStressor.class),
        Arguments.of(ChaosEffect.safepointStorm(hour), SafepointStormStressor.class),
        Arguments.of(ChaosEffect.stringInternPressure(1, 8), StringInternPressureStressor.class),
        Arguments.of(ChaosEffect.referenceQueueFlood(1, hour), ReferenceQueueFloodStressor.class));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("effects")
  @DisplayName("each stressor effect starts its own stressor")
  void startsTheStressor(final ChaosEffect effect, final Class<?> expected) {
    final ManagedStressor stressor = factory.createIfNeeded(effect, true, ManagedStressor.NO_END);
    try {
      assertThat(stressor).isInstanceOf(expected);
    } finally {
      if (stressor != null) {
        stressor.close();
      }
    }
  }

  @Test
  @DisplayName("an effect without background load starts no stressor")
  void noStressorForACallEffect() {
    assertThat(factory.createIfNeeded(ChaosEffect.suppress(), true, ManagedStressor.NO_END))
        .isNull();
  }
}

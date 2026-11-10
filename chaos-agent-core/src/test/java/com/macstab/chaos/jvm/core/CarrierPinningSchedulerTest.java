package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Carrier pinning against a real virtual-thread scheduler, set by its JVM flag: on a single carrier
 * it is refused unless the scenario allows destructive effects (L17, L18).
 */
@DisplayName("Carrier pinning on a real scheduler")
class CarrierPinningSchedulerTest {

  private static String run(final int parallelism) throws Exception {
    return BoundedChildJvm.run(
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djdk.virtualThreadScheduler.parallelism=" + parallelism,
            "-cp",
            System.getProperty("java.class.path"),
            CarrierPinningProbeMain.class.getName()),
        Duration.ofSeconds(60));
  }

  @Test
  @DisplayName("a single carrier refuses it without allowDestructiveEffects and accepts it with")
  void singleCarrier() throws Exception {
    assertThat(run(1))
        .contains("carrier-pinning=refused")
        .contains("carrier-pinning-destructive=activated");
  }

  @Test
  @DisplayName("two carriers accept both")
  void twoCarriers() throws Exception {
    assertThat(run(2))
        .contains("carrier-pinning=activated")
        .contains("carrier-pinning-destructive=activated");
  }
}

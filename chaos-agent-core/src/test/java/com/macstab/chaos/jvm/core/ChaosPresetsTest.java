package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosPresets;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.NamePattern;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosPresets")
class ChaosPresetsTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private void activate(final ChaosScenario.Builder preset) {
    runtime.activate(preset.activationPolicy(ActivationPolicy.always()).build());
  }

  @Test
  @DisplayName("every preset passes activation and carries its name as a tag")
  void everyPresetActivates() {
    final List<ChaosScenario.Builder> presets =
        List.of(
            ChaosPresets.diskFull("/a"),
            ChaosPresets.failingDisk("/b"),
            ChaosPresets.fsyncFailure("/c"),
            ChaosPresets.slowDisk("/d", Duration.ofMillis(5)),
            ChaosPresets.tooManyOpenFiles("/e"),
            ChaosPresets.connectionRefused("10.0.0.1", 5432),
            ChaosPresets.connectionResets("10.0.0.2", null),
            ChaosPresets.slowNetwork("10.0.0.3", null, Duration.ofMillis(5)),
            ChaosPresets.trickledResponses("10.0.0.4", 80, 16),
            ChaosPresets.packetLoss("10.0.0.5", 53),
            ChaosPresets.failover("10.0.0.6", 5432, "localhost", 6543),
            ChaosPresets.dnsOutage(NamePattern.glob("*.internal")),
            ChaosPresets.clockJump(Duration.ofMinutes(-5)),
            ChaosPresets.threadExhaustion());
    for (final ChaosScenario.Builder preset : presets) {
      final ChaosScenario scenario = preset.build();
      assertThat(scenario.tags()).containsKey("preset");
      runtime.activate(scenario);
    }
    assertThat(runtime.diagnostics().snapshot().scenarios()).hasSize(presets.size());
  }

  @Test
  @DisplayName("diskFull fails a write under the directory with ENOSPC")
  void diskFull() {
    activate(ChaosPresets.diskFull("/var/lib/app"));
    assertThatThrownBy(() -> runtime.beforeFileIo("FILE_IO_WRITE", this, "/var/lib/app/data", 10))
        .isInstanceOf(java.nio.file.FileSystemException.class)
        .hasMessageContaining("No space left on device");
  }

  @Test
  @DisplayName("connectionRefused refuses a connect to the endpoint")
  void connectionRefused() {
    activate(ChaosPresets.connectionRefused("10.0.0.1", 5432));
    assertThatThrownBy(
            () ->
                runtime.beforeSocketConnect(
                    null,
                    new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 0, 1}), 5432),
                    0))
        .isInstanceOf(java.net.ConnectException.class);
  }

  @Test
  @DisplayName("clockJump moves every system clock read")
  void clockJump() {
    activate(ChaosPresets.clockJump(Duration.ofMinutes(5)));
    final Instant real = Instant.ofEpochSecond(1_000L);
    assertThat(runtime.adjustClockInstant(real)).isEqualTo(real.plus(Duration.ofMinutes(5)));
    assertThat(runtime.adjustInstantNow(real)).isEqualTo(real.plus(Duration.ofMinutes(5)));
  }

  @Test
  @DisplayName("the builder stays the caller's: id, scope and policy can be changed")
  void builderIsOpen() {
    final ChaosScenario scenario =
        ChaosPresets.packetLoss("10.0.0.5", 53)
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .activationPolicy(ActivationPolicy.builder().probability(0.2).build())
            .build();
    assertThat(scenario.scope()).isEqualTo(ChaosScenario.ScenarioScope.SESSION);
    assertThat(scenario.activationPolicy().probability()).isEqualTo(0.2);
    assertThat(scenario.id()).isEqualTo("packet-loss:10.0.0.5:53");
  }
}

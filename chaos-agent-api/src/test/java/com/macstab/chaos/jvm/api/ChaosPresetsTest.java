package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosPresets")
class ChaosPresetsTest {

  @Nested
  @DisplayName("file presets")
  class Files {

    @Test
    @DisplayName("diskFull fails writes under the directory with ENOSPC")
    void diskFull() {
      final ChaosScenario scenario = ChaosPresets.diskFull("/var/data").build();

      assertPreset(scenario, "diskFull", "disk-full:/var/data");
      assertThat(scenario.description())
          .isEqualTo("writes under /var/data fail: no space left on device");
      assertFiles(scenario, "/var/data", OperationType.FILE_IO_WRITE);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.errno(ChaosEffect.Errno.ENOSPC));
    }

    @Test
    @DisplayName("failingDisk fails reads, writes and syncs with EIO")
    void failingDisk() {
      final ChaosScenario scenario = ChaosPresets.failingDisk("/srv").build();

      assertPreset(scenario, "failingDisk", "failing-disk:/srv");
      assertThat(scenario.description())
          .isEqualTo("I/O under /srv fails with an input/output error");
      assertFiles(
          scenario,
          "/srv",
          OperationType.FILE_IO_READ,
          OperationType.FILE_IO_WRITE,
          OperationType.FILE_IO_SYNC);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.errno(ChaosEffect.Errno.EIO));
    }

    @Test
    @DisplayName("fsyncFailure fails only syncs with EIO")
    void fsyncFailure() {
      final ChaosScenario scenario = ChaosPresets.fsyncFailure("/wal").build();

      assertPreset(scenario, "fsyncFailure", "fsync-failure:/wal");
      assertThat(scenario.description()).isEqualTo("fsync under /wal fails");
      assertFiles(scenario, "/wal", OperationType.FILE_IO_SYNC);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.errno(ChaosEffect.Errno.EIO));
    }

    @Test
    @DisplayName("slowDisk delays reads, writes and syncs by the latency")
    void slowDisk() {
      final ChaosScenario scenario =
          ChaosPresets.slowDisk("/tmp/x", Duration.ofMillis(250)).build();

      assertPreset(scenario, "slowDisk", "slow-disk:/tmp/x");
      assertThat(scenario.description()).isEqualTo("I/O under /tmp/x is 250 ms slower");
      assertFiles(
          scenario,
          "/tmp/x",
          OperationType.FILE_IO_READ,
          OperationType.FILE_IO_WRITE,
          OperationType.FILE_IO_SYNC);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.delay(Duration.ofMillis(250)));
    }

    @Test
    @DisplayName("tooManyOpenFiles fails opens with EMFILE")
    void tooManyOpenFiles() {
      final ChaosScenario scenario = ChaosPresets.tooManyOpenFiles("/etc").build();

      assertPreset(scenario, "tooManyOpenFiles", "too-many-open-files:/etc");
      assertThat(scenario.description()).isEqualTo("opens under /etc fail: too many open files");
      assertFiles(scenario, "/etc", OperationType.FILE_IO_OPEN);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.errno(ChaosEffect.Errno.EMFILE));
    }
  }

  @Nested
  @DisplayName("network presets")
  class Network {

    @Test
    @DisplayName("connectionRefused refuses connects to the peer with ECONNREFUSED")
    void connectionRefused() {
      final ChaosScenario scenario = ChaosPresets.connectionRefused("10.0.0.0/8", 5432).build();

      assertPreset(scenario, "connectionRefused", "connection-refused:10.0.0.0/8:5432");
      assertThat(scenario.description()).isEqualTo("connects to 10.0.0.0/8:5432 are refused");
      assertPeer(scenario, "10.0.0.0/8", 5432, OperationType.SOCKET_CONNECT);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.errno(ChaosEffect.Errno.ECONNREFUSED));
    }

    @Test
    @DisplayName("an endpoint without a port is named by its block alone")
    void endpointWithoutPort() {
      final ChaosScenario scenario = ChaosPresets.connectionRefused("10.0.0.0/8", null).build();

      assertThat(scenario.id()).isEqualTo("connection-refused:10.0.0.0/8");
      assertThat(scenario.description()).isEqualTo("connects to 10.0.0.0/8 are refused");
      assertPeer(scenario, "10.0.0.0/8", null, OperationType.SOCKET_CONNECT);
    }

    @Test
    @DisplayName("connectionResets fails reads from the peer with ECONNRESET")
    void connectionResets() {
      final ChaosScenario scenario = ChaosPresets.connectionResets("192.0.2.1", 80).build();

      assertPreset(scenario, "connectionResets", "connection-resets:192.0.2.1:80");
      assertThat(scenario.description())
          .isEqualTo("reads from 192.0.2.1:80 fail: connection reset");
      assertPeer(scenario, "192.0.2.1", 80, OperationType.SOCKET_READ);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.errno(ChaosEffect.Errno.ECONNRESET));
    }

    @Test
    @DisplayName("slowNetwork delays reads and writes with the peer by the latency")
    void slowNetwork() {
      final ChaosScenario scenario =
          ChaosPresets.slowNetwork("192.0.2.0/24", 443, Duration.ofSeconds(2)).build();

      assertPreset(scenario, "slowNetwork", "slow-network:192.0.2.0/24:443");
      assertThat(scenario.description())
          .isEqualTo("socket I/O with 192.0.2.0/24:443 is 2000 ms slower");
      assertPeer(
          scenario, "192.0.2.0/24", 443, OperationType.SOCKET_READ, OperationType.SOCKET_WRITE);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.delay(Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("trickledResponses caps every read from the peer at maxBytes")
    void trickledResponses() {
      final ChaosScenario scenario = ChaosPresets.trickledResponses("::1", 8080, 3).build();

      assertPreset(scenario, "trickledResponses", "trickled-responses:::1:8080");
      assertThat(scenario.description()).isEqualTo("reads from ::1:8080 return at most 3 bytes");
      assertPeer(scenario, "::1", 8080, OperationType.SOCKET_READ);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.trickle(3));
    }

    @Test
    @DisplayName("packetLoss drops datagrams sent to the peer")
    void packetLoss() {
      final ChaosScenario scenario = ChaosPresets.packetLoss("198.51.100.0/24", 53).build();

      assertPreset(scenario, "packetLoss", "packet-loss:198.51.100.0/24:53");
      assertThat(scenario.description()).isEqualTo("datagrams to 198.51.100.0/24:53 are dropped");
      assertPeer(scenario, "198.51.100.0/24", 53, OperationType.DATAGRAM_SEND);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.suppress());
    }

    @Test
    @DisplayName("failover redirects connects to the peer to the target")
    void failover() {
      final ChaosScenario scenario =
          ChaosPresets.failover("10.1.0.0/16", 6379, "replica.local", 6380).build();

      assertPreset(scenario, "failover", "failover:10.1.0.0/16:6379");
      assertThat(scenario.description())
          .isEqualTo("connects to 10.1.0.0/16:6379 go to replica.local:6380");
      assertPeer(scenario, "10.1.0.0/16", 6379, OperationType.SOCKET_CONNECT);
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.redirect("replica.local", 6380));
    }

    @Test
    @DisplayName("dnsOutage rejects lookups of the matching names")
    void dnsOutage() {
      final NamePattern names = NamePattern.glob("*.internal");
      final ChaosScenario scenario = ChaosPresets.dnsOutage(names).build();

      assertPreset(scenario, "dnsOutage", "dns-outage:*.internal");
      assertThat(scenario.description()).isEqualTo("lookups of *.internal fail");
      assertThat(scenario.selector())
          .isEqualTo(ChaosSelector.dns(java.util.Set.of(OperationType.DNS_RESOLVE), names));
      assertThat(scenario.effect())
          .isEqualTo(ChaosEffect.reject("chaos: name or service not known"));
    }
  }

  @Nested
  @DisplayName("time and thread presets")
  class TimeAndThreads {

    @Test
    @DisplayName("clockJump skews every wall-clock read by a fixed offset")
    void clockJump() {
      final ChaosScenario scenario = ChaosPresets.clockJump(Duration.ofHours(1)).build();

      assertPreset(scenario, "clockJump", "clock-jump:PT1H");
      assertThat(scenario.description()).isEqualTo("the wall clock is PT1H off");
      assertThat(scenario.selector())
          .isEqualTo(
              ChaosSelector.jvmRuntime(
                  java.util.Set.of(
                      OperationType.INSTANT_NOW,
                      OperationType.CLOCK_READ,
                      OperationType.LOCAL_DATE_TIME_NOW,
                      OperationType.ZONED_DATE_TIME_NOW,
                      OperationType.DATE_NEW)));
      assertThat(scenario.effect())
          .isEqualTo(ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED));
    }

    @Test
    @DisplayName("threadExhaustion fails platform thread starts with EAGAIN")
    void threadExhaustion() {
      final ChaosScenario scenario = ChaosPresets.threadExhaustion().build();

      assertPreset(scenario, "threadExhaustion", "thread-exhaustion");
      assertThat(scenario.description())
          .isEqualTo("platform thread starts fail: unable to create native thread");
      assertThat(scenario.selector())
          .isEqualTo(
              ChaosSelector.thread(
                  java.util.Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.PLATFORM));
      assertThat(scenario.effect()).isEqualTo(ChaosEffect.errno(ChaosEffect.Errno.EAGAIN));
    }
  }

  @Test
  @DisplayName("a preset is a builder the caller can refine before building")
  void presetsAreRefinable() {
    final ChaosScenario scenario =
        ChaosPresets.diskFull("/d")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .activationPolicy(ActivationPolicy.builder().maxApplications(1L).build())
            .tag("team", "storage")
            .build();

    assertThat(scenario.scope()).isEqualTo(ChaosScenario.ScenarioScope.SESSION);
    assertThat(scenario.activationPolicy().maxApplications()).isEqualTo(1L);
    assertThat(scenario.tags())
        .containsEntry("preset", "diskFull")
        .containsEntry("team", "storage");
  }

  private static void assertPreset(
      final ChaosScenario scenario, final String preset, final String id) {
    assertThat(scenario.id()).isEqualTo(id);
    assertThat(scenario.tags()).containsExactly(java.util.Map.entry("preset", preset));
  }

  private static void assertFiles(
      final ChaosScenario scenario, final String directory, final OperationType... operations) {
    assertThat(scenario.selector())
        .isEqualTo(
            new ChaosSelector.FileIoSelector(
                java.util.Set.of(operations), PathPattern.under(directory), null, null));
  }

  private static void assertPeer(
      final ChaosScenario scenario,
      final String cidr,
      final Integer port,
      final OperationType... operations) {
    assertThat(scenario.selector())
        .isEqualTo(
            new ChaosSelector.NetworkSelector(
                java.util.Set.of(operations), NamePattern.any(), cidr, port, null, null));
  }
}

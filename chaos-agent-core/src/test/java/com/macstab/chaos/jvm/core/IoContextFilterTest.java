package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Selector filters on the call context: peer CIDR and port, byte count, file path. */
@DisplayName("I/O call-context filters")
class IoContextFilterTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Nested
  @DisplayName("network")
  class Network {

    @Test
    @DisplayName("a CIDR, port and size filter match only calls that satisfy all of them")
    void cidrPortAndSize() throws Throwable {
      runtime.activate(
          scenario(
              new ChaosSelector.NetworkSelector(
                  Set.of(OperationType.SOCKET_WRITE), null, "10.0.0.0/8", 5432, 1024L, null)));
      final InetAddress inside = InetAddress.getByName("10.1.2.3");
      final InetAddress outside = InetAddress.getByName("192.168.1.1");

      runtime.beforeSocketWrite(this, 4096, inside, 5432);
      runtime.beforeSocketWrite(this, 4096, outside, 5432);
      runtime.beforeSocketWrite(this, 4096, inside, 6379);
      runtime.beforeSocketWrite(this, 512, inside, 5432);
      runtime.beforeSocketWrite(this, 4096, null, 0);

      assertThat(matched()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a read is matched by the number of bytes requested")
    void readSize() throws Throwable {
      runtime.activate(
          scenario(
              new ChaosSelector.NetworkSelector(
                  Set.of(OperationType.SOCKET_READ), null, null, null, null, 100L)));
      final InetAddress peer = InetAddress.getLoopbackAddress();

      runtime.beforeSocketRead(this, 64, peer, 80);
      runtime.beforeSocketRead(this, 8192, peer, 80);

      assertThat(matched()).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("file I/O")
  class FileIo {

    @Test
    @DisplayName("a path and size filter match only calls under the path within the range")
    void pathAndSize() throws Throwable {
      runtime.activate(
          scenario(
              new ChaosSelector.FileIoSelector(
                  Set.of(OperationType.FILE_IO_WRITE),
                  PathPattern.under("/var/lib/app"),
                  null,
                  4096L)));

      runtime.beforeFileIo("FILE_IO_WRITE", this, "/var/lib/app/data/wal.log", 512);
      runtime.beforeFileIo("FILE_IO_WRITE", this, "/var/lib/application/x", 512);
      runtime.beforeFileIo("FILE_IO_WRITE", this, "/var/lib/app/big", 1_000_000);
      runtime.beforeFileIo("FILE_IO_WRITE", this, null, 512);

      assertThat(matched()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a selector without filters keeps matching every call")
    void unfiltered() throws Throwable {
      runtime.activate(scenario(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ))));

      runtime.beforeFileIo("FILE_IO_READ", this, null, InvocationContext.UNKNOWN);
      runtime.beforeFileIo("FILE_IO_READ", this, "/tmp/x", 1);

      assertThat(matched()).isEqualTo(2L);
    }
  }

  private long matched() {
    return runtime.diagnostics().scenario("io").orElseThrow().matchedCount();
  }

  private static ChaosScenario scenario(final ChaosSelector selector) {
    return ChaosScenario.builder("io")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(selector)
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}

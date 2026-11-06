package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The bounds of the network selector's filters, at their exact edges. */
class SelectorBoundaryTest {

  private static final Set<OperationType> WRITES = Set.of(OperationType.SOCKET_WRITE);

  @Test
  void portsFromZeroTo65535AreAccepted() {
    assertThat(network(0, null, null).remotePort()).isZero();
    assertThat(network(65_535, null, null).remotePort()).isEqualTo(65_535);
    assertThatThrownBy(() -> network(-1, null, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> network(65_536, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sizeRangesAreInclusiveAndNonNegative() {
    assertThat(network(null, 0L, 0L))
        .extracting(
            ChaosSelector.NetworkSelector::minBytes, ChaosSelector.NetworkSelector::maxBytes)
        .containsExactly(0L, 0L);
    assertThat(network(null, 7L, 7L))
        .extracting(
            ChaosSelector.NetworkSelector::minBytes, ChaosSelector.NetworkSelector::maxBytes)
        .containsExactly(7L, 7L);
    assertThat(network(null, 0L, null))
        .extracting(
            ChaosSelector.NetworkSelector::minBytes, ChaosSelector.NetworkSelector::maxBytes)
        .containsExactly(0L, null);
    assertThat(network(null, null, 0L))
        .extracting(
            ChaosSelector.NetworkSelector::minBytes, ChaosSelector.NetworkSelector::maxBytes)
        .containsExactly(null, 0L);
    assertThatThrownBy(() -> network(null, -1L, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> network(null, null, -1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> network(null, 8L, 7L)).isInstanceOf(IllegalArgumentException.class);
  }

  private static ChaosSelector.NetworkSelector network(
      final Integer port, final Long minBytes, final Long maxBytes) {
    return new ChaosSelector.NetworkSelector(WRITES, null, null, port, minBytes, maxBytes);
  }
}

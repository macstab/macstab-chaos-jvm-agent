package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The bounds of the network selector's filters, at their exact edges. */
class SelectorBoundaryTest {

  private static final Set<OperationType> WRITES = Set.of(OperationType.SOCKET_WRITE);

  @Test
  void portsFromZeroTo65535AreAccepted() {
    assertThatCode(() -> network(0, null, null)).doesNotThrowAnyException();
    assertThatCode(() -> network(65_535, null, null)).doesNotThrowAnyException();
    assertThatThrownBy(() -> network(-1, null, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> network(65_536, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sizeRangesAreInclusiveAndNonNegative() {
    assertThatCode(() -> network(null, 0L, 0L)).doesNotThrowAnyException();
    assertThatCode(() -> network(null, 7L, 7L)).doesNotThrowAnyException();
    assertThatCode(() -> network(null, 0L, null)).doesNotThrowAnyException();
    assertThatCode(() -> network(null, null, 0L)).doesNotThrowAnyException();
    assertThatThrownBy(() -> network(null, -1L, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> network(null, null, -1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> network(null, 8L, 7L)).isInstanceOf(IllegalArgumentException.class);
  }

  private static ChaosSelector.NetworkSelector network(
      final Integer port, final Long minBytes, final Long maxBytes) {
    return new ChaosSelector.NetworkSelector(WRITES, null, null, port, minBytes, maxBytes);
  }
}

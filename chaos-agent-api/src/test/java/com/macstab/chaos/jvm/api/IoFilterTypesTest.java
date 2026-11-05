package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("I/O filter types")
class IoFilterTypesTest {

  @Nested
  @DisplayName("CidrBlock")
  class Cidr {

    @ParameterizedTest(name = "{0} contains {1}: {2}")
    @CsvSource({
      "10.0.0.0/8, 10.255.1.2, true",
      "10.0.0.0/8, 11.0.0.1, false",
      "192.168.1.0/25, 192.168.1.127, true",
      "192.168.1.0/25, 192.168.1.128, false",
      "192.168.1.7, 192.168.1.7, true",
      "192.168.1.7, 192.168.1.8, false",
      "0.0.0.0/0, 8.8.8.8, true",
      "2001:db8::/32, 2001:db8:1::1, true",
      "2001:db8::/32, 2001:db9::1, false",
      "::1, ::1, true",
      "::ffff:10.0.0.0/104, ::ffff:10.1.2.3, true",
      "10.0.0.0/8, ::1, false",
    })
    void containment(final String block, final String address, final boolean expected)
        throws Exception {
      assertThat(CidrBlock.parse(block).contains(InetAddress.getByName(address)))
          .isEqualTo(expected);
    }

    @Test
    @DisplayName("normalises host bits away")
    void hostBitsMasked() {
      assertThat(CidrBlock.parse("10.1.2.3/8")).isEqualTo(CidrBlock.parse("10.0.0.0/8"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "",
          "10.0.0",
          "10.0.0.256",
          "10.0.0.0/33",
          "host.example",
          "1:2:3",
          "1::2::3",
          "2001:db8::/129",
          "10.0.0.0/x"
        })
    @DisplayName("rejects malformed blocks and host names")
    void rejects(final String block) {
      assertThatThrownBy(() -> CidrBlock.parse(block)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("never matches a null address")
    void nullAddress() {
      assertThat(CidrBlock.parse("0.0.0.0/0").contains(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("PathPattern")
  class Path {

    @ParameterizedTest(name = "{0} matches {1}: {2}")
    @CsvSource({
      "/var/data, /var/data, true",
      "/var/data, /var/data/a.db, true",
      "/var/data, /var/database, false",
      "/var/data/, /var/data/x, true",
      "/var/data, /var//data/./sub/../a.db, true",
      "/var/data, /var/other/../data/a, true",
      "/var/data, /var/data/../x, false",
      "/, /etc/hosts, true",
      "/var/data, var/data/a, false",
      "C:\\data, C:/data/file, true",
    })
    void matching(final String prefix, final String path, final boolean expected) {
      assertThat(PathPattern.under(prefix).matches(path)).isEqualTo(expected);
    }

    @Test
    @DisplayName("never matches a null path")
    void nullPath() {
      assertThat(PathPattern.under("/").matches(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("selector validation")
  class SelectorValidation {

    @Test
    @DisplayName("a size filter needs operations that carry a byte count")
    void sizeFilterNeedsSizedOperations() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.SOCKET_CONNECT), null, null, null, 1L, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("byte count");
    }

    @Test
    @DisplayName("rejects minBytes above maxBytes, a bad port and a bad CIDR")
    void rejectsBadValues() {
      assertThatThrownBy(
              () ->
                  new ChaosSelector.FileIoSelector(
                      Set.of(OperationType.FILE_IO_READ), null, 10L, 5L))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.SOCKET_READ), null, null, 70_000, null, null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.SOCKET_READ), null, "not-a-cidr", null, null, null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}

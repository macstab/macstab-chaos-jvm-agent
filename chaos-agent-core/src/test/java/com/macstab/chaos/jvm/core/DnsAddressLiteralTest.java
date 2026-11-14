package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DNS: which names the JDK takes as addresses without a lookup")
class DnsAddressLiteralTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "127.0.0.1",
        "0.0.0.0",
        "255.255.255.255",
        "010.1.1.1",
        "127.1",
        "1.2",
        "1.65535",
        "1.2.65535",
        "4294967295",
        "::1",
        "[::1]",
        "fe80::1%lo0",
        "1:2"
      })
  @DisplayName("an IPv4 literal (one to four decimal parts) or a name with ':' is a literal")
  void literals(final String name) {
    assertThat(DnsDispatch.isAddressLiteral(name)).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "localhost",
        "1abc.example",
        "1.2.3.4.5",
        "999.1.1.1",
        "1.2.3.256",
        "1.16777216",
        "4294967296",
        "1..2",
        ".1.2.3",
        "1.2.3.4.",
        "0x7f.0.0.1",
        "0000000000000001"
      })
  @DisplayName("anything else is a name the JDK looks up (or refuses, as 0x7f.0.0.1)")
  void names(final String name) {
    assertThat(DnsDispatch.isAddressLiteral(name)).isFalse();
  }

  @Test
  @DisplayName("null (getLocalHost) is looked up; the empty name is loopback without a lookup")
  void nullIsLookedUpEmptyIsNot() {
    assertThat(DnsDispatch.isAddressLiteral(null)).isFalse();
    assertThat(DnsDispatch.isAddressLiteral("")).isTrue();
  }
}

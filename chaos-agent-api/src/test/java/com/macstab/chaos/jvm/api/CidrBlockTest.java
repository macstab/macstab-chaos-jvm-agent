package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CidrBlock")
class CidrBlockTest {

  @Test
  @DisplayName("a block prints as the text it was parsed from, trimmed")
  void toStringIsTheText() {
    assertThat(CidrBlock.parse("  10.0.0.0/8 ")).hasToString("10.0.0.0/8");
    assertThat(CidrBlock.parse("10.1.2.3/8")).hasToString("10.1.2.3/8");
  }

  @Test
  @DisplayName("blocks equal by network compare and hash alike; another prefix hashes apart")
  void equalityAndHash() {
    final CidrBlock a = CidrBlock.parse("10.1.2.3/8");
    final CidrBlock b = CidrBlock.parse("10.0.0.0/8");
    final CidrBlock wider = CidrBlock.parse("10.0.0.0/7");

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(wider);
    assertThat(a.hashCode()).isNotEqualTo(wider.hashCode());
  }

  @Test
  @DisplayName("cached parses a block once and hands back the same one")
  void cachedReturnsTheParsedBlock() {
    // Whether or not an earlier call cached it, the cache must hand back one equal block.
    final String text = "10.231.17.0/24";

    final CidrBlock first = CidrBlock.cached(text);

    assertThat(first).isEqualTo(CidrBlock.parse(text));
    assertThat(CidrBlock.cached(text)).isSameAs(first);
  }

  @Test
  @DisplayName("only ::ffff:0:0/96 is IPv4-mapped; a look-alike under another prefix stays IPv6")
  void ipv4MappedNeedsTheZeroPrefix() throws Exception {
    final java.net.InetAddress ipv4 = java.net.InetAddress.getByName("10.0.0.1");

    assertThat(CidrBlock.parse("::ffff:10.0.0.0/120").contains(ipv4)).isTrue();
    assertThat(CidrBlock.parse("1::ffff:10.0.0.0/120").contains(ipv4)).isFalse();
    assertThat(CidrBlock.parse("1::ffff:10.0.0.0/120").prefixLength()).isEqualTo(120);
  }

  @Test
  @DisplayName("an IPv6 group holding a non-hex character is not an address")
  void nonHexGroup() {
    assertThatThrownBy(() -> CidrBlock.parse("fe80::zz"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an IPv4 or IPv6 literal: fe80::zz");
    assertThatThrownBy(() -> CidrBlock.parse("::+1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an IPv4 or IPv6 literal: ::+1");
  }
}

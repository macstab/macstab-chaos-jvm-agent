package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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
  @DisplayName("only ASCII digits are digits: other scripts' digits and signs are rejected")
  void asciiDigitsOnly() {
    // U+0661 U+0660 are ARABIC-INDIC DIGIT ONE and ZERO, U+0968 is DEVANAGARI DIGIT EIGHT.
    for (final String text :
        new String[] {"\u0661\u0660.0.0.1", "fe80::\u0661", "10.0.0.0/\u0968", "10.0.0.0/+8"}) {
      assertThatThrownBy(() -> CidrBlock.parse(text))
          .as(text)
          .isInstanceOf(IllegalArgumentException.class);
    }
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

  @Test
  @DisplayName("a missing or blank block and a missing address literal are rejected")
  void missingText() {
    assertThatThrownBy(() -> CidrBlock.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("CIDR block must be non-blank");
    assertThatThrownBy(() -> CidrBlock.parse(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("CIDR block must be non-blank");
    assertThatThrownBy(() -> CidrBlock.parseAddress(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not an IPv4 or IPv6 literal: null");
  }

  @Test
  @DisplayName("an IPv6 literal with an empty group around '::' or too many groups is rejected")
  void malformedCompressedIpv6() {
    for (final String literal : new String[] {":1::2", "1::2:", "1:2:3:4:5:6:7::8"}) {
      assertThatThrownBy(() -> CidrBlock.parseAddress(literal))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("not an IPv4 or IPv6 literal: " + literal);
    }
  }

  @Test
  @DisplayName("blocks of equal prefix but another network differ, and no block equals a string")
  void inequality() {
    assertThat(CidrBlock.parse("10.0.0.0/8")).isNotEqualTo(CidrBlock.parse("11.0.0.0/8"));
    assertThat(CidrBlock.parse("10.0.0.0/8")).isNotEqualTo((Object) "10.0.0.0/8");
  }

  @Test
  @DisplayName("past its capacity the cache parses each new block anew instead of keeping it")
  void cacheStopsGrowingAtCapacity() throws Exception {
    // A class loader of its own gives this test a fresh cache: filling the JVM-wide one would
    // change what every later test in this JVM observes.
    final URL classes = CidrBlock.class.getProtectionDomain().getCodeSource().getLocation();
    try (URLClassLoader isolated =
        new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
      final Method cached =
          isolated.loadClass(CidrBlock.class.getName()).getMethod("cached", String.class);
      for (int i = 0; i < 256; i++) {
        cached.invoke(null, "10.0." + i + ".0/24");
      }

      final Object kept = cached.invoke(null, "10.0.0.0/24");
      assertThat(cached.invoke(null, "10.0.0.0/24")).isSameAs(kept);
      final Object first = cached.invoke(null, "10.1.0.0/16");
      final Object second = cached.invoke(null, "10.1.0.0/16");
      assertThat(second).isEqualTo(first).isNotSameAs(first);
    }
  }
}

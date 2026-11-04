package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.instrumentation.bridge.SessionHeaderPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Session header host policy")
class SessionHeaderPolicyTest {

  @AfterEach
  void reset() {
    SessionHeaderPolicy.setExtraHosts(List.of());
  }

  @Test
  @DisplayName("sends the header to hosts inside the system under test only")
  void internalHostsOnly() {
    for (final String internal :
        List.of(
            "localhost",
            "127.0.0.1",
            "10.1.2.3",
            "172.20.0.5",
            "192.168.1.9",
            "169.254.0.1",
            "[::1]",
            "fd00::5",
            "orders",
            "db.svc",
            "api.orders.svc.cluster.local",
            "box.local",
            "x.internal")) {
      assertThat(SessionHeaderPolicy.allows(internal)).as(internal).isTrue();
    }
    for (final String external :
        List.of("api.stripe.com", "8.8.8.8", "172.32.0.1", "2001:db8::1", "example.org")) {
      assertThat(SessionHeaderPolicy.allows(external)).as(external).isFalse();
    }
    assertThat(SessionHeaderPolicy.allows(null)).isFalse();
  }

  @Test
  @DisplayName("adds configured globs, and * allows every host")
  void configuredHosts() {
    SessionHeaderPolicy.setExtraHosts(List.of(" *.Staging.Example.com ", ""));
    assertThat(SessionHeaderPolicy.allows("payments.staging.example.com")).isTrue();
    assertThat(SessionHeaderPolicy.allows("payments.example.com")).isFalse();
    SessionHeaderPolicy.setExtraHosts(List.of("*"));
    assertThat(SessionHeaderPolicy.allows("api.stripe.com")).isTrue();
    SessionHeaderPolicy.setExtraHosts(List.of("a*c*e.example.com"));
    assertThat(SessionHeaderPolicy.allows("abcde.example.com")).isTrue();
    assertThat(SessionHeaderPolicy.allows("abcdx.example.com")).isFalse();
  }

  @Test
  @DisplayName("treats the private and link-local address ranges as internal, their edges not")
  void addressRanges() {
    for (final String internal :
        List.of(
            "172.16.0.1", "172.31.255.1", "fc00::1", "fe80::1", "fe90::1", "fea0::1", "feb0::1")) {
      assertThat(SessionHeaderPolicy.allows(internal)).as(internal).isTrue();
    }
    for (final String external :
        List.of(
            "172.15.0.1",
            "192.167.0.1",
            "169.253.0.1",
            "11.0.0.1",
            "fec0::1",
            "10.0.0.x",
            "a.b.c.d",
            "[::1",
            "",
            "  ")) {
      assertThat(SessionHeaderPolicy.allows(external)).as("'%s'", external).isFalse();
    }
  }

  @Test
  @DisplayName("a glob without * is an exact name; each * part must appear in order")
  void globMatching() {
    SessionHeaderPolicy.setExtraHosts(List.of("api.partner.com"));
    assertThat(SessionHeaderPolicy.allows("api.partner.com")).isTrue();
    assertThat(SessionHeaderPolicy.allows("api.partner.com.evil.org")).isFalse();
    assertThat(SessionHeaderPolicy.allows("www.partner.com")).isFalse();
    SessionHeaderPolicy.setExtraHosts(List.of("a*q*e.example.com"));
    assertThat(SessionHeaderPolicy.allows("abcde.example.com")).as("no q").isFalse();
    SessionHeaderPolicy.setExtraHosts(List.of("ab*ba.example.com"));
    assertThat(SessionHeaderPolicy.allows("aba.example.com"))
        .as("the prefix and the suffix may not overlap")
        .isFalse();
    assertThat(SessionHeaderPolicy.allows("abba.example.com")).isTrue();
  }
}

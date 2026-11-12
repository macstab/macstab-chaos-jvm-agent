package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DNS answer shaping")
class DnsAnswersTest {

  private static InetAddress[] answer() throws Exception {
    return new InetAddress[] {
      InetAddress.getByAddress("h", new byte[] {10, 0, 0, 1}),
      InetAddress.getByAddress("h", new byte[] {10, 0, 0, 2}),
      InetAddress.getByAddress(
          "h", new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}),
    };
  }

  @Test
  @DisplayName("override answers with literals, even when the lookup failed")
  void override() throws Exception {
    final InetAddress[] shaped =
        shape(ChaosEffect.dnsOverride("192.0.2.7"), "svc", null, new UnknownHostException("svc"));

    assertThat(shaped).hasSize(1);
    assertThat(shaped[0].getHostAddress()).isEqualTo("192.0.2.7");
    assertThat(shaped[0].getHostName()).isEqualTo("svc");
  }

  @Test
  @DisplayName("a failed lookup without override keeps its failure")
  void failureKept() throws Exception {
    assertThat(shape(ChaosEffect.dnsShuffle(), "svc", null, new UnknownHostException("svc")))
        .isNull();
  }

  @Test
  @DisplayName("family filters, and an empty result is UnknownHostException")
  void family() throws Exception {
    assertThat(shape(ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV4), "h", answer(), null))
        .hasSize(2);
    assertThat(shape(ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV6), "h", answer(), null))
        .hasSize(1);
    assertThatThrownBy(
            () ->
                shape(
                    ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV6),
                    "h",
                    Arrays.copyOf(answer(), 2),
                    null))
        .isInstanceOf(UnknownHostException.class);
  }

  @Test
  @DisplayName(
      "an empty family filter fails through FailureFactory: the UnknownHostException starts at"
          + " the call site, without the agent's frames")
  void familyFailureAtCallSite() {
    assertThatThrownBy(
            () ->
                shape(
                    ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV6),
                    "h",
                    Arrays.copyOf(answer(), 2),
                    null))
        .isInstanceOf(UnknownHostException.class)
        .hasMessage("h: no IPV6 address in the answer")
        .satisfies(
            failure ->
                assertThat(failure.getStackTrace())
                    .noneMatch(frame -> frame.getClassName().equals(DnsAnswers.class.getName())));
  }

  @Test
  @DisplayName("limit keeps the first n, shuffle keeps the same addresses")
  void limitAndShuffle() throws Exception {
    assertThat(shape(ChaosEffect.dnsLimit(1), "h", answer(), null)).containsExactly(answer()[0]);
    assertThat(shape(ChaosEffect.dnsShuffle(), "h", answer(), null))
        .containsExactlyInAnyOrder(answer());
  }

  @Test
  @DisplayName("override rejects host names: no lookup happens to build the answer")
  void overrideRejectsNames() {
    assertThatThrownBy(() -> ChaosEffect.dnsOverride("example.com"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InetAddress[] shape(
      final ChaosEffect.DnsAnswerEffect effect,
      final String hostname,
      final InetAddress[] answer,
      final Throwable failure)
      throws java.net.UnknownHostException {
    return DnsAnswers.shape(effect, hostname, answer, failure, new java.util.Random(0L));
  }
}

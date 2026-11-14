package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * {@link CidrBlock} against the JDK's own address parser and against bit arithmetic: every IPv6
 * spelling of an address (full, compressed at any run of zero groups, with an embedded dotted quad)
 * parses to the bytes {@link InetAddress} gives it, and a block contains exactly the addresses that
 * share its first prefix-length bits.
 */
class CidrBlockPropertyTest {

  @Property(tries = 2000)
  void ipv6SpellingsParseLikeTheJdk(@ForAll("ipv6Spellings") final String literal)
      throws UnknownHostException {
    // A literal with a colon is never resolved: InetAddress parses it or throws. The JDK turns an
    // IPv4-mapped literal into its IPv4 address; CidrBlock keeps the 16 bytes and maps them when it
    // builds or matches a block.
    final byte[] jdk = InetAddress.getByName(literal).getAddress();
    final byte[] expected = jdk.length == 16 ? jdk : mapped(jdk);
    assertThat(CidrBlock.parseAddress(literal)).as(literal).isEqualTo(expected);
  }

  @Property(tries = 2000)
  void blockContainsExactlyTheAddressesSharingItsPrefix(
      @ForAll("addresses") final byte[] network,
      @ForAll("addresses") final byte[] candidate,
      @ForAll @IntRange(min = 0, max = 128) final int rawPrefix)
      throws UnknownHostException {
    // An IPv4-mapped network is an IPv4 block (ipv4MappedBlocksMatchTheirIpv4Addresses).
    net.jqwik.api.Assume.that(!isMapped(network));
    final byte[] other = Arrays.copyOf(candidate, network.length);
    final int prefix = Math.min(rawPrefix, network.length * 8);
    final CidrBlock block = CidrBlock.parse(address(network).getHostAddress() + "/" + prefix);

    assertThat(block.prefixLength()).isEqualTo(prefix);
    assertThat(block.contains(address(other))).isEqualTo(samePrefix(network, other, prefix));
    // The network address itself, and the address that differs in the first bit after the
    // prefix, are inside; the one that differs in the last bit of the prefix is not.
    assertThat(block.contains(address(network))).isTrue();
    if (prefix < network.length * 8) {
      assertThat(block.contains(address(flip(network, prefix)))).isTrue();
    }
    if (prefix > 0) {
      assertThat(block.contains(address(flip(network, prefix - 1)))).isFalse();
    }
  }

  @Property
  void prefixLengthsOutsideTheFamilyAreRejected(
      @ForAll @IntRange(min = 33, max = 200) final int v4) {
    assertThat(CidrBlock.parse("10.0.0.0/32").prefixLength()).isEqualTo(32);
    assertThat(CidrBlock.parse("::/128").prefixLength()).isEqualTo(128);
    assertThat(CidrBlock.parse("10.0.0.0/0").prefixLength()).isZero();
    assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/" + v4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CidrBlock.parse("::/" + (v4 + 96)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Property
  void ipv4MappedBlocksMatchTheirIpv4Addresses(
      @ForAll("ipv4") final byte[] address, @ForAll @IntRange(min = 0, max = 32) final int prefix)
      throws UnknownHostException {
    final String quad = InetAddress.getByAddress(address).getHostAddress();
    final CidrBlock mapped = CidrBlock.parse("::ffff:" + quad + "/" + (96 + prefix));

    assertThat(mapped).isEqualTo(CidrBlock.parse(quad + "/" + prefix));
    assertThat(mapped.contains(InetAddress.getByAddress(address))).isTrue();
    // Not mapped: the same low bits under another IPv6 prefix are an IPv6 block.
    assertThat(CidrBlock.parse("::fffe:" + quad + "/" + (96 + prefix)))
        .isNotEqualTo(CidrBlock.parse(quad + "/" + prefix));
  }

  @Property
  void malformedLiteralsAreRejected(@ForAll("malformed") final String literal) {
    assertThatThrownBy(() -> CidrBlock.parseAddress(literal))
        .as(literal)
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CidrBlock.parse(literal + "/8"))
        .as(literal)
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── generators ────────────────────────────────────────────────────────────

  @Provide
  Arbitrary<byte[]> addresses() {
    return Arbitraries.oneOf(ipv4(), ipv6());
  }

  @Provide
  Arbitrary<byte[]> ipv4() {
    return Arbitraries.bytes().array(byte[].class).ofSize(4);
  }

  private Arbitrary<byte[]> ipv6() {
    // Mostly zero groups, so the compressed spellings get long runs to compress.
    final Arbitrary<Integer> group =
        Arbitraries.frequencyOf(
            net.jqwik.api.Tuple.of(3, Arbitraries.just(0)),
            net.jqwik.api.Tuple.of(2, Arbitraries.integers().between(0, 0xFFFF)));
    return group
        .list()
        .ofSize(8)
        .map(
            groups -> {
              final byte[] bytes = new byte[16];
              for (int i = 0; i < 8; i++) {
                bytes[2 * i] = (byte) (groups.get(i) >>> 8);
                bytes[2 * i + 1] = (byte) (int) groups.get(i);
              }
              return bytes;
            });
  }

  @Provide
  Arbitrary<String> ipv6Spellings() {
    return Combinators.combine(
            ipv6(), Arbitraries.integers().between(0, 7), Arbitraries.of(true, false))
        .as(CidrBlockPropertyTest::spell);
  }

  @Provide
  Arbitrary<String> malformed() {
    return Arbitraries.of(
        "1:2:3:4:5:6:7",
        "1:2:3:4:5:6:7:8:9",
        "1::2::3",
        "12345::",
        "::g",
        ":1:2:3:4:5:6:7",
        "1:2:3:4:5:6:7:",
        "1:2:3:4:5:6:7::8",
        "::1.2.3",
        "::256.0.0.1",
        "1.2.3",
        "1.2.3.4.5",
        "1.2.3.256",
        "1..2.3",
        "01234.1.1.1",
        "a.b.c.d",
        "");
  }

  /** One spelling of {@code address}: full or with the zero run from {@code from} compressed. */
  private static String spell(final byte[] address, final int from, final boolean embedIpv4) {
    final List<String> groups = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      groups.add(Integer.toHexString(((address[2 * i] & 0xFF) << 8) | (address[2 * i + 1] & 0xFF)));
    }
    String tail = "";
    int groupCount = 8;
    if (embedIpv4) {
      tail =
          (address[12] & 0xFF)
              + "."
              + (address[13] & 0xFF)
              + "."
              + (address[14] & 0xFF)
              + "."
              + (address[15] & 0xFF);
      groupCount = 6;
    }
    int end = from;
    while (end < groupCount && groups.get(end).equals("0")) {
      end++;
    }
    final String text;
    if (end > from && from < groupCount) {
      text =
          String.join(":", groups.subList(0, from))
              + "::"
              + String.join(":", groups.subList(end, groupCount));
    } else {
      text = String.join(":", groups.subList(0, groupCount));
    }
    if (!embedIpv4) {
      return text;
    }
    return text.endsWith(":") ? text + tail : text + ":" + tail;
  }

  /**
   * The address of these bytes in their own family: {@link InetAddress#getByAddress} would turn 16
   * IPv4-mapped bytes into an IPv4 address.
   */
  private static InetAddress address(final byte[] bytes) throws UnknownHostException {
    return bytes.length == 16
        ? java.net.Inet6Address.getByAddress(null, bytes, -1)
        : InetAddress.getByAddress(bytes);
  }

  private static boolean isMapped(final byte[] bytes) {
    return bytes.length == 16
        && Arrays.equals(Arrays.copyOf(bytes, 12), Arrays.copyOf(mapped(new byte[4]), 12));
  }

  private static byte[] mapped(final byte[] ipv4) {
    final byte[] bytes = new byte[16];
    bytes[10] = (byte) 0xFF;
    bytes[11] = (byte) 0xFF;
    System.arraycopy(ipv4, 0, bytes, 12, 4);
    return bytes;
  }

  private static boolean samePrefix(final byte[] a, final byte[] b, final int prefix) {
    for (int bit = 0; bit < prefix; bit++) {
      if (bitAt(a, bit) != bitAt(b, bit)) {
        return false;
      }
    }
    return true;
  }

  private static int bitAt(final byte[] bytes, final int bit) {
    return (bytes[bit / 8] >>> (7 - bit % 8)) & 1;
  }

  private static byte[] flip(final byte[] bytes, final int bit) {
    final byte[] flipped = bytes.clone();
    flipped[bit / 8] ^= (byte) (0x80 >>> (bit % 8));
    return flipped;
  }
}

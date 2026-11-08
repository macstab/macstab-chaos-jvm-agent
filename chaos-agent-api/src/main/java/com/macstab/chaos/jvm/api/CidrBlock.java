package com.macstab.chaos.jvm.api;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * A block of IP addresses in CIDR notation: {@code "10.0.0.0/8"}, {@code "2001:db8::/32"}, or a
 * single address such as {@code "192.168.1.10"} (a {@code /32} or {@code /128} block).
 *
 * <p>Parsing never resolves a name: only IPv4 dotted-quad and IPv6 literals are accepted, so
 * building a selector cannot trigger a DNS lookup (or a DNS chaos scenario). IPv4 and IPv6 are
 * separate families: an IPv4 block never contains an IPv6 address and vice versa. The JDK reports
 * an IPv4-mapped IPv6 peer ({@code ::ffff:a.b.c.d}) as an IPv4 address, so an IPv4-mapped block
 * with a prefix of at least 96 bits is parsed as the IPv4 block it maps.
 */
public final class CidrBlock {

  /**
   * Bound on the parse cache; distinct blocks come from plans, so this is generous. A full cache is
   * emptied before the next block is cached.
   */
  private static final int CACHE_CAPACITY = 256;

  private static final Map<String, CidrBlock> CACHE = new ConcurrentHashMap<>();

  private final String text;
  private final byte[] network;
  private final int prefixLength;

  private CidrBlock(final String text, final byte[] network, final int prefixLength) {
    this.text = text;
    this.network = network;
    this.prefixLength = prefixLength;
  }

  /**
   * Parses a CIDR block.
   *
   * @param text an IPv4 or IPv6 literal, optionally followed by {@code /prefixLength}
   * @return the block
   * @throws IllegalArgumentException if {@code text} is not a valid block
   */
  public static CidrBlock parse(final String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CIDR block must be non-blank");
    }
    final String trimmed = text.trim();
    final int slash = trimmed.indexOf('/');
    final String addressPart = slash < 0 ? trimmed : trimmed.substring(0, slash);
    final byte[] address =
        addressPart.indexOf(':') >= 0 ? parseIpv6(addressPart) : parseIpv4(addressPart);
    if (address == null) {
      throw new IllegalArgumentException("not an IPv4 or IPv6 literal: " + text);
    }
    final int maxPrefix = address.length * 8;
    final int prefix;
    if (slash < 0) {
      prefix = maxPrefix;
    } else {
      final String prefixText = trimmed.substring(slash + 1);
      if (prefixText.isEmpty() || prefixText.length() > 3 || !isAsciiDigits(prefixText)) {
        throw new IllegalArgumentException("invalid CIDR prefix length: " + text);
      }
      prefix = Integer.parseInt(prefixText);
      if (prefix > maxPrefix) {
        throw new IllegalArgumentException(
            "CIDR prefix length must be in [0, " + maxPrefix + "]: " + text);
      }
    }
    if (address.length == 16 && prefix >= 96 && isIpv4Mapped(address)) {
      return new CidrBlock(
          trimmed, mask(Arrays.copyOfRange(address, 12, 16), prefix - 96), prefix - 96);
    }
    return new CidrBlock(trimmed, mask(address, prefix), prefix);
  }

  private static boolean isIpv4Mapped(final byte[] address) {
    for (int i = 0; i < 10; i++) {
      if (address[i] != 0) {
        return false;
      }
    }
    return address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
  }

  /**
   * Returns the parsed block for {@code text}, parsing it once and caching the result.
   *
   * @param text the block in CIDR notation
   * @return the block
   * @throws IllegalArgumentException if {@code text} is not a valid block
   */
  @Internal
  public static CidrBlock cached(final String text) {
    return cached(CACHE, text);
  }

  /** Parses {@code text} through {@code cache}; package-private as a test seam. */
  static CidrBlock cached(final Map<String, CidrBlock> cache, final String text) {
    final CidrBlock cached = cache.get(text);
    if (cached != null) {
      return cached;
    }
    final CidrBlock parsed = parse(text);
    // Selectors look blocks up on every evaluation: a full cache is emptied so a live plan's
    // blocks are cached again. Concurrent clears cost re-parses only.
    if (cache.size() >= CACHE_CAPACITY) {
      cache.clear();
    }
    final CidrBlock winner = cache.putIfAbsent(text, parsed);
    return winner != null ? winner : parsed;
  }

  /**
   * Parses an IPv4 or IPv6 literal without resolving anything.
   *
   * @param literal the address, e.g. {@code 10.0.0.5} or {@code 2001:db8::1}
   * @return its bytes (4 or 16)
   * @throws IllegalArgumentException if {@code literal} is not an address literal
   */
  @Internal
  public static byte[] parseAddress(final String literal) {
    final byte[] address =
        literal == null
            ? null
            : literal.indexOf(':') >= 0 ? parseIpv6(literal.trim()) : parseIpv4(literal.trim());
    if (address == null) {
      throw new IllegalArgumentException("not an IPv4 or IPv6 literal: " + literal);
    }
    return address;
  }

  /**
   * Returns whether {@code address} lies in this block.
   *
   * @param address the address; {@code null} never matches
   * @return {@code true} if the address is in the block
   */
  public boolean contains(final @Nullable InetAddress address) {
    if (address == null) {
      return false;
    }
    final byte[] candidate = address.getAddress();
    if (candidate.length != network.length) {
      return false;
    }
    final int fullBytes = prefixLength / 8;
    for (int i = 0; i < fullBytes; i++) {
      if (candidate[i] != network[i]) {
        return false;
      }
    }
    final int remainingBits = prefixLength % 8;
    if (remainingBits == 0) {
      return true;
    }
    final int byteMask = 0xFF << (8 - remainingBits) & 0xFF;
    return (candidate[fullBytes] & byteMask) == (network[fullBytes] & byteMask);
  }

  /**
   * Returns the prefix length in bits.
   *
   * @return the prefix length
   */
  public int prefixLength() {
    return prefixLength;
  }

  @Override
  public String toString() {
    return text;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof CidrBlock block
        && prefixLength == block.prefixLength
        && Arrays.equals(network, block.network);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(network) + prefixLength;
  }

  private static byte[] mask(final byte[] address, final int prefix) {
    final byte[] masked = address.clone();
    for (int bit = prefix; bit < masked.length * 8; bit++) {
      masked[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
    }
    return masked;
  }

  private static byte @Nullable [] parseIpv4(final String text) {
    final String[] parts = text.split("\\.", -1);
    if (parts.length != 4) {
      return null;
    }
    final byte[] bytes = new byte[4];
    for (int i = 0; i < 4; i++) {
      final String part = parts[i];
      if (part.isEmpty() || part.length() > 3 || !isAsciiDigits(part)) {
        return null;
      }
      final int value = Integer.parseInt(part);
      if (value > 255) {
        return null;
      }
      bytes[i] = (byte) value;
    }
    return bytes;
  }

  private static byte @Nullable [] parseIpv6(final String text) {
    String body = text;
    byte[] embeddedIpv4 = null;
    final int lastColon = body.lastIndexOf(':');
    if (body.indexOf('.', lastColon) >= 0) {
      embeddedIpv4 = parseIpv4(body.substring(lastColon + 1));
      if (embeddedIpv4 == null) {
        return null;
      }
      // Replace the dotted quad with two placeholder groups so the group count works out.
      body = body.substring(0, lastColon + 1) + "0:0";
    }
    final int doubleColon = body.indexOf("::");
    if (doubleColon >= 0 && body.indexOf("::", doubleColon + 1) >= 0) {
      return null;
    }
    final String[] head;
    final String[] tail;
    if (doubleColon >= 0) {
      head = splitGroups(body.substring(0, doubleColon));
      tail = splitGroups(body.substring(doubleColon + 2));
      if (head == null || tail == null || head.length + tail.length > 7) {
        return null;
      }
    } else {
      head = splitGroups(body);
      tail = new String[0];
      if (head == null || head.length != 8) {
        return null;
      }
    }
    final int[] groups = new int[8];
    for (int i = 0; i < head.length; i++) {
      groups[i] = parseGroup(head[i]);
    }
    for (int i = 0; i < tail.length; i++) {
      groups[8 - tail.length + i] = parseGroup(tail[i]);
    }
    final byte[] bytes = new byte[16];
    for (int i = 0; i < 8; i++) {
      if (groups[i] < 0) {
        return null;
      }
      bytes[2 * i] = (byte) (groups[i] >>> 8);
      bytes[2 * i + 1] = (byte) groups[i];
    }
    if (embeddedIpv4 != null) {
      System.arraycopy(embeddedIpv4, 0, bytes, 12, 4);
    }
    return bytes;
  }

  private static String @Nullable [] splitGroups(final String text) {
    if (text.isEmpty()) {
      return new String[0];
    }
    final String[] groups = text.split(":", -1);
    for (final String group : groups) {
      if (group.isEmpty()) {
        return null;
      }
    }
    return groups;
  }

  private static int parseGroup(final String group) {
    if (group.length() > 4) {
      return -1;
    }
    for (int i = 0; i < group.length(); i++) {
      final char c = group.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
        return -1;
      }
    }
    return Integer.parseInt(group, 16);
  }

  /** Address literals use ASCII digits only; {@link Character#isDigit} accepts every script's. */
  private static boolean isAsciiDigits(final String text) {
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }
}

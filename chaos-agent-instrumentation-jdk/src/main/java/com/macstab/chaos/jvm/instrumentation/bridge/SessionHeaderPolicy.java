package com.macstab.chaos.jvm.instrumentation.bridge;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Decides which hosts receive the {@code X-Chaos-Session} header. By default only hosts inside the
 * system under test: loopback, private and link-local addresses, single-label names (Kubernetes
 * services, Docker Compose services) and the {@code .local}, {@code .localhost}, {@code .internal},
 * {@code .svc} and {@code .cluster.local} domains. A session id sent to a third party would leak
 * test identity and, worse, let that party join the session if it ran the agent.
 *
 * <p>The agent setting {@code sessionHeaderHosts} adds host globs ({@code *.staging.example.com});
 * {@code *} sends the header everywhere. Loaded by the bootstrap class loader with {@link
 * BootstrapDispatcher}: HTTP client advice in JDK classes calls it.
 */
public final class SessionHeaderPolicy {

  private static final List<String> INTERNAL_SUFFIXES =
      List.of(".local", ".localhost", ".internal", ".svc", ".cluster.local");

  private static volatile List<String> extraHosts = List.of();

  private SessionHeaderPolicy() {}

  /**
   * Sets the additional host globs from the agent setting {@code sessionHeaderHosts}.
   *
   * @param globs host globs, {@code *} matching any run of characters; {@code *} alone allows all
   */
  public static void setExtraHosts(final List<String> globs) {
    extraHosts =
        globs.stream()
            .map(glob -> glob.strip().toLowerCase(Locale.ROOT))
            .filter(glob -> !glob.isEmpty())
            .toList();
  }

  /**
   * Returns whether a request to {@code host} may carry the session header.
   *
   * @param host the request's host name or address literal; {@code null} never
   * @return {@code true} when the host is internal or matches a configured glob
   */
  public static boolean allows(final String host) {
    if (host == null || host.isBlank()) {
      return false;
    }
    String name = host.strip().toLowerCase(Locale.ROOT);
    if (name.startsWith("[") && name.endsWith("]")) {
      name = name.substring(1, name.length() - 1);
    }
    for (final String glob : extraHosts) {
      if (matchesGlob(glob, name)) {
        return true;
      }
    }
    if (name.equals("localhost") || isInternalAddress(name)) {
      return true;
    }
    if (name.indexOf('.') < 0 && name.indexOf(':') < 0) {
      return true; // single label: a service name inside the cluster or compose network
    }
    for (final String suffix : INTERNAL_SUFFIXES) {
      if (name.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isInternalAddress(final String name) {
    if (name.indexOf(':') >= 0) {
      return name.equals("::1")
          || name.startsWith("fc")
          || name.startsWith("fd")
          || name.startsWith("fe8")
          || name.startsWith("fe9")
          || name.startsWith("fea")
          || name.startsWith("feb");
    }
    final int[] octets = ipv4Octets(name);
    if (octets == null) {
      return false;
    }
    return octets[0] == 10
        || octets[0] == 127
        || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
        || (octets[0] == 192 && octets[1] == 168)
        || (octets[0] == 169 && octets[1] == 254);
  }

  /**
   * The four octets of an IPv4 literal: four dot-separated runs of one to three ASCII digits, each
   * 0 to 255. Read by hand: {@code Integer.parseInt} takes a sign and non-ASCII digits, and throws
   * for every host name of four labels.
   *
   * @return the octets, or {@code null} when {@code name} is no IPv4 literal
   */
  private static int @Nullable [] ipv4Octets(final String name) {
    final int[] octets = new int[4];
    int octet = 0;
    int digits = 0;
    int value = 0;
    for (int i = 0; i <= name.length(); i++) {
      final char c = i == name.length() ? '.' : name.charAt(i);
      if (c >= '0' && c <= '9' && digits < 3) {
        value = value * 10 + (c - '0');
        digits++;
      } else if (c == '.' && digits > 0 && value <= 255 && octet < 4) {
        octets[octet++] = value;
        digits = 0;
        value = 0;
      } else {
        return null;
      }
    }
    return octet == 4 ? octets : null;
  }

  private static boolean matchesGlob(final String glob, final String name) {
    if (glob.equals("*")) {
      return true;
    }
    final String[] parts = glob.split("\\*", -1);
    int position = 0;
    for (int i = 0; i < parts.length; i++) {
      final String part = parts[i];
      if (i == 0) {
        if (!name.startsWith(part)) {
          return false;
        }
        position = part.length();
      } else if (i == parts.length - 1) {
        return name.length() - part.length() >= position && name.endsWith(part);
      } else {
        final int found = name.indexOf(part, position);
        if (found < 0) {
          return false;
        }
        position = found + part.length();
      }
    }
    return position == name.length();
  }
}

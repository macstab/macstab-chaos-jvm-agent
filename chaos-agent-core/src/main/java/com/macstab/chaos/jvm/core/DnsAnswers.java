package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.CidrBlock;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Applies a {@link ChaosEffect.DnsAnswerEffect} to a DNS answer. */
final class DnsAnswers {
  private DnsAnswers() {}

  /**
   * Reshapes a DNS answer: override, family filter, shuffle, limit, in that order.
   *
   * @param effect the reshaping
   * @param hostname the name looked up; the host name of override addresses
   * @param answer the resolver's answer, or {@code null} when it failed
   * @param failure the resolver's failure, or {@code null}
   * @return the new answer, or {@code null} to keep a failure no override replaces
   * @throws UnknownHostException if the family filter leaves no address
   */
  static InetAddress[] shape(
      final ChaosEffect.DnsAnswerEffect effect,
      final String hostname,
      final InetAddress[] answer,
      final Throwable failure)
      throws UnknownHostException {
    final List<InetAddress> addresses = new ArrayList<>();
    if (effect.override() != null) {
      for (final String literal : effect.override()) {
        addresses.add(InetAddress.getByAddress(hostname, CidrBlock.parseAddress(literal)));
      }
    } else if (failure != null || answer == null) {
      return null;
    } else {
      Collections.addAll(addresses, answer);
    }
    if (effect.family() != null) {
      addresses.removeIf(
          address ->
              effect.family() == ChaosEffect.AddressFamily.IPV4
                  ? !(address instanceof Inet4Address)
                  : !(address instanceof Inet6Address));
      if (addresses.isEmpty()) {
        throw new UnknownHostException(
            hostname + ": no " + effect.family() + " address in the answer");
      }
    }
    if (effect.shuffle()) {
      Collections.shuffle(addresses, ThreadLocalRandom.current());
    }
    if (effect.limit() != null && addresses.size() > effect.limit()) {
      addresses.subList(effect.limit(), addresses.size()).clear();
    }
    return addresses.toArray(new InetAddress[0]);
  }
}

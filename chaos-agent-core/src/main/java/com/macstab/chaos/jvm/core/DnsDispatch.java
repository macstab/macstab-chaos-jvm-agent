package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * Dispatch of DNS lookups: forward resolution, the answer returned, and reverse lookups.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class DnsDispatch {

  private final DispatchEngine engine;

  DnsDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Called before a reverse lookup of {@code address}.
   *
   * @param address the IP literal looked up
   * @throws Throwable a {@link java.net.UnknownHostException} when a scenario fails the lookup
   */
  public void beforeDnsReverse(final String address) throws Throwable {
    if (engine.idle(OperationType.DNS_REVERSE)) {
      return;
    }
    engine.applyPreDecision(
        engine.evaluate(
            new InvocationContext(
                OperationType.DNS_REVERSE,
                "java.net.InetAddress",
                null,
                address,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId())));
  }

  /**
   * Called before {@link java.net.InetAddress#getByName(String)}, {@link
   * java.net.InetAddress#getAllByName(String)}, or {@link java.net.InetAddress#getLocalHost()}.
   *
   * <p>An IP literal or the empty name is not evaluated: the JDK parses it, or returns the loopback
   * address, without a lookup ({@link #isAddressLiteral}).
   *
   * @param hostname the hostname being resolved; {@code null} for {@code getLocalHost()}
   * @return the name to resolve: {@code hostname}, or another under a {@code DnsRewriteEffect}
   * @throws Throwable if an active scenario throws to simulate a DNS failure
   */
  public @Nullable String beforeDnsResolve(final @Nullable String hostname) throws Throwable {
    if (engine.idle(OperationType.DNS_RESOLVE) || isAddressLiteral(hostname)) {
      return hostname;
    }
    final RuntimeDecision decision =
        engine.evaluate(
            new InvocationContext(
                OperationType.DNS_RESOLVE,
                "java.net.InetAddress",
                null,
                hostname,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId()));
    if (decision.terminalAction() instanceof TerminalAction.DnsRewrite rewrite) {
      engine.applyDelayAndGate(decision);
      return rewrite.host();
    }
    engine.applyPreDecision(decision);
    return hostname;
  }

  /**
   * Called when {@code InetAddress.getAllByName} returns or fails, to reshape the answer under a
   * {@code DnsAnswerEffect} (override, family filter, shuffle, limit).
   *
   * @param hostname the name that was looked up
   * @param answer the resolver's answer, or {@code null} when the lookup failed
   * @param failure the lookup's {@link java.net.UnknownHostException}, or {@code null}
   * @return the answer to return instead, or {@code null} to keep the original outcome; always
   *     {@code null} for an IP literal, which has no looked-up answer
   * @throws Throwable if a scenario rejects the lookup or a filter leaves no address
   */
  public java.net.InetAddress @Nullable [] afterDnsAnswer(
      final String hostname,
      final java.net.InetAddress @Nullable [] answer,
      final @Nullable Throwable failure)
      throws Throwable {
    if (engine.idle(OperationType.DNS_ANSWER) || isAddressLiteral(hostname)) {
      return null;
    }
    final RuntimeDecision decision =
        engine.evaluate(
            new InvocationContext(
                OperationType.DNS_ANSWER,
                "java.net.InetAddress",
                null,
                hostname,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId()));
    engine.applyDelayAndGate(decision);
    final TerminalAction terminal = decision.terminalAction();
    if (terminal == null) {
      return null;
    }
    if (terminal instanceof TerminalAction.Throw thrown) {
      throw thrown.failure();
    }
    if (!(terminal instanceof TerminalAction.DnsAnswer reshaping)) {
      return null;
    }
    final DnsShape shape = reshaping.shape();
    return DnsAnswers.shape(
        shape.effect(), hostname, answer, failure, new java.util.Random(shape.seed()));
  }

  /**
   * Whether the JDK takes {@code name} as an address without a lookup: the empty name (the loopback
   * address, as {@code getByName("")} returns it), an IPv6 literal (any name with a {@code ':'},
   * bracketed or not, scoped or not) or an IPv4 literal as {@code
   * IPAddressUtil.textToNumericFormatV4} accepts it: at most 15 characters, one to four
   * dot-separated decimal parts, the last filling the remaining bytes ({@code 127.1}, {@code 1.2},
   * {@code 010.1.1.1}). {@code 1.2.3.4.5} and {@code 999.1.1.1} are names the JDK looks up. A part
   * in hex ({@code 0x7f.0.0.1}) is not a literal here: the JDK refuses it without a lookup, but it
   * is evaluated, and fails with the same {@code UnknownHostException} a reject throws.
   * Allocation-free.
   *
   * @param name the name resolved; {@code null} is not a literal: it stands for {@code
   *     getLocalHost()}, which looks its name up ({@code getByName(null)} reaches here as the empty
   *     name)
   * @return {@code true} for an IP literal
   */
  static boolean isAddressLiteral(final @Nullable String name) {
    if (name == null) {
      return false;
    }
    if (name.isEmpty()) {
      return true;
    }
    if (name.indexOf(':') >= 0) {
      return true;
    }
    final int length = name.length();
    if (length > 15) {
      return false;
    }
    long part = -1L;
    int dots = 0;
    for (int i = 0; i < length; i++) {
      final char c = name.charAt(i);
      if (c == '.') {
        if (part < 0L || part > 0xffL || dots == 3) {
          return false;
        }
        dots++;
        part = -1L;
      } else {
        final int digit = Character.digit(c, 10);
        if (digit < 0) {
          return false;
        }
        part = (part < 0L ? 0L : part * 10L) + digit;
      }
    }
    // The last part fills the bytes the dotted parts left: 4, 3, 2 or 1 of them.
    return part >= 0L && part <= (0xffffffffL >>> (8 * dots));
  }
}

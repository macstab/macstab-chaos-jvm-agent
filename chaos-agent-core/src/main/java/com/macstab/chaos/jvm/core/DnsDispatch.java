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
   * @param hostname the hostname being resolved; {@code null} for {@code getLocalHost()}
   * @return the name to resolve: {@code hostname}, or another under a {@code DnsRewriteEffect}
   * @throws Throwable if an active scenario throws to simulate a DNS failure
   */
  public @Nullable String beforeDnsResolve(final @Nullable String hostname) throws Throwable {
    final RuntimeDecision decision =
        (engine.idle(OperationType.DNS_RESOLVE)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.DNS_RESOLVE,
                    "java.net.InetAddress",
                    null,
                    hostname,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
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
   * @return the answer to return instead, or {@code null} to keep the original outcome
   * @throws Throwable if a scenario rejects the lookup or a filter leaves no address
   */
  public java.net.InetAddress @Nullable [] afterDnsAnswer(
      final String hostname,
      final java.net.InetAddress @Nullable [] answer,
      final @Nullable Throwable failure)
      throws Throwable {
    if (engine.idle(OperationType.DNS_ANSWER)) {
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
}

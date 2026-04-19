package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.StringLength;

/**
 * Property-based tests for {@link SelectorMatcher}.
 *
 * <p>Each property encodes an algebraic invariant: if a selector declares it covers an operation
 * type, it must match any context carrying that type; if it does not declare it, it must never
 * match. These invariants must hold for all generated inputs.
 */
class SelectorMatcherPropertyTest {

  // ── FileIoSelector ──────────────────────────────────────────────────────────

  @Property
  void fileIoSelectorWithReadAlwaysMatchesReadContext(
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 80) String className) {
    final ChaosSelector selector = ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ));
    final InvocationContext ctx =
        new InvocationContext(
            OperationType.FILE_IO_READ, className, null, null, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("FileIoSelector(READ) must match any FILE_IO_READ context")
        .isTrue();
  }

  @Property
  void fileIoSelectorWithWriteAlwaysMatchesWriteContext(
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 80) String className) {
    final ChaosSelector selector = ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE));
    final InvocationContext ctx =
        new InvocationContext(
            OperationType.FILE_IO_WRITE, className, null, null, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("FileIoSelector(WRITE) must match any FILE_IO_WRITE context")
        .isTrue();
  }

  @Property
  void fileIoSelectorWithReadNeverMatchesWriteContext(
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 80) String className) {
    final ChaosSelector selector = ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ));
    final InvocationContext ctx =
        new InvocationContext(
            OperationType.FILE_IO_WRITE, className, null, null, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("FileIoSelector(READ) must never match FILE_IO_WRITE context")
        .isFalse();
  }

  @Property
  void fileIoSelectorWithBothOpsMatchesBothDirections(
      @ForAll("fileIoOpType") OperationType opType,
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 80) String className) {
    final ChaosSelector selector =
        ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ, OperationType.FILE_IO_WRITE));
    final InvocationContext ctx =
        new InvocationContext(opType, className, null, null, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("FileIoSelector(READ+WRITE) must match any file I/O op (%s)", opType)
        .isTrue();
  }

  @Provide
  Arbitrary<OperationType> fileIoOpType() {
    return Arbitraries.of(OperationType.FILE_IO_READ, OperationType.FILE_IO_WRITE);
  }

  // ── DnsSelector ────────────────────────────────────────────────────────────

  @Property
  void dnsSelectorWithAnyPatternMatchesAllHostnames(
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 80) String hostname) {
    final ChaosSelector selector =
        ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.any());
    final InvocationContext ctx =
        new InvocationContext(
            OperationType.DNS_RESOLVE, "InetAddress", null, hostname, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("DnsSelector(any) must match any hostname '%s'", hostname)
        .isTrue();
  }

  @Property
  void dnsSelectorWithExactPatternMatchesOnlyThatHostname(
      @ForAll @AlphaChars @NotEmpty @StringLength(min = 5, max = 20) String host,
      @ForAll @AlphaChars @NotEmpty @StringLength(min = 5, max = 20) String otherHost) {
    // Only test case where hosts are different (shrinking may produce equal ones)
    net.jqwik.api.Assume.that(!host.equals(otherHost));

    final ChaosSelector selector =
        ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.exact(host));
    final InvocationContext ctxMatch =
        new InvocationContext(
            OperationType.DNS_RESOLVE, "InetAddress", null, host, false, null, null, null);
    final InvocationContext ctxNoMatch =
        new InvocationContext(
            OperationType.DNS_RESOLVE, "InetAddress", null, otherHost, false, null, null, null);

    assertThat(SelectorMatcher.matches(selector, ctxMatch))
        .as("exact hostname '%s' should match itself", host)
        .isTrue();
    assertThat(SelectorMatcher.matches(selector, ctxNoMatch))
        .as("exact hostname '%s' should not match '%s'", host, otherHost)
        .isFalse();
  }

  @Property
  void dnsSelectorNeverMatchesNonDnsOperations(
      @ForAll("nonDnsOpType") OperationType opType,
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 40) String hostname) {
    final ChaosSelector selector =
        ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.any());
    final InvocationContext ctx =
        new InvocationContext(opType, "SomeClass", null, hostname, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("DnsSelector must not match non-DNS op %s", opType)
        .isFalse();
  }

  @Property
  void dnsSelectorDoesNotMatchWhenHostnameIsNull() {
    // A DNS context may arrive with a null hostname (e.g. getByAddress path before the
    // name is resolved). An exact-match selector must not match null — matching null would
    // be silently permissive and inject into unintended DNS operations.
    final ChaosSelector selector =
        ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.exact("example.com"));
    final InvocationContext ctx =
        new InvocationContext(
            OperationType.DNS_RESOLVE, "InetAddress", null, null, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("DnsSelector(exact) must not match a null hostname")
        .isFalse();
  }

  @Provide
  Arbitrary<OperationType> nonDnsOpType() {
    // Cover every non-DNS op in OperationType — hand-curated lists become stale as new
    // ops are added. Arbitraries.of + filter keeps coverage automatic.
    return Arbitraries.of(OperationType.values()).filter(op -> op != OperationType.DNS_RESOLVE);
  }

  // ── SslSelector ────────────────────────────────────────────────────────────

  @Property
  void sslSelectorAlwaysMatchesSslHandshakeContext(
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 80) String engineClassName) {
    final ChaosSelector selector = ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE));
    final InvocationContext ctx =
        new InvocationContext(
            OperationType.SSL_HANDSHAKE, engineClassName, null, null, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("SslSelector must match any SSL_HANDSHAKE context")
        .isTrue();
  }

  @Property
  void sslSelectorNeverMatchesNonSslOperations(@ForAll("nonSslOpType") OperationType opType) {
    final ChaosSelector selector = ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE));
    final InvocationContext ctx =
        new InvocationContext(opType, "SSLEngineImpl", null, null, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("SslSelector must not match non-SSL op %s", opType)
        .isFalse();
  }

  @Provide
  Arbitrary<OperationType> nonSslOpType() {
    return Arbitraries.of(OperationType.values()).filter(op -> op != OperationType.SSL_HANDSHAKE);
  }

  // ── ThreadSelector (THREAD_SLEEP) ──────────────────────────────────────────

  @Property
  void threadSelectorWithAnyPatternMatchesAnySleepContext(
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 60) String threadName) {
    final ChaosSelector selector =
        ChaosSelector.thread(Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY);
    final InvocationContext ctx =
        new InvocationContext(
            OperationType.THREAD_SLEEP, threadName, null, threadName, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("ThreadSelector(ANY) must match any THREAD_SLEEP context")
        .isTrue();
  }

  @Property
  void threadSelectorWithSleepNeverMatchesNonSleepOps(
      @ForAll("nonSleepOpType") OperationType opType,
      @ForAll @AlphaChars @NotEmpty @StringLength(max = 40) String threadName) {
    final ChaosSelector selector =
        ChaosSelector.thread(Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY);
    final InvocationContext ctx =
        new InvocationContext(opType, threadName, null, threadName, false, null, null, null);
    assertThat(SelectorMatcher.matches(selector, ctx))
        .as("ThreadSelector(SLEEP) must not match op %s", opType)
        .isFalse();
  }

  @Provide
  Arbitrary<OperationType> nonSleepOpType() {
    return Arbitraries.of(OperationType.values()).filter(op -> op != OperationType.THREAD_SLEEP);
  }
}

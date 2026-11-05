package com.macstab.chaos.jvm.api;

import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Ready-made scenarios for the faults production systems actually meet: a full or failing disk, a
 * refused or slow peer, packet loss, a DNS outage, a clock jump, exhausted descriptors or threads.
 * Each is the JVM counterpart of a rule of the native chaos libraries.
 *
 * <p>Every preset returns a prefilled {@link ChaosScenario.Builder} — id, description, selector,
 * effect, and the tag {@code preset=<name>} — so the scope, the activation policy and the id stay
 * the caller's:
 *
 * <pre>{@code
 * chaos.activate(
 *     ChaosPresets.diskFull("/var/lib/app")
 *         .activationPolicy(ActivationPolicy.builder().probability(0.1).build())
 *         .build());
 * }</pre>
 */
public final class ChaosPresets {

  private static final String TAG = "preset";

  private ChaosPresets() {}

  private static ChaosScenario.Builder preset(
      final String name,
      final String id,
      final String description,
      final ChaosSelector selector,
      final ChaosEffect effect) {
    return ChaosScenario.builder(id)
        .description(description)
        .selector(selector)
        .effect(effect)
        .tag(TAG, name);
  }

  private static ChaosSelector files(final String directory, final OperationType... operations) {
    return new ChaosSelector.FileIoSelector(
        Set.of(operations), PathPattern.under(directory), null, null);
  }

  private static ChaosSelector peer(
      final String cidr, final @Nullable Integer port, final OperationType... operations) {
    return new ChaosSelector.NetworkSelector(
        Set.of(operations), NamePattern.any(), cidr, port, null, null);
  }

  private static String endpoint(final String cidr, final @Nullable Integer port) {
    return cidr + (port == null ? "" : ":" + port);
  }

  // ── Files ────────────────────────────────────────────────────────────────

  /**
   * Writes under {@code directory} fail with {@code ENOSPC} ({@code /dir:write:ENOSPC}).
   *
   * @param directory the directory, matched at path boundaries
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder diskFull(final String directory) {
    return preset(
        "diskFull",
        "disk-full:" + directory,
        "writes under " + directory + " fail: no space left on device",
        files(directory, OperationType.FILE_IO_WRITE),
        ChaosEffect.errno(ChaosEffect.Errno.ENOSPC));
  }

  /**
   * Reads, writes and fsyncs under {@code directory} fail with {@code EIO}: a failing disk.
   *
   * @param directory the directory
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder failingDisk(final String directory) {
    return preset(
        "failingDisk",
        "failing-disk:" + directory,
        "I/O under " + directory + " fails with an input/output error",
        files(
            directory,
            OperationType.FILE_IO_READ,
            OperationType.FILE_IO_WRITE,
            OperationType.FILE_IO_SYNC),
        ChaosEffect.errno(ChaosEffect.Errno.EIO));
  }

  /**
   * Fsyncs under {@code directory} fail with {@code EIO}: the write-back error a database must not
   * ignore.
   *
   * @param directory the directory
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder fsyncFailure(final String directory) {
    return preset(
        "fsyncFailure",
        "fsync-failure:" + directory,
        "fsync under " + directory + " fails",
        files(directory, OperationType.FILE_IO_SYNC),
        ChaosEffect.errno(ChaosEffect.Errno.EIO));
  }

  /**
   * Reads, writes and fsyncs under {@code directory} take {@code latency} longer.
   *
   * @param directory the directory
   * @param latency the added latency
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder slowDisk(final String directory, final Duration latency) {
    return preset(
        "slowDisk",
        "slow-disk:" + directory,
        "I/O under " + directory + " is " + latency.toMillis() + " ms slower",
        files(
            directory,
            OperationType.FILE_IO_READ,
            OperationType.FILE_IO_WRITE,
            OperationType.FILE_IO_SYNC),
        ChaosEffect.delay(latency));
  }

  /**
   * Opens under {@code directory} fail with {@code EMFILE}: the process is out of descriptors.
   *
   * @param directory the directory
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder tooManyOpenFiles(final String directory) {
    return preset(
        "tooManyOpenFiles",
        "too-many-open-files:" + directory,
        "opens under " + directory + " fail: too many open files",
        files(directory, OperationType.FILE_IO_OPEN),
        ChaosEffect.errno(ChaosEffect.Errno.EMFILE));
  }

  // ── Network ──────────────────────────────────────────────────────────────

  /**
   * Connects to {@code cidr} (and {@code port}) are refused ({@code ECONNREFUSED}).
   *
   * @param cidr the peers, a CIDR block or one address
   * @param port the peer port, or {@code null} for any
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder connectionRefused(
      final String cidr, final @Nullable Integer port) {
    return preset(
        "connectionRefused",
        "connection-refused:" + endpoint(cidr, port),
        "connects to " + endpoint(cidr, port) + " are refused",
        peer(cidr, port, OperationType.SOCKET_CONNECT),
        ChaosEffect.errno(ChaosEffect.Errno.ECONNREFUSED));
  }

  /**
   * Reads from {@code cidr} fail with {@code ECONNRESET}: the peer drops connections.
   *
   * @param cidr the peers
   * @param port the peer port, or {@code null} for any
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder connectionResets(
      final String cidr, final @Nullable Integer port) {
    return preset(
        "connectionResets",
        "connection-resets:" + endpoint(cidr, port),
        "reads from " + endpoint(cidr, port) + " fail: connection reset",
        peer(cidr, port, OperationType.SOCKET_READ),
        ChaosEffect.errno(ChaosEffect.Errno.ECONNRESET));
  }

  /**
   * Reads from and writes to {@code cidr} take {@code latency} longer.
   *
   * @param cidr the peers
   * @param port the peer port, or {@code null} for any
   * @param latency the added latency
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder slowNetwork(
      final String cidr, final @Nullable Integer port, final Duration latency) {
    return preset(
        "slowNetwork",
        "slow-network:" + endpoint(cidr, port),
        "socket I/O with " + endpoint(cidr, port) + " is " + latency.toMillis() + " ms slower",
        peer(cidr, port, OperationType.SOCKET_READ, OperationType.SOCKET_WRITE),
        ChaosEffect.delay(latency));
  }

  /**
   * Reads from {@code cidr} return at most {@code maxBytes}: responses arrive in small pieces.
   *
   * @param cidr the peers
   * @param port the peer port, or {@code null} for any
   * @param maxBytes the most bytes one read returns
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder trickledResponses(
      final String cidr, final @Nullable Integer port, final int maxBytes) {
    return preset(
        "trickledResponses",
        "trickled-responses:" + endpoint(cidr, port),
        "reads from " + endpoint(cidr, port) + " return at most " + maxBytes + " bytes",
        peer(cidr, port, OperationType.SOCKET_READ),
        ChaosEffect.trickle(maxBytes));
  }

  /**
   * Datagrams to {@code cidr} are dropped. Set the probability for partial loss.
   *
   * @param cidr the destinations
   * @param port the destination port, or {@code null} for any
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder packetLoss(final String cidr, final @Nullable Integer port) {
    return preset(
        "packetLoss",
        "packet-loss:" + endpoint(cidr, port),
        "datagrams to " + endpoint(cidr, port) + " are dropped",
        peer(cidr, port, OperationType.DATAGRAM_SEND),
        ChaosEffect.suppress());
  }

  /**
   * Connects to {@code cidr:port} go to {@code host:targetPort} instead: a failover.
   *
   * @param cidr the peers asked for
   * @param port the port asked for
   * @param host the host connected to instead
   * @param targetPort the port connected to instead
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder failover(
      final String cidr, final int port, final String host, final int targetPort) {
    return preset(
        "failover",
        "failover:" + endpoint(cidr, port),
        "connects to " + endpoint(cidr, port) + " go to " + host + ":" + targetPort,
        peer(cidr, port, OperationType.SOCKET_CONNECT),
        ChaosEffect.redirect(host, targetPort));
  }

  /**
   * Lookups of the names {@code names} matches fail with {@code UnknownHostException}.
   *
   * @param names the names, e.g. {@code NamePattern.glob("*.internal")}
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder dnsOutage(final NamePattern names) {
    return preset(
        "dnsOutage",
        "dns-outage:" + names.value(),
        "lookups of " + names.value() + " fail",
        ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), names),
        ChaosEffect.reject("chaos: name or service not known"));
  }

  // ── Time and threads ─────────────────────────────────────────────────────

  /**
   * Every {@code java.time} and {@code java.util.Date} read is {@code offset} off: the wall clock
   * jumps (a negative offset sends it back).
   *
   * @param offset the jump
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder clockJump(final Duration offset) {
    return preset(
        "clockJump",
        "clock-jump:" + offset,
        "the wall clock is " + offset + " off",
        ChaosSelector.jvmRuntime(
            Set.of(
                OperationType.INSTANT_NOW,
                OperationType.CLOCK_READ,
                OperationType.LOCAL_DATE_TIME_NOW,
                OperationType.ZONED_DATE_TIME_NOW,
                OperationType.DATE_NEW)),
        ChaosEffect.skewClock(offset, ChaosEffect.ClockSkewMode.FIXED));
  }

  /**
   * Platform thread starts fail as when the thread limit is reached: the JDK's {@code
   * OutOfMemoryError: unable to create native thread}.
   *
   * @return the prefilled builder
   */
  public static ChaosScenario.Builder threadExhaustion() {
    return preset(
        "threadExhaustion",
        "thread-exhaustion",
        "platform thread starts fail: unable to create native thread",
        ChaosSelector.thread(Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.PLATFORM),
        ChaosEffect.errno(ChaosEffect.Errno.EAGAIN));
  }
}

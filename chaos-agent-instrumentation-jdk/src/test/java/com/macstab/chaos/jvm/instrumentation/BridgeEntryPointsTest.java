package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.ChannelBuffers;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The bridge entry points the woven advice calls, through the real dispatcher, bridge and runtime
 * ({@link JdkInstrumentationInstaller#connectDispatcher}): what each returns to the advice, and how
 * {@link ChannelBuffers} shapes the buffers of a channel call.
 */
@DisplayName("Bridge entry points")
class BridgeEntryPointsTest {

  private ChaosRuntime runtime;
  private AutoCloseable connection;

  @BeforeEach
  void connect() throws Exception {
    runtime = new ChaosRuntime();
    connection = JdkInstrumentationInstaller.connectDispatcher(runtime);
  }

  @AfterEach
  void disconnect() throws Exception {
    connection.close();
    runtime.close();
  }

  private void activate(final ChaosSelector selector, final ChaosEffect effect) {
    runtime.activate(
        ChaosScenario.builder("scenario-" + System.nanoTime())
            .selector(selector)
            .effect(effect)
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  private static ChaosSelector network(final OperationType operation) {
    return ChaosSelector.network(Set.of(operation));
  }

  private static ByteBuffer pattern(final int size) {
    final ByteBuffer buffer = ByteBuffer.allocate(size);
    for (int i = 0; i < size; i++) {
      buffer.put(i, (byte) i);
    }
    return buffer;
  }

  @Nested
  @DisplayName("socket channels")
  class SocketChannels {

    @Test
    @DisplayName("an unshaped read or write needs no shaping")
    void unshaped() throws Throwable {
      assertThat(BootstrapDispatcher.beforeSocketChannelRead(this, pattern(16))).isNull();
      assertThat(BootstrapDispatcher.beforeSocketChannelWrite(this, pattern(16))).isNull();
      assertThat(BootstrapDispatcher.beforeSocketChannelRead(this, (ByteBuffer) null)).isNull();
    }

    @Test
    @DisplayName("a trickle limits the buffer for the call and restores it after")
    void trickleLimitsAndRestores() throws Throwable {
      activate(network(OperationType.SOCKET_READ), ChaosEffect.trickle(4));
      final ByteBuffer buffer = ByteBuffer.allocate(16);
      final ChannelBuffers shaping = BootstrapDispatcher.beforeSocketChannelRead(this, buffer);
      assertThat(buffer.remaining()).isEqualTo(4);
      buffer.position(4);
      shaping.finish(4);
      assertThat(buffer.limit()).isEqualTo(16);
    }

    @Test
    @DisplayName("a torn gathering write spreads the shorter count over the buffers")
    void tornGather() throws Throwable {
      activate(network(OperationType.SOCKET_WRITE), ChaosEffect.tornWrite());
      final ByteBuffer[] buffers = {pattern(10), pattern(10)};
      final ChannelBuffers shaping =
          BootstrapDispatcher.beforeSocketChannelWrite(this, buffers, 0, 2);
      final long allowed = (long) buffers[0].remaining() + buffers[1].remaining();
      assertThat(allowed).isBetween(1L, 19L);
      shaping.finish(0);
      assertThat(buffers[0].limit()).isEqualTo(10);
      assertThat(buffers[1].limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("a corrupted scattering read flips exactly one bit of the bytes it returned")
    void corruptScatter() throws Throwable {
      activate(network(OperationType.SOCKET_READ), ChaosEffect.corruptRead());
      final ByteBuffer[] buffers = {ByteBuffer.allocate(8), ByteBuffer.allocate(8)};
      final ChannelBuffers shaping =
          BootstrapDispatcher.beforeSocketChannelRead(this, buffers, 0, 2);
      // the "read": 12 zero bytes received, 8 into the first buffer and 4 into the second
      buffers[0].position(8);
      buffers[1].position(4);
      shaping.finish(12);
      int bits = 0;
      for (final ByteBuffer buffer : buffers) {
        for (int i = 0; i < buffer.capacity(); i++) {
          bits += Integer.bitCount(buffer.get(i) & 0xFF);
        }
      }
      assertThat(bits).isEqualTo(1);
      assertThat(buffers[1].get(5)).as("beyond the bytes read").isZero();
    }

    @Test
    @DisplayName("invalid buffer arrays are left to the call, which rejects them")
    void invalidArrays() throws Throwable {
      assertThat(BootstrapDispatcher.beforeSocketChannelRead(this, null, 0, 1)).isNull();
      assertThat(BootstrapDispatcher.beforeSocketChannelRead(this, new ByteBuffer[] {null}, 0, 1))
          .isNull();
      assertThat(BootstrapDispatcher.beforeSocketChannelWrite(this, new ByteBuffer[1], 1, 1))
          .isNull();
    }
  }

  @Nested
  @DisplayName("NIO and file channels")
  class Channels {

    @Test
    @DisplayName("a torn gathering NIO write is shaped")
    void tornNioGather() throws Throwable {
      activate(
          new ChaosSelector.NioSelector(Set.of(OperationType.NIO_CHANNEL_WRITE), NamePattern.any()),
          ChaosEffect.tornWrite());
      final ByteBuffer[] buffers = {pattern(10)};
      assertThat(BootstrapDispatcher.beforeNioChannelWrite(this, buffers, 0, 1)).isNotNull();
      assertThat(buffers[0].remaining()).isBetween(1, 9);
    }

    @Test
    @DisplayName("a scattering file read under a path is corrupted, another path is not")
    void fileScatter() throws Throwable {
      activate(
          new ChaosSelector.FileIoSelector(
              Set.of(OperationType.FILE_IO_READ), PathPattern.under("/data"), null, null),
          ChaosEffect.corruptRead());
      final ByteBuffer[] buffers = {ByteBuffer.allocate(4)};
      assertThat(
              BootstrapDispatcher.beforeFileChannelIo(
                  "FILE_IO_READ", this, "/data/f", buffers, 0, 1))
          .isNotNull();
      assertThat(
              BootstrapDispatcher.beforeFileChannelIo(
                  "FILE_IO_READ", this, "/other/f", buffers, 0, 1))
          .isNull();
      assertThat(
              BootstrapDispatcher.beforeFileChannelIo("FILE_IO_READ", this, "/data/f", null, 0, 1))
          .isNull();
    }
  }

  @Nested
  @DisplayName("selector stalls")
  class SelectorStalls {

    private long stalledMillis(final java.nio.channels.Selector selector) throws Throwable {
      final long start = System.nanoTime();
      BootstrapDispatcher.beforeNioSelect(selector, 2_000L);
      return (System.nanoTime() - start) / 1_000_000L;
    }

    @Test
    @DisplayName("a wakeup made before the select ends its stall at once; it is kept only once")
    void wakeupBeforeTheStall() throws Throwable {
      activate(
          new ChaosSelector.NioSelector(
              Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any()),
          ChaosEffect.timeout());
      try (java.nio.channels.Selector selector = java.nio.channels.Selector.open()) {
        BootstrapDispatcher.selectorWoken(selector);
        assertThat(stalledMillis(selector)).as("after a wakeup").isLessThan(1_000L);
        // Another object is no selector: nothing to keep, nothing to fail.
        BootstrapDispatcher.selectorWoken(new Object());
      }
    }

    @Test
    @DisplayName("a closed selector's wakeup is not kept, and idle a wakeup clears a kept one")
    void closedOrIdle() throws Throwable {
      final ChaosActivationHandle timeout =
          runtime.activate(
              ChaosScenario.builder("select-timeout")
                  .selector(
                      new ChaosSelector.NioSelector(
                          Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any()))
                  .effect(ChaosEffect.timeout())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final java.nio.channels.Selector closed = java.nio.channels.Selector.open();
      closed.close();
      BootstrapDispatcher.selectorWoken(closed);
      // The real select of a closed selector throws; it is not stalled.
      assertThat(BootstrapDispatcher.beforeNioSelect(closed, 2_000L)).isFalse();

      try (java.nio.channels.Selector selector = java.nio.channels.Selector.open()) {
        // A select that proceeds (no timeout to wait out) consumes the kept wakeup, as the real
        // select consumes the JDK's own.
        BootstrapDispatcher.selectorWoken(selector);
        assertThat(BootstrapDispatcher.beforeNioSelect(selector, 0L)).isFalse();
        BootstrapDispatcher.selectorWoken(selector);
        timeout.close();
        BootstrapDispatcher.selectorWoken(selector); // idle: the kept wakeup is dropped
        BootstrapDispatcher.selectorWoken(selector); // idle, nothing kept
        assertThat(BootstrapDispatcher.beforeNioSelect(selector, 0L)).isFalse();
      }
    }
  }

  @Nested
  @DisplayName("datagrams")
  class Datagrams {

    @Test
    @DisplayName("a suppressed send is dropped: consumed and reported as sent")
    void droppedSend() throws Throwable {
      activate(network(OperationType.DATAGRAM_SEND), ChaosEffect.suppress());
      final ByteBuffer source = pattern(12);
      assertThat(BootstrapDispatcher.beforeDatagramSend(this, source, null, true)).isEqualTo(13);
      assertThat(source.hasRemaining()).isFalse();
      final java.net.DatagramPacket packet =
          new java.net.DatagramPacket(new byte[4], 4, new InetSocketAddress("127.0.0.1", 9));
      assertThat(BootstrapDispatcher.dropDatagramPacket(this, packet)).isTrue();
    }

    @Test
    @DisplayName("without a scenario a datagram is sent and received unchanged")
    void unchanged() throws Throwable {
      final ByteBuffer source = pattern(12);
      assertThat(BootstrapDispatcher.beforeDatagramSend(this, source, null, true)).isZero();
      assertThat(source.remaining()).isEqualTo(12);
      assertThat(BootstrapDispatcher.beforeDatagramReceive(this, ByteBuffer.allocate(8))).isNull();
      assertThat(BootstrapDispatcher.beforeDatagramReceive(this, null)).isNull();
      assertThat(
              BootstrapDispatcher.corruptDatagramPacket(
                  this, new java.net.DatagramPacket(new byte[4], 4)))
          .isFalse();
    }

    @Test
    @DisplayName("a suppressed send the JDK would refuse is not dropped: it proceeds to fail")
    void refusedSendIsNotDropped() throws Throwable {
      activate(network(OperationType.DATAGRAM_SEND), ChaosEffect.suppress());
      final InetSocketAddress peer = new InetSocketAddress(InetAddress.getLoopbackAddress(), 9);
      final InetSocketAddress other = new InetSocketAddress(InetAddress.getLoopbackAddress(), 10);
      final java.nio.channels.DatagramChannel closed = java.nio.channels.DatagramChannel.open();
      closed.close();
      try (java.nio.channels.DatagramChannel unconnected =
              java.nio.channels.DatagramChannel.open();
          java.nio.channels.DatagramChannel connected =
              java.nio.channels.DatagramChannel.open().connect(peer);
          java.nio.channels.DatagramChannel nonBlocking =
              java.nio.channels.DatagramChannel.open()) {
        nonBlocking.configureBlocking(false);

        assertThat(BootstrapDispatcher.beforeDatagramSend(closed, pattern(4), peer, false))
            .as("closed")
            .isZero();
        assertThat(BootstrapDispatcher.beforeDatagramSend(unconnected, pattern(4), null, true))
            .as("a write of an unconnected channel")
            .isZero();
        assertThat(BootstrapDispatcher.beforeDatagramSend(unconnected, pattern(4), null, false))
            .as("no destination")
            .isZero();
        assertThat(
                BootstrapDispatcher.beforeDatagramSend(
                    unconnected,
                    pattern(4),
                    InetSocketAddress.createUnresolved("nowhere.invalid", 9),
                    false))
            .as("an unresolved destination")
            .isZero();
        assertThat(
                BootstrapDispatcher.beforeDatagramSend(
                    unconnected,
                    pattern(4),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    false))
            .as("port 0")
            .isZero();
        assertThat(BootstrapDispatcher.beforeDatagramSend(connected, pattern(4), other, false))
            .as("a destination other than the connected peer")
            .isZero();
        // An IPv6 destination of an IPv4 channel is refused where the bridge can read the
        // channel's family (sun.nio.ch opened to it by the agent): DatagramEndToEndTest.
        final InetSocketAddress ipv6 = new InetSocketAddress("::1", 9);
        try (java.nio.channels.DatagramChannel inet6 =
            java.nio.channels.DatagramChannel.open(java.net.StandardProtocolFamily.INET6)) {
          assertThat(BootstrapDispatcher.beforeDatagramSend(inet6, pattern(4), ipv6, false))
              .as("an IPv6 destination of an IPv6 channel")
              .isEqualTo(5);
          assertThat(BootstrapDispatcher.beforeDatagramSend(this, pattern(4), ipv6, false))
              .as("another kind of channel: no family to check")
              .isEqualTo(5);
        }
        assertThat(BootstrapDispatcher.beforeDatagramSend(unconnected, pattern(4), peer, false))
            .as("a send the JDK makes")
            .isEqualTo(5);
        assertThat(BootstrapDispatcher.beforeDatagramSend(connected, pattern(4), peer, false))
            .as("a send to the connected peer")
            .isEqualTo(5);
        assertThat(BootstrapDispatcher.beforeDatagramSend(connected, pattern(4), null, true))
            .as("a write of a connected channel")
            .isEqualTo(5);

        assertThat(
                BootstrapDispatcher.dropDatagramPacket(
                    unconnected, new java.net.DatagramPacket(new byte[4], 4)))
            .as("a packet without an address on an unconnected socket")
            .isFalse();
        assertThat(
                BootstrapDispatcher.dropDatagramPacket(
                    nonBlocking, new java.net.DatagramPacket(new byte[4], 4, peer)))
            .as("a non-blocking channel")
            .isFalse();
        assertThat(
                BootstrapDispatcher.dropDatagramPacket(
                    closed, new java.net.DatagramPacket(new byte[4], 4, peer)))
            .as("closed")
            .isFalse();
        assertThat(
                BootstrapDispatcher.dropDatagramPacket(
                    unconnected, new java.net.DatagramPacket(new byte[4], 4, peer)))
            .as("a packet with an address")
            .isTrue();
        final java.net.DatagramPacket toPeer = new java.net.DatagramPacket(new byte[4], 4);
        assertThat(BootstrapDispatcher.dropDatagramPacket(connected, toPeer))
            .as("a connected socket's packet without an address")
            .isTrue();
        assertThat(toPeer.getSocketAddress()).isEqualTo(peer);
      }
    }

    @Test
    @DisplayName("a packet receive counts the packet's window, also once the packet is reused")
    void packetWindow() throws Throwable {
      activate(
          new ChaosSelector.NetworkSelector(
              Set.of(OperationType.DATAGRAM_RECEIVE), NamePattern.any(), null, null, 200L, 300L),
          ChaosEffect.corruptRead());
      final java.net.DatagramPacket window = new java.net.DatagramPacket(new byte[1024], 512, 256);
      assertThat(BootstrapDispatcher.corruptDatagramPacket(this, window)).isTrue();
      assertThat(
              BootstrapDispatcher.corruptDatagramPacket(
                  this, new java.net.DatagramPacket(new byte[1024], 1024)))
          .as("the whole array")
          .isFalse();
      assertThat(BootstrapDispatcher.corruptDatagramPacket(this, null)).isFalse();
    }

    @Test
    @DisplayName("a corrupted receive is shaped, for a buffer and for a packet")
    void corruptReceive() throws Throwable {
      activate(network(OperationType.DATAGRAM_RECEIVE), ChaosEffect.corruptRead());
      final ByteBuffer target = ByteBuffer.allocate(8);
      final ChannelBuffers shaping = BootstrapDispatcher.beforeDatagramReceive(this, target);
      target.position(8);
      shaping.finish(ChannelBuffers.RECEIVED);
      int bits = 0;
      for (int i = 0; i < 8; i++) {
        bits += Integer.bitCount(target.get(i) & 0xFF);
      }
      assertThat(bits).isEqualTo(1);
      assertThat(
              BootstrapDispatcher.corruptDatagramPacket(
                  this, new java.net.DatagramPacket(new byte[4], 4)))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("reads into a read-only buffer")
  class ReadOnlyBuffers {

    private ChaosActivationHandle corrupt(final ChaosSelector selector) {
      return runtime.activate(
          ChaosScenario.builder("corrupt-" + System.nanoTime())
              .selector(selector)
              .effect(ChaosEffect.corruptRead())
              .activationPolicy(ActivationPolicy.always())
              .build());
    }

    @Test
    @DisplayName("the JDK refuses a channel read into a read-only buffer")
    void jdkRefusesReadOnlyRead() throws Exception {
      try (java.nio.channels.Pipe.SourceChannel source = java.nio.channels.Pipe.open().source()) {
        assertThatThrownBy(() -> source.read(ByteBuffer.allocate(8).asReadOnlyBuffer()))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }

    @Test
    @DisplayName(
        "a corrupted socket channel read into a read-only buffer neither applies nor counts")
    void socketChannelRead() throws Throwable {
      final ChaosActivationHandle handle = corrupt(network(OperationType.SOCKET_READ));
      final ByteBuffer readOnly = ByteBuffer.allocate(8).asReadOnlyBuffer();

      assertThat(BootstrapDispatcher.beforeSocketChannelRead(this, readOnly)).isNull();
      assertThat(
              BootstrapDispatcher.beforeSocketChannelRead(
                  this, new ByteBuffer[] {ByteBuffer.allocate(8), readOnly}, 0, 2))
          .isNull();

      assertThat(handle.appliedCount()).isZero();
    }

    @Test
    @DisplayName("a corrupted datagram receive into a read-only buffer neither applies nor counts")
    void datagramReceive() throws Throwable {
      final ChaosActivationHandle handle = corrupt(network(OperationType.DATAGRAM_RECEIVE));

      assertThat(
              BootstrapDispatcher.beforeDatagramReceive(
                  this, ByteBuffer.allocate(8).asReadOnlyBuffer()))
          .isNull();

      assertThat(handle.appliedCount()).isZero();
    }

    @Test
    @DisplayName(
        "a corrupted scattering file read into a read-only buffer neither applies nor counts")
    void fileScatterRead() throws Throwable {
      final ChaosActivationHandle handle =
          corrupt(
              new ChaosSelector.FileIoSelector(
                  Set.of(OperationType.FILE_IO_READ), null, null, null));

      assertThat(
              BootstrapDispatcher.beforeFileChannelIo(
                  "FILE_IO_READ",
                  this,
                  "/f",
                  new ByteBuffer[] {ByteBuffer.allocate(8).asReadOnlyBuffer()},
                  0,
                  1))
          .isNull();

      assertThat(handle.appliedCount()).isZero();
    }
  }

  @Nested
  @DisplayName("lookups, binds, waits and shutdowns")
  class Others {

    @Test
    @DisplayName("a rejected reverse lookup fails; without one it does not")
    void reverseLookup() throws Throwable {
      final InetAddress address = InetAddress.getByAddress(new byte[] {10, 0, 0, 1});
      assertThat(BootstrapDispatcher.reverseLookupFails(address)).isFalse();
      activate(
          ChaosSelector.dns(Set.of(OperationType.DNS_REVERSE), NamePattern.exact("10.0.0.1")),
          ChaosEffect.reject("no PTR"));
      assertThat(BootstrapDispatcher.reverseLookupFails(address)).isTrue();
      assertThat(BootstrapDispatcher.reverseLookupFails(null)).isFalse();
    }

    @Test
    @DisplayName("a bind error reaches the advice as BindException")
    void bind() throws Throwable {
      BootstrapDispatcher.beforeSocketBind(InetAddress.getLoopbackAddress(), 0);
      activate(network(OperationType.SOCKET_BIND), ChaosEffect.errno(ChaosEffect.Errno.EADDRINUSE));
      assertThatThrownBy(
              () -> BootstrapDispatcher.beforeSocketBind(InetAddress.getLoopbackAddress(), 80))
          .isInstanceOf(java.net.BindException.class);
    }

    @Test
    @DisplayName("a timed process wait under a timeout reports a child that has not exited")
    void processWait() throws Throwable {
      assertThat(BootstrapDispatcher.beforeProcessWait(this, 10L)).isFalse();
      activate(
          new ChaosSelector.ProcessSelector(Set.of(OperationType.PROCESS_WAIT), null),
          ChaosEffect.timeout());
      assertThat(BootstrapDispatcher.beforeProcessWait(this, 10L)).isTrue();
      assertThat(BootstrapDispatcher.beforeProcessWait(this, 0L)).as("no timeout").isFalse();
    }

    @Test
    @DisplayName("a shutdown error reaches the advice")
    void shutdown() throws Throwable {
      BootstrapDispatcher.beforeSocketShutdown(this, null, 0);
      activate(
          network(OperationType.SOCKET_SHUTDOWN), ChaosEffect.errno(ChaosEffect.Errno.ENOTCONN));
      assertThatThrownBy(() -> BootstrapDispatcher.beforeSocketShutdown(this, null, 0))
          .isInstanceOf(java.net.SocketException.class);
    }

    @Test
    @DisplayName("a redirect returns the other endpoint, else the requested one")
    void redirect() throws Throwable {
      final InetSocketAddress asked = new InetSocketAddress("127.0.0.1", 5432);
      assertThat(BootstrapDispatcher.beforeSocketConnect(this, asked, 0)).isSameAs(asked);
      activate(network(OperationType.SOCKET_CONNECT), ChaosEffect.redirect("127.0.0.1", 6543));
      assertThat(BootstrapDispatcher.beforeSocketConnect(this, asked, 0))
          .isEqualTo(new InetSocketAddress("127.0.0.1", 6543));
    }

    @Test
    @DisplayName("the system clock reads are skewed by CLOCK_READ, except inside a java.time now()")
    void clockReads() throws Throwable {
      final Instant real = Instant.ofEpochSecond(1_000L);
      assertThat(BootstrapDispatcher.adjustClockInstant(real)).isSameAs(real);
      activate(
          ChaosSelector.jvmRuntime(Set.of(OperationType.CLOCK_READ)),
          ChaosEffect.skewClock(Duration.ofSeconds(5), ChaosEffect.ClockSkewMode.FIXED));
      assertThat(BootstrapDispatcher.adjustClockInstant(real)).isEqualTo(real.plusSeconds(5));
      assertThat(BootstrapDispatcher.adjustClockReadMillis(1_000L)).isEqualTo(6_000L);
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_CLOCK_API);
      try {
        assertThat(BootstrapDispatcher.adjustClockInstant(real)).isSameAs(real);
        assertThat(BootstrapDispatcher.adjustClockReadMillis(1_000L)).isEqualTo(1_000L);
      } finally {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_CLOCK_API);
      }
    }

    @Test
    @DisplayName("file I/O inside a transfer is the transfer's, not evaluated again")
    void transferScope() throws Throwable {
      activate(
          new ChaosSelector.FileIoSelector(Set.of(OperationType.FILE_IO_READ), null, null, null),
          ChaosEffect.corruptRead());
      assertThat(BootstrapDispatcher.beforeFileIo("FILE_IO_READ", this, "/f", 8)).isNegative();
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
      try {
        assertThat(BootstrapDispatcher.beforeFileIo("FILE_IO_READ", this, "/f", 8)).isEqualTo(8);
      } finally {
        BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
      }
    }
  }
}

package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Set;

/**
 * Probe main for UDP under a scenario, on real datagram sockets and channels. Each mode prints
 * {@code key=value} lines.
 */
public final class DatagramProbeMain {

  private static final int RECEIVE_TIMEOUT_MILLIS = 2_000;

  private DatagramProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "receive-window" -> receiveWindow();
      case "receive-reused" -> receiveReused();
      case "dropped-send" -> droppedSend();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * A {@code corruptRead()} on {@code DATAGRAM_RECEIVE} limited to receives of at most 300 bytes,
   * and a {@code DatagramSocket} receive into a 256-byte window at offset 512 of a 1024-byte array.
   * Prints {@code received} (the datagram's length), {@code applied} and {@code corrupted} (whether
   * a bit of the 200 zero bytes received was flipped).
   */
  private static void receiveWindow() throws Exception {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    try (DatagramSocket receiver = new DatagramSocket(0, loopback);
        DatagramSocket sender = new DatagramSocket(0, loopback);
        ChaosActivationHandle corrupt =
            RealCallRows.activate(
                "datagram-window",
                new ChaosSelector.NetworkSelector(
                    Set.of(OperationType.DATAGRAM_RECEIVE),
                    NamePattern.any(),
                    null,
                    null,
                    null,
                    300L),
                ChaosEffect.corruptRead())) {
      receiver.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
      sender.send(new DatagramPacket(new byte[200], 200, receiver.getLocalSocketAddress()));
      final byte[] data = new byte[1024];
      final DatagramPacket packet = new DatagramPacket(data, 512, 256);
      receiver.receive(packet);
      boolean corrupted = false;
      for (final byte b : data) {
        corrupted |= b != 0;
      }
      System.out.println("received=" + packet.getLength());
      System.out.println("applied=" + corrupt.appliedCount());
      System.out.println("corrupted=" + corrupted);
    }
  }

  /**
   * A reused packet: a 1024-byte packet that already received a 10-byte datagram (its {@code
   * getLength()} is now 10) receives another under a {@code corruptRead()} limited to receives of
   * at most 300 bytes. The receive still takes up to 1024 bytes, so the scenario does not select
   * it. Prints {@code received} and {@code applied}.
   */
  private static void receiveReused() throws Exception {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    try (DatagramSocket receiver = new DatagramSocket(0, loopback);
        DatagramSocket sender = new DatagramSocket(0, loopback)) {
      receiver.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
      final DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
      sender.send(new DatagramPacket(new byte[10], 10, receiver.getLocalSocketAddress()));
      receiver.receive(packet);
      try (ChaosActivationHandle corrupt =
          RealCallRows.activate(
              "datagram-reused",
              new ChaosSelector.NetworkSelector(
                  Set.of(OperationType.DATAGRAM_RECEIVE),
                  NamePattern.any(),
                  null,
                  null,
                  null,
                  300L),
              ChaosEffect.corruptRead())) {
        sender.send(new DatagramPacket(new byte[10], 10, receiver.getLocalSocketAddress()));
        receiver.receive(packet);
        System.out.println("received=" + packet.getLength());
        System.out.println("applied=" + corrupt.appliedCount());
      }
    }
  }

  /**
   * Sends the JDK refuses, each under a {@code suppress()} on {@code DATAGRAM_SEND}: they must fail
   * as without the scenario instead of being reported sent (also an IPv6 destination of an IPv4
   * channel, {@code family}). Prints, per row, the class of what the send threw ({@code ok} when it
   * returned); for a connected socket's packet without an address, the address the packet carries
   * afterwards and what the peer received; and a plain drop.
   */
  private static void droppedSend() throws Exception {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    try (DatagramSocket receiver = new DatagramSocket(0, loopback);
        ChaosActivationHandle drop =
            RealCallRows.activate(
                "datagram-drop",
                ChaosSelector.network(Set.of(OperationType.DATAGRAM_SEND)),
                ChaosEffect.suppress())) {
      receiver.setSoTimeout(RECEIVE_TIMEOUT_MILLIS / 4);
      final InetSocketAddress peer = (InetSocketAddress) receiver.getLocalSocketAddress();
      final InetSocketAddress other =
          new InetSocketAddress(
              loopback, peer.getPort() == 65_535 ? peer.getPort() - 1 : peer.getPort() + 1);

      final DatagramChannel closed = DatagramChannel.open();
      closed.close();
      System.out.println(
          "closed.send=" + RealCallRows.outcome(() -> closed.send(ByteBuffer.allocate(4), peer)));
      try (DatagramChannel unconnected = DatagramChannel.open()) {
        System.out.println(
            "unconnected.write="
                + RealCallRows.outcome(() -> unconnected.write(ByteBuffer.allocate(4))));
        System.out.println(
            "unresolved.send="
                + RealCallRows.outcome(
                    () ->
                        unconnected.send(
                            ByteBuffer.allocate(4),
                            InetSocketAddress.createUnresolved("nowhere.invalid", 9))));
        System.out.println(
            "port0.send="
                + RealCallRows.outcome(
                    () ->
                        unconnected.send(
                            ByteBuffer.allocate(4), new InetSocketAddress(loopback, 0))));
        System.out.println("drop.sent=" + unconnected.send(ByteBuffer.allocate(4), peer));
      }
      try (DatagramChannel connected = DatagramChannel.open()) {
        connected.connect(peer);
        System.out.println(
            "otherPeer.send="
                + RealCallRows.outcome(() -> connected.send(ByteBuffer.allocate(4), other)));
      }
      try (DatagramChannel inet = DatagramChannel.open(StandardProtocolFamily.INET)) {
        System.out.println(
            "family.send="
                + RealCallRows.outcome(
                    () -> inet.send(ByteBuffer.allocate(4), new InetSocketAddress("::1", 9))));
      }
      try (DatagramSocket unconnected = new DatagramSocket(0, loopback)) {
        System.out.println(
            "noAddress.send="
                + RealCallRows.outcome(() -> unconnected.send(new DatagramPacket(new byte[4], 4))));
      }
      try (DatagramSocket connected = new DatagramSocket(0, loopback)) {
        connected.connect(peer);
        final DatagramPacket packet = new DatagramPacket(new byte[4], 4);
        System.out.println("connected.send=" + RealCallRows.outcome(() -> connected.send(packet)));
        System.out.println(
            "connected.packetAddress="
                + (packet.getAddress() != null && packet.getSocketAddress().equals(peer)));
        System.out.println(
            "connected.received="
                + RealCallRows.outcome(() -> receiver.receive(new DatagramPacket(new byte[4], 4))));
      }
      System.out.println("applied=" + drop.appliedCount());
    }
  }
}

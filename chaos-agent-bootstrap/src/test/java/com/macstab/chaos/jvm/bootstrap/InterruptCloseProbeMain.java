package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Probe main for the close the JDK makes inside {@code Thread.interrupt()}: an interrupted reader's
 * channel is closed by the interrupting thread, which must not run into a close scenario.
 */
public final class InterruptCloseProbeMain {

  private static final Duration CLOSE_DELAY = Duration.ofSeconds(3);

  private InterruptCloseProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "interrupt-close" -> interruptClose();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * A reader blocked on a file channel (a FIFO, opened read-write so the open does not wait for a
   * writer) under a {@code FILE_IO_CLOSE} delay, and one blocked on a socket channel under a {@code
   * SOCKET_CLOSE} delay; each is interrupted, on a platform thread and on a virtual one ({@code
   * VirtualThread} overrides {@code interrupt()}). Prints, per {@code file}, {@code fileVirtual}
   * (JDK 21), {@code socket} and {@code socketVirtual}, how long {@code interrupt()} took in
   * milliseconds and what the reader saw, and per scenario its applied count.
   */
  private static void interruptClose() throws Exception {
    final Path fifo = Files.createTempDirectory("interrupt-close").resolve("fifo");
    final Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start();
    if (!mkfifo.waitFor(10, TimeUnit.SECONDS) || mkfifo.exitValue() != 0) {
      throw new IllegalStateException("mkfifo failed");
    }
    try (ChaosActivationHandle delay =
        RealCallRows.activate(
            "file-close-delay",
            ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_CLOSE)),
            ChaosEffect.delay(CLOSE_DELAY))) {
      final FileChannel channel =
          FileChannel.open(fifo, StandardOpenOption.READ, StandardOpenOption.WRITE);
      interruptReader("file", false, () -> channel.read(ByteBuffer.allocate(4)));
      // JDK 25 keeps a virtual thread blocked on a FIFO until its read ends, also without the
      // agent: there the virtual row is the socket's only.
      if (Runtime.version().feature() == 21) {
        final FileChannel virtual =
            FileChannel.open(fifo, StandardOpenOption.READ, StandardOpenOption.WRITE);
        interruptReader("fileVirtual", true, () -> virtual.read(ByteBuffer.allocate(4)));
      }
      System.out.println("file.applied=" + delay.appliedCount());
    }
    try (ServerSocketChannel server =
            ServerSocketChannel.open()
                .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        SocketChannel client = SocketChannel.open(server.getLocalAddress());
        SocketChannel peer = server.accept();
        SocketChannel virtual = SocketChannel.open(server.getLocalAddress());
        SocketChannel virtualPeer = server.accept();
        ChaosActivationHandle delay =
            RealCallRows.activate(
                "socket-close-delay",
                ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
                ChaosEffect.delay(CLOSE_DELAY))) {
      interruptReader("socket", false, () -> client.read(ByteBuffer.allocate(4)));
      interruptReader("socketVirtual", true, () -> virtual.read(ByteBuffer.allocate(4)));
      System.out.println("socket.applied=" + delay.appliedCount());
    }
  }

  /**
   * Starts {@code read} on a platform or virtual thread, interrupts it once it blocks, and prints
   * what happened.
   */
  private static void interruptReader(
      final String row, final boolean virtual, final RealCallRows.Call read) throws Exception {
    final AtomicReference<String> outcome = new AtomicReference<>("none");
    final Runnable reading = () -> outcome.set(RealCallRows.outcome(read));
    final Thread reader =
        virtual
            ? Thread.ofVirtual().name("interrupt-close-" + row).unstarted(reading)
            : Thread.ofPlatform().name("interrupt-close-" + row).unstarted(reading);
    reader.start();
    // A blocked native read stays RUNNABLE, so there is no state to wait for: give it time.
    Thread.sleep(300);
    final long start = System.nanoTime();
    reader.interrupt();
    final long interruptMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    reader.join(CLOSE_DELAY.toMillis() * 2);
    System.out.println(row + ".interruptMillis=" + interruptMillis);
    System.out.println(row + ".reader=" + outcome.get());
  }
}

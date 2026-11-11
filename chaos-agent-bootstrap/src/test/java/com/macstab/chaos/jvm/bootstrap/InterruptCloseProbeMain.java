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
   * SOCKET_CLOSE} delay; each is interrupted. Prints, per {@code file} and {@code socket}, how long
   * {@code interrupt()} took in milliseconds, what the reader saw, and the scenario's applied
   * count.
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
      interruptReader("file", () -> channel.read(ByteBuffer.allocate(4)));
      System.out.println("file.applied=" + delay.appliedCount());
    }
    try (ServerSocketChannel server =
            ServerSocketChannel.open()
                .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        SocketChannel client = SocketChannel.open(server.getLocalAddress());
        SocketChannel peer = server.accept();
        ChaosActivationHandle delay =
            RealCallRows.activate(
                "socket-close-delay",
                ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
                ChaosEffect.delay(CLOSE_DELAY))) {
      interruptReader("socket", () -> client.read(ByteBuffer.allocate(4)));
      System.out.println("socket.applied=" + delay.appliedCount());
    }
  }

  /** Starts {@code read} on a thread, interrupts it once it blocks, and prints what happened. */
  private static void interruptReader(final String row, final RealCallRows.Call read)
      throws Exception {
    final AtomicReference<String> outcome = new AtomicReference<>("none");
    final Thread reader =
        new Thread(() -> outcome.set(RealCallRows.outcome(read)), "interrupt-close-" + row);
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

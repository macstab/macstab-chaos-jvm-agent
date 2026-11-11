package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Probe main for file effects on real files: a reject of every file operation, and the errnos the
 * other probes do not reach. Each row prints the class and message of what the call threw.
 */
public final class FileRealCallProbeMain {

  private FileRealCallProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "file-calls" -> fileCalls();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  private static void row(
      final String row,
      final Path dir,
      final OperationType operation,
      final ChaosEffect effect,
      final RealCallRows.Call call) {
    final Throwable failure =
        RealCallRows.during(
            row,
            new ChaosSelector.FileIoSelector(
                Set.of(operation), PathPattern.under(dir.toString()), null, null),
            effect,
            call);
    print(row, failure);
  }

  private static void print(final String row, final @Nullable Throwable failure) {
    System.out.println(row + ".type=" + (failure == null ? "none" : failure.getClass().getName()));
    if (failure != null) {
      System.out.println(row + ".message=" + failure.getMessage());
    }
  }

  private static void fileCalls() throws Exception {
    final Path dir = Files.createTempDirectory("file-calls");
    final Path data = Files.write(dir.resolve("data"), new byte[] {1, 2, 3, 4});
    final ChaosEffect reject = ChaosEffect.reject("chaos");
    try (FileChannel channel =
        FileChannel.open(data, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      row(
          "read",
          dir,
          OperationType.FILE_IO_READ,
          reject,
          () -> channel.read(ByteBuffer.allocate(4), 0));
      row("sync", dir, OperationType.FILE_IO_SYNC, reject, () -> channel.force(true));
      row("truncate", dir, OperationType.FILE_IO_TRUNCATE, reject, () -> channel.truncate(2));
      row(
          "map",
          dir,
          OperationType.FILE_IO_MAP,
          reject,
          () -> channel.map(FileChannel.MapMode.READ_ONLY, 0, 2));
      row(
          "readEio",
          dir,
          OperationType.FILE_IO_READ,
          ChaosEffect.errno(ChaosEffect.Errno.EIO),
          () -> channel.read(ByteBuffer.allocate(4), 0));
      row(
          "writeEdquot",
          dir,
          OperationType.FILE_IO_WRITE,
          ChaosEffect.errno(ChaosEffect.Errno.EDQUOT),
          () -> channel.write(ByteBuffer.allocate(1), 0));
    }
    try (FileOutputStream out = new FileOutputStream(data.toFile(), true)) {
      row("streamSync", dir, OperationType.FILE_IO_SYNC, reject, () -> out.getFD().sync());
    }
    final FileInputStream in = new FileInputStream(data.toFile());
    row("close", dir, OperationType.FILE_IO_CLOSE, reject, in::close);
    System.out.println("close.closed=" + RealCallRows.outcome(in::read));
    row(
        "openEnfile",
        dir,
        OperationType.FILE_IO_OPEN,
        ChaosEffect.errno(ChaosEffect.Errno.ENFILE),
        () -> Files.newInputStream(data).close());
    row(
        "openEdquot",
        dir,
        OperationType.FILE_IO_OPEN,
        ChaosEffect.errno(ChaosEffect.Errno.EDQUOT),
        () -> Files.newOutputStream(dir.resolve("new")).close());
    final Path moved = dir.resolve("moved");
    row("move", dir, OperationType.FILE_IO_MOVE, reject, () -> Files.move(data, moved));
    System.out.println("move.sourceExists=" + Files.exists(data));
    row(
        "moveTarget",
        dir,
        OperationType.FILE_IO_MOVE_TARGET,
        reject,
        () -> Files.move(data, moved));
    System.out.println("moveTarget.targetExists=" + Files.exists(moved));
    row("delete", dir, OperationType.FILE_IO_DELETE, reject, () -> Files.delete(data));
    System.out.println("delete.exists=" + Files.exists(data));
    final File legacy = data.toFile();
    final AtomicReference<Boolean> deleted = new AtomicReference<>();
    row(
        "fileDelete",
        dir,
        OperationType.FILE_IO_DELETE,
        reject,
        () -> deleted.set(legacy.delete()));
    System.out.println("fileDelete.value=" + deleted.get() + "/" + legacy.exists());
    row(
        "deleteEio",
        dir,
        OperationType.FILE_IO_DELETE,
        ChaosEffect.errno(ChaosEffect.Errno.EIO),
        () -> Files.delete(data));
  }
}

package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Probe main for injected file I/O failures on real files: the exception type the caller sees and
 * what a torn transfer does. Each mode prints {@code key=value} lines.
 */
public final class FileFaultProbeMain {

  private FileFaultProbeMain() {}

  public static void main(final String[] args) throws Exception {
    final Path dir = Files.createTempDirectory("file-fault");
    switch (args[0]) {
      case "open-subclass" -> openSubclass(dir);
      case "torn-transfer" -> tornTransfer(dir);
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /** A stream class of the application: the constructor still declares FileNotFoundException. */
  static final class CountingInput extends FileInputStream {
    CountingInput(final File file) throws FileNotFoundException {
      super(file);
    }
  }

  /** As {@link CountingInput}, for writes. */
  static final class CountingOutput extends FileOutputStream {
    CountingOutput(final File file) throws FileNotFoundException {
      super(file);
    }
  }

  /** As {@link CountingInput}, for random access. */
  static final class CountingRandomAccess extends RandomAccessFile {
    CountingRandomAccess(final File file) throws FileNotFoundException {
      super(file, "rw");
    }
  }

  /**
   * Opens a {@code java.io} stream subclass under a {@code FILE_IO_OPEN} errno {@code ENOENT} and
   * reject. Prints {@code <effect>.<stream>} as the exception class.
   */
  private static void openSubclass(final Path dir) throws Exception {
    final File file = dir.resolve("f.txt").toFile();
    Files.writeString(file.toPath(), "x");
    final ChaosSelector open =
        new ChaosSelector.FileIoSelector(
            Set.of(OperationType.FILE_IO_OPEN), PathPattern.under(dir.toString()), null, null);
    for (final ChaosEffect effect :
        new ChaosEffect[] {ChaosEffect.errno(ChaosEffect.Errno.ENOENT), ChaosEffect.reject("x")}) {
      final String name = effect instanceof ChaosEffect.ErrnoEffect ? "errno" : "reject";
      System.out.println(
          name
              + ".input="
              + type(
                  RealCallRows.during(name, open, effect, () -> new CountingInput(file).close())));
      System.out.println(
          name
              + ".output="
              + type(
                  RealCallRows.during(name, open, effect, () -> new CountingOutput(file).close())));
      System.out.println(
          name
              + ".randomAccess="
              + type(
                  RealCallRows.during(
                      name, open, effect, () -> new CountingRandomAccess(file).close())));
    }
  }

  /**
   * A {@code transferFrom(source, 0, Long.MAX_VALUE)} of a 100-byte file under a torn write with
   * {@code maxApplications} 1, then a 100-byte channel write. Prints {@code transferred}, {@code
   * written} and {@code applied}.
   */
  private static void tornTransfer(final Path dir) throws Exception {
    final Path source = dir.resolve("src.bin");
    Files.write(source, new byte[100]);
    final Path out = Files.createDirectories(dir.resolve("out"));
    try (ChaosActivationHandle torn =
            ChaosPlatform.current()
                .activate(
                    ChaosScenario.builder("torn-transfer")
                        .selector(
                            new ChaosSelector.FileIoSelector(
                                Set.of(OperationType.FILE_IO_WRITE),
                                PathPattern.under(out.toString()),
                                null,
                                null))
                        .effect(ChaosEffect.tornWrite())
                        .activationPolicy(ActivationPolicy.count(1L))
                        .build());
        FileChannel in = FileChannel.open(source);
        FileChannel target =
            FileChannel.open(
                out.resolve("t.bin"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      System.out.println("transferred=" + target.transferFrom(in, 0, Long.MAX_VALUE));
      target.position(100);
      System.out.println("written=" + target.write(ByteBuffer.allocate(100)));
      System.out.println("applied=" + torn.appliedCount());
    }
  }

  private static String type(final Throwable failure) {
    return failure == null ? "none" : failure.getClass().getName();
  }
}

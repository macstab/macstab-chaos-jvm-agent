package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.ChannelBuffers;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import net.bytebuddy.asm.Advice;
import org.jspecify.annotations.Nullable;

/**
 * ByteBuddy {@link Advice} classes for file streams, file channels, mappings and {@code
 * java.nio.file.Files} operations ({@code FILE_IO_*}).
 *
 * <p>Each nested class targets one method or overload family. The outer class is not instantiated;
 * it exists only as a namespace.
 */
final class FileIoAdvice {
  private FileIoAdvice() {}

  // ── B17: File I/O ─────────────────────────────────────────────────────────

  /**
   * Intercepts the single-byte {@link java.io.FileInputStream#read()} (one byte requested). The
   * stream's {@code path} field is the path it was opened with, or {@code null} for a stream built
   * from a {@link java.io.FileDescriptor}.
   *
   * <p>Passes {@code "FILE_IO_READ"} as the operation tag so the dispatcher can route to the
   * correct {@link com.macstab.chaos.jvm.api.OperationType#FILE_IO_READ} scenario.
   */
  static final class FileReadByteAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream, @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_READ, stream, path, 1) < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt, @Advice.Return(readOnly = false) int value) {
      if (corrupt) {
        value = BootstrapDispatcher.corruptByte(value);
      }
    }
  }

  /** Intercepts {@link java.io.FileInputStream#read(byte[])} ({@code b.length} requested). */
  static final class FileReadArrayAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileIo(
              OperationTags.FILE_IO_READ, stream, path, buffer == null ? -1 : buffer.length)
          < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Return final int read) {
      if (corrupt) {
        BootstrapDispatcher.corruptBytes(buffer, 0, read);
      }
    }
  }

  /** Intercepts {@link java.io.FileInputStream#read(byte[], int, int)} ({@code len} requested). */
  static final class FileReadRangeAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream,
        @Advice.Argument(2) final int len,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_READ, stream, path, len) < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Argument(1) final int offset,
        @Advice.Return final int read) {
      if (corrupt) {
        BootstrapDispatcher.corruptBytes(buffer, offset, read);
      }
    }
  }

  /**
   * Intercepts {@link java.io.FileInputStream#readAllBytes()}: one read of the bytes returned, also
   * when the stream fills them through several of its own reads ({@link
   * BootstrapDispatcher#SCOPE_FILE_TRANSFER}). The bytes requested are unbounded ({@link
   * Integer#MAX_VALUE}, as {@code readNBytes(Integer.MAX_VALUE)}). A corrupted read flips one bit
   * of the returned array.
   */
  static final class FileReadAllBytesAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream, @Advice.FieldValue("path") final String path)
        throws Throwable {
      final boolean corrupt =
          BootstrapDispatcher.beforeFileIo(
                  OperationTags.FILE_IO_READ, stream, path, Integer.MAX_VALUE)
              < 0;
      // Entered after the dispatch: when it throws, no exit advice runs to leave the scope.
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
      return corrupt;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final boolean corrupt, @Advice.Return final byte[] bytes) {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
      if (corrupt && bytes != null) {
        BootstrapDispatcher.corruptBytes(bytes, 0, bytes.length);
      }
    }
  }

  /**
   * Intercepts {@link java.io.FileInputStream#readNBytes(int)} ({@code len} requested), as {@link
   * FileReadAllBytesAdvice}.
   */
  static final class FileReadNBytesAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object stream,
        @Advice.Argument(0) final int len,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      final boolean corrupt =
          BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_READ, stream, path, len) < 0;
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
      return corrupt;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final boolean corrupt, @Advice.Return final byte[] bytes) {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
      if (corrupt && bytes != null) {
        BootstrapDispatcher.corruptBytes(bytes, 0, bytes.length);
      }
    }
  }

  /**
   * Intercepts the single-byte {@link java.io.FileOutputStream#write(int)}.
   *
   * <p>Passes {@code "FILE_IO_WRITE"} as the operation tag so the dispatcher can route to the
   * correct {@link com.macstab.chaos.jvm.api.OperationType#FILE_IO_WRITE} scenario.
   */
  static final class FileWriteByteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object stream, @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_WRITE, stream, path, 1);
    }
  }

  /** Intercepts {@link java.io.FileOutputStream#write(byte[])} ({@code b.length} bytes). */
  static final class FileWriteArrayAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object stream,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          OperationTags.FILE_IO_WRITE, stream, path, buffer == null ? -1 : buffer.length);
    }
  }

  /** Intercepts {@link java.io.FileOutputStream#write(byte[], int, int)} ({@code len} bytes). */
  static final class FileWriteRangeAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object stream,
        @Advice.Argument(2) final int len,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_WRITE, stream, path, len);
    }
  }

  /**
   * Intercepts {@code FileChannelImpl.read(ByteBuffer[, long])}: one channel read, reporting the
   * bytes requested ({@code remaining()}) and the channel's path, and corrupting the bytes read
   * under a {@code CorruptReadEffect}.
   */
  static final class FileChannelReadAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      // A read-only buffer takes no bytes: the JDK refuses the read, so a corruption counts none.
      return BootstrapDispatcher.beforeFileIo(
              OperationTags.FILE_IO_READ,
              channel,
              path,
              buffer == null ? -1 : buffer.isReadOnly() ? 0 : buffer.remaining())
          < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer,
        @Advice.Return final int read) {
      if (corrupt) {
        BootstrapDispatcher.corruptBuffer(buffer, read);
      }
    }
  }

  /**
   * Intercepts {@code FileChannelImpl.write(ByteBuffer[, long])}. Under a {@code TornWriteEffect}
   * the buffer's limit is lowered for the call, so the channel writes fewer bytes and returns that
   * count, and restored afterwards.
   */
  static final class FileChannelWriteAdvice {
    @Advice.OnMethodEnter
    static int enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      final int requested = buffer == null ? -1 : buffer.remaining();
      final int count =
          BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_WRITE, channel, path, requested);
      if (buffer != null && count >= 0 && count < requested) {
        final int originalLimit = buffer.limit();
        buffer.limit(buffer.position() + count);
        return originalLimit;
      }
      return -1;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final int originalLimit,
        @Advice.Argument(0) final java.nio.ByteBuffer buffer) {
      if (originalLimit >= 0) {
        buffer.limit(originalLimit);
      }
    }
  }

  /** Intercepts {@code FileChannelImpl.read(ByteBuffer[], int, int)}, a scattering read. */
  static final class FileChannelScatterReadAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer[] buffers,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileChannelIo(
          OperationTags.FILE_IO_READ, channel, path, buffers, offset, length);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Enter final ChannelBuffers shaping, @Advice.Return final long read) {
      if (shaping != null) {
        shaping.finish(read);
      }
    }
  }

  /** Intercepts {@code FileChannelImpl.write(ByteBuffer[], int, int)}, a gathering write. */
  static final class FileChannelGatherWriteAdvice {
    @Advice.OnMethodEnter
    static @Nullable ChannelBuffers enter(
        @Advice.This final Object channel,
        @Advice.Argument(0) final java.nio.ByteBuffer[] buffers,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return BootstrapDispatcher.beforeFileChannelIo(
          OperationTags.FILE_IO_WRITE, channel, path, buffers, offset, length);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final ChannelBuffers shaping, @Advice.Return final long written) {
      if (shaping != null) {
        shaping.finish(written);
      }
    }
  }

  /** Intercepts {@code FileChannelImpl.map(MapMode, long, long[, Arena])}: the region's size. */
  static final class FileChannelMapAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object channel,
        @Advice.Argument(2) final long size,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          OperationTags.FILE_IO_MAP, channel, path, (int) Math.min(size, Integer.MAX_VALUE));
    }
  }

  /**
   * Intercepts {@code FileChannelImpl.transferTo(long, long, WritableByteChannel)}: one read, also
   * when the JDK copies through the channel's own reads ({@link
   * BootstrapDispatcher#SCOPE_FILE_TRANSFER}).
   */
  static final class FileChannelTransferToAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object channel,
        @Advice.Argument(1) final long count,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          OperationTags.FILE_IO_TRANSFER_TO,
          channel,
          path,
          (int) Math.min(count, Integer.MAX_VALUE));
      // Entered after the dispatch: when it throws, no exit advice runs to leave the scope.
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
    }
  }

  /**
   * Intercepts {@code FileChannelImpl.transferFrom(ReadableByteChannel, long, long)}: a write,
   * whose count a torn write lowers.
   */
  static final class FileChannelTransferFromAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object channel,
        @Advice.Argument(value = 2, readOnly = false) long count,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      final int requested = (int) Math.min(count, Integer.MAX_VALUE);
      final int allowed =
          BootstrapDispatcher.beforeFileIo(
              OperationTags.FILE_IO_TRANSFER_FROM, channel, path, requested);
      if (allowed >= 0 && allowed < requested) {
        count = allowed;
      }
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
    }
  }

  /**
   * Intercepts {@code MappedByteBuffer.force(int, int)} of a file mapping (msync), which {@code
   * force()} delegates to. The JDK reports a failed msync as {@link java.io.UncheckedIOException}.
   */
  static final class MappedBufferForceAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object buffer, @Advice.FieldValue("fd") final Object fd)
        throws Throwable {
      if (fd != null) {
        try {
          BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_SYNC, buffer, null, -1);
        } catch (final java.io.IOException failed) {
          throw new java.io.UncheckedIOException(failed);
        }
      }
    }
  }

  /**
   * Intercepts a platform provider's {@code copy(Path, Path, CopyOption...)}: it opens the source
   * and the target ({@code FILE_IO_OPEN} of each).
   */
  static final class FilesCopyAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object provider,
        @Advice.Argument(0) final java.nio.file.Path source,
        @Advice.Argument(1) final java.nio.file.Path target)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          OperationTags.FILE_IO_OPEN, provider, source == null ? null : source.toString(), -1);
      BootstrapDispatcher.beforeFileIo(
          OperationTags.FILE_IO_OPEN, provider, target == null ? null : target.toString(), -1);
    }
  }

  /** Intercepts {@code FileChannelImpl.force(boolean)}: fsync of a channel. */
  static final class FileChannelForceAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object channel, @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_SYNC, channel, path, -1);
    }
  }

  /**
   * Intercepts {@code FileChannelImpl.truncate(long)} and {@code RandomAccessFile.setLength(long)}:
   * both hold the file's path in a {@code path} field.
   */
  static final class FileTruncateAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object file, @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_TRUNCATE, file, path, -1);
    }
  }

  /**
   * Intercepts {@code AbstractFileSystemProvider.delete(Path)}, which {@code Files.delete} reaches;
   * {@code deleteIfExists} has its own advice below.
   */
  static final class FilesDeleteAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object provider, @Advice.Argument(0) final java.nio.file.Path file)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          OperationTags.FILE_IO_DELETE, provider, file == null ? null : file.toString(), -1);
    }
  }

  /**
   * Intercepts {@code AbstractFileSystemProvider.deleteIfExists(Path)}. An injected {@code ENOENT}
   * returns {@code false}, as the real method does for a missing file; other failures throw.
   */
  static final class FilesDeleteIfExistsAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object provider, @Advice.Argument(0) final java.nio.file.Path file)
        throws Throwable {
      try {
        BootstrapDispatcher.beforeFileIo(
            OperationTags.FILE_IO_DELETE, provider, file == null ? null : file.toString(), -1);
        return false;
      } catch (final java.nio.file.NoSuchFileException missing) {
        return true;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean missing, @Advice.Return(readOnly = false) boolean deleted) {
      if (missing) {
        deleted = false;
      }
    }
  }

  /**
   * Intercepts the private {@code open(String, ...)} of {@code FileInputStream}, {@code
   * FileOutputStream} and {@code RandomAccessFile}: the file a constructor opens. Opens of archives
   * are skipped: they are class loading (see {@link BootstrapDispatcher#isArchive(String)}).
   */
  static final class FileOpenAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object stream, @Advice.Argument(0) final String name)
        throws Throwable {
      if (!BootstrapDispatcher.isArchive(name)) {
        BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_OPEN, stream, name, -1);
      }
    }
  }

  /**
   * Intercepts a platform file system provider's {@code newByteChannel} / {@code newFileChannel}:
   * one open, also when {@code newByteChannel} opens through {@code newFileChannel} ({@link
   * BootstrapDispatcher#SCOPE_FILE_OPEN}).
   */
  static final class FilesOpenAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object provider, @Advice.Argument(0) final java.nio.file.Path path)
        throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_OPEN)) {
        try {
          BootstrapDispatcher.beforeFileIo(
              OperationTags.FILE_IO_OPEN, provider, path == null ? null : path.toString(), -1);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_OPEN);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_OPEN);
    }
  }

  /** Intercepts a file system provider's {@code move(Path, Path, CopyOption...)}. */
  static final class FilesMoveAdvice {
    @Advice.OnMethodEnter
    static void enter(
        @Advice.This final Object provider,
        @Advice.Argument(0) final java.nio.file.Path source,
        @Advice.Argument(1) final java.nio.file.Path target)
        throws Throwable {
      BootstrapDispatcher.beforeFileIo(
          OperationTags.FILE_IO_MOVE, provider, source == null ? null : source.toString(), -1);
      BootstrapDispatcher.beforeFileIo(
          OperationTags.FILE_IO_MOVE_TARGET,
          provider,
          target == null ? null : target.toString(),
          -1);
    }
  }

  /**
   * Intercepts {@code close()} of {@code FileInputStream}, {@code FileOutputStream} and {@code
   * RandomAccessFile}: evaluated after the close, once for the stream and its channel together.
   * Archives are skipped: they are class loading.
   */
  static final class FileStreamCloseAdvice {
    @Advice.OnMethodEnter
    static boolean enter(@Advice.FieldValue("closed") final boolean closed) {
      final boolean outermost =
          BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_CLOSE);
      return outermost && !closed;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final Object stream,
        @Advice.Enter final boolean closing,
        @Advice.Thrown final Throwable thrown,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_CLOSE);
      if (closing && thrown == null && !BootstrapDispatcher.isArchive(path)) {
        BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_CLOSE, stream, path, -1);
      }
    }
  }

  /** Intercepts {@code FileChannelImpl.implCloseChannel()}, as {@link FileStreamCloseAdvice}. */
  static final class FileChannelCloseAdvice {
    @Advice.OnMethodEnter
    static boolean enter() {
      return BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_CLOSE);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.This final Object channel,
        @Advice.Enter final boolean outermost,
        @Advice.Thrown final Throwable thrown,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_CLOSE);
      if (outermost && thrown == null && !BootstrapDispatcher.isArchive(path)) {
        BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_CLOSE, channel, path, -1);
      }
    }
  }

  /**
   * Intercepts {@link java.io.File#delete()}. {@code java.io} reports a failed delete by returning
   * {@code false}, so an injected I/O failure skips the delete and returns {@code false}.
   */
  static final class FileDeleteAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object file, @Advice.FieldValue("path") final String path)
        throws Throwable {
      try {
        BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_DELETE, file, path, -1);
        return false;
      } catch (final java.io.IOException failed) {
        return true;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean failed, @Advice.Return(readOnly = false) boolean deleted) {
      if (failed) {
        deleted = false;
      }
    }
  }

  /**
   * Intercepts {@link java.io.File#renameTo(java.io.File)}; an injected I/O failure skips the
   * rename and returns {@code false}, as {@link FileDeleteAdvice} does for deletes.
   */
  static final class FileRenameAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(
        @Advice.This final Object file,
        @Advice.Argument(0) final java.io.File destination,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      try {
        BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_MOVE, file, path, -1);
        BootstrapDispatcher.beforeFileIo(
            OperationTags.FILE_IO_MOVE_TARGET,
            file,
            destination == null ? null : destination.getPath(),
            -1);
        return false;
      } catch (final java.io.IOException failed) {
        return true;
      }
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean failed, @Advice.Return(readOnly = false) boolean renamed) {
      if (failed) {
        renamed = false;
      }
    }
  }

  /** Intercepts {@link java.io.FileDescriptor#sync()}: fsync of a stream's descriptor. */
  static final class FileDescriptorSyncAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Object descriptor) throws Throwable {
      BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_SYNC, descriptor, null, -1);
    }
  }

  /**
   * Intercepts {@code RandomAccessFile.read()}. Reads of archives are skipped: see {@link
   * BootstrapDispatcher#isArchive(String)}.
   */
  static final class RandomAccessReadByteAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object file, @Advice.FieldValue("path") final String path)
        throws Throwable {
      return !BootstrapDispatcher.isArchive(path)
          && BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_READ, file, path, 1) < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt, @Advice.Return(readOnly = false) int value) {
      if (corrupt) {
        value = BootstrapDispatcher.corruptByte(value);
      }
    }
  }

  /**
   * Intercepts {@code RandomAccessFile.readLine()}: one read of the line, also when the file reads
   * it byte by byte ({@link BootstrapDispatcher#SCOPE_FILE_TRANSFER}). The bytes requested are
   * unbounded ({@link Integer#MAX_VALUE}). A corrupted read flips one bit of the line's bytes, each
   * of which {@code readLine} returns as one {@code char}. Reads of archives are skipped: see
   * {@link BootstrapDispatcher#isArchive(String)}.
   */
  static final class RandomAccessReadLineAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object file, @Advice.FieldValue("path") final String path)
        throws Throwable {
      final boolean corrupt =
          !BootstrapDispatcher.isArchive(path)
              && BootstrapDispatcher.beforeFileIo(
                      OperationTags.FILE_IO_READ, file, path, Integer.MAX_VALUE)
                  < 0;
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
      return corrupt;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Enter final boolean corrupt, @Advice.Return(readOnly = false) String line) {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_TRANSFER);
      if (corrupt && line != null) {
        final byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        BootstrapDispatcher.corruptBytes(bytes, 0, bytes.length);
        line = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
      }
    }
  }

  /**
   * Intercepts {@code RandomAccessFile.readBytes(byte[], int, int)}, the funnel of every array
   * read. Reads of archives are skipped: see {@link BootstrapDispatcher#isArchive(String)}.
   */
  static final class RandomAccessReadRangeAdvice {
    @Advice.OnMethodEnter
    static boolean enter(
        @Advice.This final Object file,
        @Advice.Argument(2) final int len,
        @Advice.FieldValue("path") final String path)
        throws Throwable {
      return !BootstrapDispatcher.isArchive(path)
          && BootstrapDispatcher.beforeFileIo(OperationTags.FILE_IO_READ, file, path, len) < 0;
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean corrupt,
        @Advice.Argument(0) final byte[] buffer,
        @Advice.Argument(1) final int offset,
        @Advice.Return final int read) {
      if (corrupt) {
        BootstrapDispatcher.corruptBytes(buffer, offset, read);
      }
    }
  }
}

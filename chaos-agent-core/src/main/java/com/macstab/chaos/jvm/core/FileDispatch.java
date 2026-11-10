package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * Dispatch of file operations: reads, writes, opens, closes, maps, transfers, fsync, truncation,
 * deletion and moves.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class FileDispatch {

  private final DispatchEngine engine;

  FileDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Called before a file operation: a read, write, open, close, sync, truncation, deletion, move,
   * map or channel transfer.
   *
   * @param operation the name of a {@code FILE_IO_*} operation, or a transfer tag ({@link
   *     DispatchEngine#TRANSFER_TO}, {@link DispatchEngine#TRANSFER_FROM})
   * @param stream the stream instance
   * @param path the path the stream was opened with, or {@code null}
   * @param byteCount the bytes read (requested) or written by the call
   * @return {@code byteCount}, encoded as {@code -(byteCount) - 1} when a read's bytes must be
   *     corrupted (see {@code DispatchEngine.corruptFlag})
   * @throws Throwable if an active scenario throws to simulate an I/O failure
   */
  public int beforeFileIo(
      final String operation,
      final @Nullable Object stream,
      final @Nullable String path,
      final int byteCount)
      throws Throwable {
    final OperationType fileIoOpType = resolveFileIoOperationType(operation);
    if (engine.idle(fileIoOpType)) {
      return byteCount;
    }
    return engine.transferCount(
        (engine.evaluate(
            new InvocationContext(
                fileIoOpType,
                stream == null ? "java.io.FileInputStream" : stream.getClass().getName(),
                null,
                transferTag(operation),
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                byteCount,
                path,
                null,
                InvocationContext.UNKNOWN,
                null,
                InvocationContext.UNKNOWN))),
        byteCount);
  }

  /** The method a transfer tag stands for, as the context's target name; {@code null} otherwise. */
  private static @Nullable String transferTag(final String operationTag) {
    return switch (operationTag) {
      case DispatchEngine.TRANSFER_TO -> "transferTo";
      case DispatchEngine.TRANSFER_FROM -> "transferFrom";
      default -> null;
    };
  }

  private static OperationType resolveFileIoOperationType(final String operationTag) {
    return switch (operationTag) {
      case "FILE_IO_READ" -> OperationType.FILE_IO_READ;
      case "FILE_IO_WRITE" -> OperationType.FILE_IO_WRITE;
      case "FILE_IO_SYNC" -> OperationType.FILE_IO_SYNC;
      case "FILE_IO_TRUNCATE" -> OperationType.FILE_IO_TRUNCATE;
      case "FILE_IO_DELETE" -> OperationType.FILE_IO_DELETE;
      case "FILE_IO_MOVE" -> OperationType.FILE_IO_MOVE;
      case "FILE_IO_OPEN" -> OperationType.FILE_IO_OPEN;
      case "FILE_IO_MAP" -> OperationType.FILE_IO_MAP;
      case "FILE_IO_CLOSE" -> OperationType.FILE_IO_CLOSE;
      case "FILE_IO_MOVE_TARGET" -> OperationType.FILE_IO_MOVE_TARGET;
      // FileChannel.transferTo reads the channel's file, transferFrom writes it.
      case DispatchEngine.TRANSFER_TO -> OperationType.FILE_IO_READ;
      case DispatchEngine.TRANSFER_FROM -> OperationType.FILE_IO_WRITE;
      default ->
          throw new IllegalArgumentException(
              "Unknown file I/O operation tag '" + operationTag + "'");
    };
  }
}

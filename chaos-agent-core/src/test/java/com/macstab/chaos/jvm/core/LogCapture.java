package com.macstab.chaos.jvm.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Records what a {@link Logger} publishes while it is open, with the logger lowered to {@link
 * Level#ALL} so FINE diagnostics are produced too; closing restores the logger's level.
 */
final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final Level previousLevel;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final Handler handler =
      new Handler() {
        @Override
        public void publish(final LogRecord record) {
          records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  private LogCapture(final String loggerName) {
    this.logger = Logger.getLogger(loggerName);
    this.previousLevel = logger.getLevel();
    handler.setLevel(Level.ALL);
    logger.setLevel(Level.ALL);
    logger.addHandler(handler);
  }

  /** Captures the agent's shared {@code com.macstab.chaos.jvm} logger. */
  static LogCapture agent() {
    return new LogCapture("com.macstab.chaos.jvm");
  }

  /** Captures the logger a class names after itself. */
  static LogCapture of(final Class<?> owner) {
    return new LogCapture(owner.getName());
  }

  /** The formatted messages published so far. */
  List<String> messages() {
    return records.stream().map(LogRecord::getMessage).toList();
  }

  /** The records published so far, with their level and thrown exception. */
  List<LogRecord> records() {
    return List.copyOf(records);
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
    logger.setLevel(previousLevel);
  }
}

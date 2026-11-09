package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import java.util.ArrayDeque;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The agent's {@code java.util.logging} lines. Nothing here touches JUL before a line is written:
 * the first {@link Logger} initialises the {@code LogManager}, and an agent that does so during
 * {@code premain} fixes the {@code LogManager} class before the application can choose its own
 * ({@code java.util.logging.manager}, as WildFly and Quarkus set it).
 *
 * <p>While lines are held ({@link #hold()}, from {@code premain} until the application has
 * initialised JUL), each line is kept in a buffer of {@value #HELD_LINES} lines, the oldest dropped
 * first, and written on {@link #release()} with its original time. Otherwise a line goes to the JUL
 * logger of its name, looked up when the first line is written.
 *
 * <p>Thread-safe. Agent-internal; not an API.
 */
@Internal
public final class AgentLog {

  /** The most lines kept while held; older ones are dropped and counted. */
  static final int HELD_LINES = 256;

  /** The name of the agent's shared logger. */
  public static final String AGENT = "com.macstab.chaos.jvm";

  private static final Object LOCK = new Object();

  /** The held lines; {@code null} when lines are written at once. Guarded by {@link #LOCK}. */
  private static @Nullable ArrayDeque<LogRecord> held;

  /** Lines dropped from {@link #held} since {@link #hold()}. Guarded by {@link #LOCK}. */
  private static long dropped;

  /** Whether {@link #held} is set: read without the lock on every line. */
  private static volatile boolean holding;

  private final String name;

  private volatile @Nullable Logger logger;

  private AgentLog(final String name) {
    this.name = name;
  }

  /**
   * The log of the given JUL logger name.
   *
   * @param name the logger name
   * @return the log
   */
  public static AgentLog named(final String name) {
    return new AgentLog(name);
  }

  /**
   * Whether a line at {@code level} is written. While lines are held every line is kept, so it is
   * {@code true} then.
   *
   * @param level the level
   * @return whether a line at that level is written or held
   */
  public boolean isLoggable(final Level level) {
    return holding || logger().isLoggable(level);
  }

  /**
   * Logs a {@code FINE} line.
   *
   * @param message the line, built only when it is written or held
   */
  public void fine(final Supplier<String> message) {
    log(Level.FINE, null, message);
  }

  /**
   * Logs a {@code WARNING} line.
   *
   * @param message the line, built only when it is written or held
   */
  public void warning(final Supplier<String> message) {
    log(Level.WARNING, null, message);
  }

  /**
   * Logs a line.
   *
   * @param level the level
   * @param message the line, built only when it is written or held
   */
  public void log(final Level level, final Supplier<String> message) {
    log(level, null, message);
  }

  /**
   * Logs a line.
   *
   * @param level the level
   * @param thrown the failure the line reports, or {@code null}
   * @param message the line, built only when it is written or held
   */
  public void log(
      final Level level, final @Nullable Throwable thrown, final Supplier<String> message) {
    if (holding && held(level, thrown, message)) {
      return;
    }
    logger().log(level, thrown, message);
  }

  private boolean held(
      final Level level, final @Nullable Throwable thrown, final Supplier<String> message) {
    // Built outside the lock: a supplier can be slow. A LogRecord keeps the time it was made.
    final LogRecord line = new LogRecord(level, message.get());
    line.setLoggerName(name);
    // The caller would otherwise be inferred when the line is written, from the releasing thread.
    line.setSourceClassName(name);
    line.setThrown(thrown);
    synchronized (LOCK) {
      final ArrayDeque<LogRecord> lines = held;
      if (lines == null) {
        return false;
      }
      if (lines.size() == HELD_LINES) {
        lines.removeFirst();
        dropped++;
      }
      lines.addLast(line);
      return true;
    }
  }

  private Logger logger() {
    Logger resolved = logger;
    if (resolved == null) {
      resolved = Logger.getLogger(name);
      logger = resolved;
    }
    return resolved;
  }

  /**
   * Holds every line from now on until {@link #release()}: {@code premain} calls it first, before
   * any agent class could log. Holding twice keeps the lines already held.
   */
  public static void hold() {
    synchronized (LOCK) {
      if (held == null) {
        held = new ArrayDeque<>();
        dropped = 0;
      }
      holding = true;
    }
  }

  /**
   * Writes the held lines in order, the dropped ones counted in one line before them, and writes
   * every later line at once. Does nothing when no line is held.
   */
  public static void release() {
    final ArrayDeque<LogRecord> lines;
    final long lost;
    synchronized (LOCK) {
      lines = held;
      lost = dropped;
      held = null;
      dropped = 0;
      holding = false;
    }
    if (lines == null) {
      return;
    }
    if (lost > 0) {
      named(AGENT)
          .warning(
              () ->
                  "[chaos-agent] "
                      + lost
                      + " log lines of the agent's start were dropped; the "
                      + HELD_LINES
                      + " latest follow");
    }
    for (final LogRecord line : lines) {
      Logger.getLogger(line.getLoggerName()).log(line);
    }
  }

  /**
   * Whether lines are held now.
   *
   * @return {@code true} between {@link #hold()} and {@link #release()}
   */
  public static boolean holding() {
    return holding;
  }
}

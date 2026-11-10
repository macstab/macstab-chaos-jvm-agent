package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgentLog")
class AgentLogTest {

  /** Outside the agent's logger tree: its lines do not reach the agent logger's handlers too. */
  private static final String NAME = "agentlog.test.AgentLogTest";

  private final List<LogRecord> written = new CopyOnWriteArrayList<>();
  private final Logger logger = Logger.getLogger(NAME);
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(final LogRecord line) {
          written.add(line);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };
  private Level levelBefore;

  @BeforeEach
  void capture() {
    levelBefore = logger.getLevel();
    logger.setLevel(Level.ALL);
    logger.addHandler(capture);
  }

  @AfterEach
  void restore() {
    AgentLog.release();
    logger.removeHandler(capture);
    logger.setLevel(levelBefore);
  }

  @Test
  @DisplayName("a line is written at once when nothing is held")
  void writtenAtOnce() {
    final AgentLog log = AgentLog.named(NAME);

    log.warning(() -> "now");
    log.warning(() -> "again");

    assertThat(written).extracting(LogRecord::getMessage).containsExactly("now", "again");
    assertThat(AgentLog.holding()).isFalse();
  }

  @Test
  @DisplayName("held lines are written on release, in order, with their level, failure and logger")
  void heldUntilRelease() {
    final AgentLog log = AgentLog.named(NAME);
    final IllegalStateException failure = new IllegalStateException("boom");

    AgentLog.hold();
    log.log(Level.INFO, () -> "first");
    log.log(Level.WARNING, failure, () -> "second");
    assertThat(written).as("nothing reaches the logger while held").isEmpty();

    AgentLog.release();

    assertThat(written).extracting(LogRecord::getMessage).containsExactly("first", "second");
    assertThat(written.get(0).getLevel()).isEqualTo(Level.INFO);
    assertThat(written.get(1).getThrown()).isSameAs(failure);
    assertThat(written.get(1).getLoggerName()).isEqualTo(NAME);
    assertThat(written.get(1).getSourceClassName()).isEqualTo(NAME);
    assertThat(AgentLog.holding()).isFalse();
  }

  @Test
  @DisplayName(
      "while held, a line below INFO is neither held nor built, so it never pushes out a WARNING")
  void belowInfoIsNotHeld() {
    final AgentLog log = AgentLog.named(NAME);
    final java.util.concurrent.atomic.AtomicInteger built =
        new java.util.concurrent.atomic.AtomicInteger();

    AgentLog.hold();
    log.warning(() -> "important");
    for (int i = 0; i < AgentLog.HELD_LINES + 44; i++) {
      log.fine(
          () -> {
            built.incrementAndGet();
            return "noise";
          });
    }
    assertThat(log.isLoggable(Level.FINE)).as("FINE while held").isFalse();
    assertThat(log.isLoggable(Level.INFO)).as("INFO while held").isTrue();

    AgentLog.release();

    assertThat(written).extracting(LogRecord::getMessage).containsExactly("important");
    assertThat(built).as("no FINE line was formatted").hasValue(0);
  }

  @Test
  @DisplayName("past the buffer the oldest lines are dropped and counted in one line")
  void overflowDropsTheOldest() {
    final Logger agent = Logger.getLogger(AgentLog.AGENT);
    agent.addHandler(capture);
    try {
      final AgentLog log = AgentLog.named(NAME);
      AgentLog.hold();
      for (int i = 0; i < AgentLog.HELD_LINES + 3; i++) {
        final int line = i;
        log.log(Level.INFO, () -> "line " + line);
      }

      AgentLog.release();

      assertThat(written).hasSize(AgentLog.HELD_LINES + 1);
      assertThat(written.get(0).getMessage())
          .isEqualTo(
              "[chaos-agent] 3 log lines of the agent's start were dropped; the "
                  + AgentLog.HELD_LINES
                  + " latest follow");
      assertThat(written.get(1).getMessage()).isEqualTo("line 3");
    } finally {
      agent.removeHandler(capture);
    }
  }

  @Test
  @DisplayName("at exit, the held lines go straight to System.err, not through JUL")
  void releasedToStandardError() {
    final AgentLog log = AgentLog.named(NAME);
    final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    final java.io.PrintStream errBefore = System.err;
    AgentLog.hold();
    log.log(Level.WARNING, new IllegalStateException("boom"), () -> "held at exit");
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      AgentLog.releaseToStandardError();
    } finally {
      System.setErr(errBefore);
    }

    assertThat(written).as("nothing goes through JUL").isEmpty();
    assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("WARNING " + NAME + ": held at exit")
        .contains("java.lang.IllegalStateException: boom");
    assertThat(AgentLog.holding()).isFalse();
  }

  @Test
  @DisplayName("holding twice keeps the held lines; releasing twice writes them once")
  void holdAndReleaseAreIdempotent() {
    final AgentLog log = AgentLog.named(NAME);

    AgentLog.hold();
    log.warning(() -> "kept");
    AgentLog.hold();
    AgentLog.release();
    AgentLog.release();

    assertThat(written).extracting(LogRecord::getMessage).containsExactly("kept");
  }

  @Test
  @DisplayName("at exit, the dropped lines are counted on System.err before the held ones")
  void droppedLinesCountedOnStandardError() {
    final AgentLog log = AgentLog.named(NAME);
    final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    final java.io.PrintStream errBefore = System.err;
    AgentLog.hold();
    for (int i = 0; i < AgentLog.HELD_LINES + 2; i++) {
      final int line = i;
      log.warning(() -> "warning " + line);
    }
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      AgentLog.releaseToStandardError();
    } finally {
      System.setErr(errBefore);
    }

    final java.util.List<String> lines =
        captured.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
    assertThat(lines).hasSize(AgentLog.HELD_LINES + 1);
    assertThat(lines.get(0))
        .isEqualTo(
            "WARNING "
                + AgentLog.AGENT
                + ": [chaos-agent] 2 log lines of the agent's start were dropped; the "
                + AgentLog.HELD_LINES
                + " latest follow");
    assertThat(lines.get(1)).endsWith(": warning 2");
  }
}

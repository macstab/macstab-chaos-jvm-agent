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

    assertThat(written).extracting(LogRecord::getMessage).containsExactly("now");
    assertThat(AgentLog.holding()).isFalse();
  }

  @Test
  @DisplayName("held lines are written on release, in order, with their level, failure and logger")
  void heldUntilRelease() {
    final AgentLog log = AgentLog.named(NAME);
    final IllegalStateException failure = new IllegalStateException("boom");

    AgentLog.hold();
    log.fine(() -> "first");
    log.log(Level.WARNING, failure, () -> "second");
    assertThat(log.isLoggable(Level.FINEST)).as("every line is held").isTrue();
    assertThat(written).as("nothing reaches the logger while held").isEmpty();

    AgentLog.release();

    assertThat(written).extracting(LogRecord::getMessage).containsExactly("first", "second");
    assertThat(written.get(0).getLevel()).isEqualTo(Level.FINE);
    assertThat(written.get(1).getThrown()).isSameAs(failure);
    assertThat(written.get(1).getLoggerName()).isEqualTo(NAME);
    assertThat(written.get(1).getSourceClassName()).isEqualTo(NAME);
    assertThat(AgentLog.holding()).isFalse();
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
  @DisplayName("holding twice keeps the held lines; releasing twice writes them once")
  void holdAndReleaseAreIdempotent() {
    final AgentLog log = AgentLog.named(NAME);

    AgentLog.hold();
    log.fine(() -> "kept");
    AgentLog.hold();
    AgentLog.release();
    AgentLog.release();

    assertThat(written).extracting(LogRecord::getMessage).containsExactly("kept");
  }
}

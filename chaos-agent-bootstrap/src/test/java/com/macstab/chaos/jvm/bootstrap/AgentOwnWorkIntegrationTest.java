package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the agent does while it manages scenarios is its own work, never a target (L3): a scenario
 * that selects file writes, queue offers or reflective calls does not see the agent's log lines,
 * its event hand-off to an asynchronous listener, its debug dump or its stressor teardown. Each
 * test runs {@link AgentOwnWorkProbeMain} in a child JVM with the agent attached.
 */
@DisplayName("The agent's own work in a child JVM")
class AgentOwnWorkIntegrationTest {

  @TempDir Path tempDir;

  private ChildJvmProbe probe;

  @BeforeEach
  void setUp() {
    probe = new ChildJvmProbe(tempDir);
  }

  @Nested
  @DisplayName("Event delivery")
  class EventDelivery {

    @Test
    @DisplayName("the agent's STARTED and STOPPED log lines are no file write of a scenario")
    void logLinesAreAgentWork() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(AgentOwnWorkProbeMain.class, "log-lines");

      assertThat(result.output()).contains("type=STARTED scenarioId=\"other\"");
      assertThat(result.longValue("matched")).as("file writes matched").isZero();
      assertThat(result.longValue("elapsedMillis"))
          .as("not the 2 s write delay")
          .isLessThan(1_000L);
    }

    @Test
    @DisplayName("an asynchronous listener gets every event while queue offers are rejected")
    void asyncHandOffIsAgentWork() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(AgentOwnWorkProbeMain.class, "async-listener");

      assertThat(result.value("received")).isEqualTo("REGISTERED,STARTED,STOPPED");
      assertThat(result.longValue("dropped")).isZero();
      assertThat(result.longValue("matched")).as("queue offers matched").isZero();
    }

    @Test
    @DisplayName("a plan's debug dump on start is no file write of a scenario")
    void debugDumpIsAgentWork() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(AgentOwnWorkProbeMain.class, "debug-dump");

      assertThat(result.longValue("matched")).as("file writes matched").isZero();
      assertThat(result.longValue("elapsedMillis"))
          .as("not the 2 s write delay")
          .isLessThan(1_000L);
    }
  }

  @Nested
  @DisplayName("Stressor teardown")
  class StressorTeardown {

    @Test
    @DisplayName("stopping direct-buffer pressure frees its buffers while reflection is rejected")
    void directBufferCloseIsAgentWork() throws Exception {
      final ChildJvmProbe.ProbeResult result =
          probe.runWithoutAgent(AgentOwnWorkProbeMain.class, "direct-buffer-close");

      assertThat(result.longValue("freedMiB")).isGreaterThanOrEqualTo(60L);
      assertThat(result.longValue("matched")).as("reflective calls matched").isZero();
    }
  }
}

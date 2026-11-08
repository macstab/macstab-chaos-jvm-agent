package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Self-attach in a JVM started without {@code -Djdk.attach.allowAttachSelf} and without {@code
 * -XX:+EnableDynamicAgentLoading}, as the documentation describes it: the agent installs and
 * applies chaos, and the JVM prints the JEP 451 warning about a dynamically loaded agent.
 */
@DisplayName("Self-attach without JVM flags (end-to-end)")
class SelfAttachWithoutFlagsIntegrationTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("installs, applies a delay, and the JVM warns that an agent was loaded dynamically")
  void installsWithoutFlags() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutAgentOrFlags(StartupPathProbeMain.class, "self-attach");

    assertThat(result.value("allowAttachSelf")).isEqualTo("null");
    assertThat(result.longValue("applied")).isEqualTo(1L);
    assertThat(result.longValue("submitMillis")).isGreaterThanOrEqualTo(150L);
    assertThat(result.output()).contains("A Java agent has been loaded dynamically");
  }
}

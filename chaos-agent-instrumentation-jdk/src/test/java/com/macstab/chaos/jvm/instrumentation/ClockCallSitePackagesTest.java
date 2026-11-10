package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Clock call-site packages")
class ClockCallSitePackagesTest {

  @Test
  @DisplayName("keeps application prefixes and drops blanks")
  void keepsApplicationPrefixes() {
    assertThat(
            JdkInstrumentationInstaller.validatedClockPackages(
                Arrays.asList(" com.example. ", "", null, "org.acme.Clock")))
        .containsExactly("com.example.", "org.acme.Clock");
  }

  @Test
  @DisplayName("refuses prefixes that would rewrite JDK classes, including ones that cover them")
  void refusesJdkPrefixes() {
    for (final String prefix :
        List.of("java.", "java.lang", "jdk.internal.", "sun.", "com.sun.", "j", "com.")) {
      assertThatThrownBy(() -> JdkInstrumentationInstaller.validatedClockPackages(List.of(prefix)))
          .as(prefix)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("JDK");
    }
  }

  @Test
  @DisplayName("warns with the agent's log prefix when a clock scenario has no call-site packages")
  void warnsWithAgentPrefixWithoutPackages() {
    final List<String> previous = JdkInstrumentationInstaller.clockPackages;
    final Logger logger = Logger.getLogger(JdkInstrumentationInstaller.class.getName());
    final List<String> messages = new CopyOnWriteArrayList<>();
    final Handler capture =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            messages.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(capture);
    JdkInstrumentationInstaller.clockPackages = List.of();
    try {
      final AgentBuilder builder = new AgentBuilder.Default();

      assertThat(ClockHooks.applyClockCallSiteTransformations(builder)).isSameAs(builder);

      assertThat(messages)
          .singleElement()
          .asString()
          .startsWith("[chaos-agent] a SYSTEM_CLOCK_MILLIS/NANOS scenario is active");
    } finally {
      JdkInstrumentationInstaller.clockPackages = previous;
      logger.removeHandler(capture);
    }
  }
}

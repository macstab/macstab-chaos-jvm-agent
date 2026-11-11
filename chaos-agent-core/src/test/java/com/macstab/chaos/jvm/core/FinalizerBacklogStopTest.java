package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stopping a finalizer backlog ends the finalizer sleeping in it (L17): the JVM's single Finalizer
 * thread runs other objects' finalizers again at once, however long the configured delay. Run in a
 * child JVM, since a Finalizer thread left sleeping would hold up every later test's finalizers.
 */
@DisplayName("A stopped finalizer backlog")
class FinalizerBacklogStopTest {

  @Test
  @DisplayName("releases the Finalizer thread from an hour-long finalizer at once")
  void stopReleasesTheFinalizerThread() throws Exception {
    final String output =
        BoundedChildJvm.run(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                FinalizerStopProbeMain.class.getName()),
            Duration.ofSeconds(60));

    assertThat(output)
        .as("the Finalizer thread slept in the stressor's finalize()")
        .contains("sleeping=true");
    assertThat(output)
        .as("an application finalizer ran after the stop")
        .contains("finalizedAfterStop=true");
    final long millis =
        Long.parseLong(
            output
                .lines()
                .filter(line -> line.startsWith("millis="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no millis= in:\n" + output))
                .substring("millis=".length()));
    assertThat(millis).as("time from the stop to the next finalizer").isLessThan(5_000L);
  }
}

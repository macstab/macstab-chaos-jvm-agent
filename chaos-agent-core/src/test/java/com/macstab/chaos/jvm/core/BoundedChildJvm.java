package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a child JVM for a test with its output written to a file, not a pipe, and waits for it at
 * most a bound. Reading a pipe to its end blocks until the child exits, which no JUnit timeout can
 * interrupt; a child still running at the bound is destroyed and fails the test instead.
 */
final class BoundedChildJvm {

  private BoundedChildJvm() {}

  /**
   * Runs {@code command} and returns its output and errors.
   *
   * @param command the command line
   * @param timeout how long the child may run
   * @return the child's output and errors
   * @throws AssertionError when the child does not finish within {@code timeout}
   */
  static String run(final List<String> command, final Duration timeout) throws Exception {
    final Path log = Files.createTempFile("chaos-child-jvm-", ".log");
    try {
      final Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      try {
        final boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
          process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertThat(finished)
            .as("child JVM must finish within %s, output:\n%s", timeout, output)
            .isTrue();
        return output;
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
        }
      }
    } finally {
      Files.deleteIfExists(log);
    }
  }
}

package com.macstab.chaos.jvm.bootstrap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * A child JVM that another JVM drives, as an operator drives a running service: it prints {@code
 * pid=<pid>} and {@code ready}, then answers one line per command read from stdin.
 *
 * <ul>
 *   <li>{@code sleep}: times {@code Thread.sleep(1)} on the main thread and prints {@code
 *       sleepMillis=<n>};
 *   <li>{@code exit}: prints {@code bye} and ends.
 * </ul>
 */
public final class InteractiveProbeMain {

  private InteractiveProbeMain() {}

  public static void main(final String[] args) throws Exception {
    System.out.println("pid=" + ProcessHandle.current().pid());
    System.out.println("ready");
    System.out.flush();
    final BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String command;
    while ((command = in.readLine()) != null) {
      switch (command) {
        case "sleep" -> {
          final long start = System.nanoTime();
          Thread.sleep(1);
          System.out.println(
              "sleepMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
        case "exit" -> {
          System.out.println("bye");
          System.out.flush();
          return;
        }
        default -> System.out.println("unknown=" + command);
      }
      System.out.flush();
    }
  }
}

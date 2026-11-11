package com.macstab.chaos.jvm.bootstrap;

/** Probe main for an agent started with {@code instrumentation=eager}: it only reports it ran. */
public final class EagerStartProbeMain {

  private EagerStartProbeMain() {}

  public static void main(final String[] args) {
    if (!"start".equals(args[0])) {
      throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
    System.out.println("done=true");
  }
}

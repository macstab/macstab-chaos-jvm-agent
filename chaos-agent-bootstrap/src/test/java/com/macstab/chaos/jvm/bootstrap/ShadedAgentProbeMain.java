package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.Set;

/**
 * Runs in a child JVM on the shaded agent jar alone ({@link ChildJvmProbe#runShadedOnly}) and
 * prints what the shading must not change: an application class in a {@code net.bytebuddy} package
 * is not woven, and {@code -Dnet.bytebuddy.experimental} reaches the agent's relocated ByteBuddy.
 */
public final class ShadedAgentProbeMain {

  private ShadedAgentProbeMain() {}

  /**
   * Prints {@code matched}, {@code relocatedProperty} and {@code experimental}.
   *
   * @param args ignored
   * @throws Exception on a probe failure
   */
  public static void main(final String[] args) throws Exception {
    final net.bytebuddy.probe.Target target = new net.bytebuddy.probe.Target();
    try (ChaosActivationHandle handle =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("bytebuddy-named-target")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(
                        ChaosSelector.method(
                            Set.of(OperationType.METHOD_ENTER),
                            NamePattern.exact("net.bytebuddy.probe.Target"),
                            NamePattern.exact("run")))
                    .effect(ChaosEffect.delay(java.time.Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      target.run();
      System.out.println("matched=" + handle.matchedCount());
    }
    System.out.println(
        "relocatedProperty="
            + System.getProperty("com.macstab.chaos.jvm.shaded.net.bytebuddy.experimental"));
    System.out.println(
        "experimental="
            + Class.forName("com.macstab.chaos.jvm.shaded.net.bytebuddy.utility.OpenedClassReader")
                .getField("EXPERIMENTAL")
                .getBoolean(null));
  }
}

package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Runs in a child JVM in the fat-jar shape ({@link ChildJvmProbe#runInFatJarShape}): the agent jar
 * is not on the class path but in a class loader of its own, as in a Spring Boot executable jar,
 * and the agent self-attaches. {@link InAgentLoader} is defined by a loader whose parent is the
 * agent's loader, so the agent classes it names come from there.
 */
public final class FatJarProbeMain {

  private FatJarProbeMain() {}

  /**
   * Runs {@link InAgentLoader#run(String)} with {@code args[0]} as the mode.
   *
   * @param args the mode
   * @throws Exception on a probe failure
   */
  public static void main(final String[] args) throws Exception {
    final URL agentJar = Path.of(System.getProperty("chaos.bootstrap.agentJar")).toUri().toURL();
    final URL probeClasses =
        FatJarProbeMain.class.getProtectionDomain().getCodeSource().getLocation();
    try (URLClassLoader agentLoader =
            new URLClassLoader(new URL[] {agentJar}, ClassLoader.getPlatformClassLoader());
        URLClassLoader applicationLoader =
            new URLClassLoader(new URL[] {probeClasses}, agentLoader)) {
      Class.forName(InAgentLoader.class.getName(), true, applicationLoader)
          .getMethod("run", String.class)
          .invoke(null, args[0]);
    }
  }

  /** The probe body, defined by a loader that sees the agent through its own loader only. */
  public static final class InAgentLoader {

    private InAgentLoader() {}

    /**
     * Runs one mode.
     *
     * @param mode the probe mode
     */
    public static void run(final String mode) {
      System.out.println(
          "agent.ownLoader="
              + (ChaosPlatform.class.getClassLoader() != ClassLoader.getSystemClassLoader()));
      ChaosPlatform.installLocally();
      switch (mode) {
        case "direct-buffer-release" -> directBufferRelease();
        default -> throw new IllegalArgumentException(mode);
      }
    }

    /**
     * Activates and stops a 32 MiB direct buffer pressure and prints how much direct memory it
     * holds during the run and after the stop. Run under Epsilon, no collection frees a buffer, so
     * only the stressor's eager release brings the pool down.
     */
    private static void directBufferRelease() {
      final BufferPoolMXBean direct =
          ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
              .filter(pool -> "direct".equals(pool.getName()))
              .findFirst()
              .orElseThrow();
      final long before = direct.getMemoryUsed();
      final ChaosActivationHandle handle =
          ChaosPlatform.current()
              .activate(
                  ChaosScenario.builder("direct")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
                      .effect(
                          new ChaosEffect.DirectBufferPressureEffect(
                              32L * 1024 * 1024, 1024 * 1024, false))
                      .activationPolicy(ActivationPolicy.always())
                      .build());
      final long during = direct.getMemoryUsed();
      handle.close();
      final long after = direct.getMemoryUsed();
      System.out.println("directDuringMiB=" + (during - before) / (1024 * 1024));
      System.out.println("directAfterMiB=" + (after - before) / (1024 * 1024));
    }
  }
}

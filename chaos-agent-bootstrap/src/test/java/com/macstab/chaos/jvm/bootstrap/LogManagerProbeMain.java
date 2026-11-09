package com.macstab.chaos.jvm.bootstrap;

import java.lang.management.ManagementFactory;
import java.util.logging.LogManager;
import javax.management.ObjectName;

/**
 * Child-JVM probe started with {@code -javaagent:} and a startup plan: its {@code main} chooses the
 * {@code LogManager} as WildFly and Quarkus do, by setting {@code java.util.logging.manager} before
 * the first logger, and prints which {@code LogManager} the JVM got, whether the agent's MBean is
 * registered afterwards, and the agent's start-up lines once they reach the chosen manager.
 */
public final class LogManagerProbeMain {

  private static final String CHAOS_MBEAN = "com.macstab.chaos.jvm:type=ChaosDiagnostics";

  private LogManagerProbeMain() {}

  /**
   * Runs the probe.
   *
   * @param args ignored
   * @throws Exception when the MBean server cannot be read
   */
  public static void main(final String[] args) throws Exception {
    System.setProperty("java.util.logging.manager", ProbeLogManager.class.getName());
    System.out.println("logManager=" + LogManager.getLogManager().getClass().getName());
    final ObjectName name = new ObjectName(CHAOS_MBEAN);
    final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    boolean registered = false;
    while (!registered && System.nanoTime() - deadline < 0) {
      registered = ManagementFactory.getPlatformMBeanServer().isRegistered(name);
      if (!registered) {
        Thread.sleep(20);
      }
    }
    System.out.println("mbeanRegistered=" + registered);
  }

  /** The application's own {@code LogManager}, as {@code org.jboss.logmanager.LogManager} is. */
  public static final class ProbeLogManager extends LogManager {

    /** Called by {@code LogManager}'s static initialiser, through the system class loader. */
    public ProbeLogManager() {
      super();
    }
  }
}

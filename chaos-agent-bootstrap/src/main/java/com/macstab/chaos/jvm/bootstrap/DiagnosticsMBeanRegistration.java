package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.lang.management.ManagementFactory;
import javax.management.ObjectName;
import org.jspecify.annotations.Nullable;

/**
 * The registration of the diagnostics MBean: the only place the agent's start names a type of
 * {@code java.management}. Kept out of {@link ChaosAgentBootstrap}, whose method signatures the JVM
 * resolves to find {@code premain}, so the agent starts on a runtime without that module (a jlink
 * image, {@code --limit-modules}); without the module this class fails to link, which {@link
 * ChaosAgentBootstrap} catches.
 */
final class DiagnosticsMBeanRegistration {

  private DiagnosticsMBeanRegistration() {}

  /**
   * Registers the diagnostics MBean with the platform MBean server.
   *
   * @param name the MBean's object name
   * @param runtime the runtime it reports
   * @param control whether its control operations are enabled
   * @return the registered {@code ObjectName}, or {@code null} when nothing was registered
   */
  static @Nullable Object register(
      final String name, final ChaosRuntime runtime, final boolean control) {
    try {
      final ObjectName objectName = new ObjectName(name);
      ManagementFactory.getPlatformMBeanServer()
          .registerMBean(new ChaosDiagnosticsMBean(runtime, control), objectName);
      return objectName;
    } catch (final javax.management.InstanceAlreadyExistsException alreadyRegistered) {
      System.err.println(
          "[chaos-agent] MBean registration skipped: "
              + name
              + " is already registered in this JVM");
      return null;
    } catch (final Exception exception) {
      System.err.println("[chaos-agent] MBean registration skipped: " + exception.getMessage());
      return null;
    }
  }

  /**
   * Unregisters an MBean {@link #register} registered.
   *
   * @param registered the {@code ObjectName} {@link #register} returned
   * @throws Exception if the MBean server refuses
   */
  static void unregister(final Object registered) throws Exception {
    ManagementFactory.getPlatformMBeanServer().unregisterMBean((ObjectName) registered);
  }
}

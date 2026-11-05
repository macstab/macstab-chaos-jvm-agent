package com.macstab.chaos.jvm.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerBuilder;
import javax.management.MBeanServerDelegate;
import javax.management.ObjectName;

/**
 * A platform MBean server that refuses to register or unregister the chaos diagnostics MBean when
 * told to, as a locked-down or broken JMX setup would. Installed in a child JVM with {@code
 * -Djavax.management.builder.initial=}; every other MBean is served by the JDK's own server.
 */
public final class RefusingMBeanServerBuilder extends MBeanServerBuilder {

  static final String CHAOS_MBEAN = "com.macstab.chaos.jvm:type=ChaosDiagnostics";

  static volatile boolean refuseRegistration;
  static volatile boolean refuseUnregistration;

  /** Instantiated by the JDK through {@code javax.management.builder.initial}. */
  public RefusingMBeanServerBuilder() {}

  @Override
  public MBeanServer newMBeanServer(
      final String defaultDomain, final MBeanServer outer, final MBeanServerDelegate delegate) {
    final MBeanServer server = super.newMBeanServer(defaultDomain, outer, delegate);
    return (MBeanServer)
        Proxy.newProxyInstance(
            MBeanServer.class.getClassLoader(),
            new Class<?>[] {MBeanServer.class},
            (proxy, method, args) -> {
              if (method.getName().equals("registerMBean")
                  && refuseRegistration
                  && isChaos(args[1])) {
                throw new MBeanRegistrationException(
                    new IllegalStateException("registration refused"), "registration refused");
              }
              if (method.getName().equals("unregisterMBean")
                  && refuseUnregistration
                  && isChaos(args[0])) {
                throw new MBeanRegistrationException(
                    new IllegalStateException("unregistration refused"), "unregistration refused");
              }
              try {
                return method.invoke(server, args);
              } catch (final InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static boolean isChaos(final Object name) {
    return name instanceof ObjectName objectName
        && objectName.getCanonicalName().equals(CHAOS_MBEAN);
  }
}

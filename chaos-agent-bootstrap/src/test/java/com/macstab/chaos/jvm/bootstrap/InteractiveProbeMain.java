package com.macstab.chaos.jvm.bootstrap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.rmi.server.RMIServerSocketFactory;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;

/**
 * A child JVM that another JVM drives, as an operator drives a running service: it prints {@code
 * pid=<pid>} and {@code ready}, then answers one line per command read from stdin.
 *
 * <ul>
 *   <li>{@code sleep}: times {@code Thread.sleep(1)} on the main thread and prints {@code
 *       sleepMillis=<n>};
 *   <li>{@code jmx}: starts a JMX connector on the loopback address, waits until the agent's MBean
 *       is registered and prints {@code jmx=<service URL>};
 *   <li>{@code exit}: prints {@code bye} and ends.
 * </ul>
 */
public final class InteractiveProbeMain {

  private static final String MBEAN = "com.macstab.chaos.jvm:type=ChaosDiagnostics";

  /** Held so the exported connector lives as long as the JVM. */
  private static JMXConnectorServer connector;

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
        case "jmx" -> System.out.println("jmx=" + startConnector());
        case "exit" -> {
          System.out.println("bye");
          System.out.flush();
          // The exported connector's RMI threads are not daemons: stop it, or the JVM stays up.
          if (connector != null) {
            connector.stop();
          }
          return;
        }
        default -> System.out.println("unknown=" + command);
      }
      System.out.flush();
    }
  }

  /**
   * Starts an RMI JMX connector whose server socket is bound to the loopback address and returns
   * its address once the agent's MBean is registered.
   *
   * <p>Not the attach API's local connector ({@code startLocalManagementAgent}): that one listens
   * on the wildcard address, and on macOS ({@code SO_REUSEADDR}) another process may bind {@code
   * 127.0.0.1} on the same port and is then given the connections to it, which fails the handshake
   * with a {@code ConnectIOException} caused by an {@code EOFException} or a reset. A socket bound
   * to {@code 127.0.0.1} itself cannot be bound a second time. The agent registers its MBean on its
   * own thread once {@code java.util.logging} is initialised, which the platform MBean server does,
   * so the address is printed only after the registration.
   */
  private static String startConnector() throws Exception {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    // The host the exported stubs carry; read once, by the first RMI export below.
    System.setProperty("java.rmi.server.hostname", loopback.getHostAddress());
    final RMIServerSocketFactory loopbackOnly = port -> new ServerSocket(port, 0, loopback);
    final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    connector =
        JMXConnectorServerFactory.newJMXConnectorServer(
            new JMXServiceURL("rmi", loopback.getHostAddress(), 0),
            Map.of(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, loopbackOnly),
            server);
    connector.start();
    final ObjectName name = new ObjectName(MBEAN);
    while (!server.isRegistered(name)) {
      Thread.sleep(10);
    }
    return connector.getAddress().toString();
  }
}

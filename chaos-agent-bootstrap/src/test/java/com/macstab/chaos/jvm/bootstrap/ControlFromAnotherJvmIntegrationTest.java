package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.tools.attach.VirtualMachine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A running agent JVM controlled from another JVM, as an operator does it: through the MBean over a
 * JMX connector, and by attaching the agent with a plan. The test JVM is the operator; the child
 * runs {@link InteractiveProbeMain}, whose {@code Thread.sleep(1)} on its main thread is the real
 * call each plan delays.
 */
@DisplayName("Control from another JVM")
class ControlFromAnotherJvmIntegrationTest {

  private static final String MBEAN = "com.macstab.chaos.jvm:type=ChaosDiagnostics";
  private static final String SCENARIO_ID = "slow-main-sleep";
  private static final long DELAYED_MILLIS = 280;
  private static final long UNDELAYED_MILLIS = 150;

  /** A JVM-scoped 300 ms delay on {@code Thread.sleep} of the thread named {@code main} only. */
  private static final String PLAN =
      """
      {"metadata": {"name": "remote"}, "scenarios": [{"id": "%s", "scope": "JVM",
        "selector": {"type": "thread", "operations": ["THREAD_SLEEP"],
                     "threadNamePattern": {"mode": "EXACT", "value": "main"}},
        "effect": {"type": "delay", "minDelay": "PT0.3S", "maxDelay": "PT0.3S"}}]}
      """
          .formatted(SCENARIO_ID);

  @TempDir Path tempDir;

  @Nested
  @DisplayName("over a JMX connector")
  class OverJmx {

    @Test
    @DisplayName("jmxControl=true: activatePlan, stopScenario and stopAll change a real call")
    void controlOperations() throws Exception {
      try (Child child =
              Child.start(
                  "-javaagent:"
                      + System.getProperty("chaos.bootstrap.agentJar")
                      + "=jmxControl=true");
          JMXConnector connector = connect(child.pid())) {
        final MBeanServerConnection server = connector.getMBeanServerConnection();
        final ObjectName name = new ObjectName(MBEAN);
        assertThat(child.sleepMillis()).as("no plan yet").isLessThan(UNDELAYED_MILLIS);

        assertThat(server.getAttribute(name, "ControlEnabled")).isEqualTo(Boolean.TRUE);
        assertThat(activate(server, name))
            .as("the ids of the scenarios activated")
            .isEqualTo(SCENARIO_ID);
        assertThat(child.sleepMillis())
            .as("delayed by the plan")
            .isGreaterThanOrEqualTo(DELAYED_MILLIS);
        assertThat((String[]) server.invoke(name, "listScenarios", null, null))
            .anySatisfy(line -> assertThat(line).contains(SCENARIO_ID).contains("ACTIVE"));

        assertThat(
                server.invoke(
                    name,
                    "stopScenario",
                    new Object[] {SCENARIO_ID},
                    new String[] {String.class.getName()}))
            .isEqualTo(Boolean.TRUE);
        assertThat(child.sleepMillis()).as("after stopScenario").isLessThan(UNDELAYED_MILLIS);

        activate(server, name);
        assertThat(child.sleepMillis()).isGreaterThanOrEqualTo(DELAYED_MILLIS);
        assertThat(server.invoke(name, "stopAll", null, null)).isEqualTo(1);
        assertThat(child.sleepMillis()).as("after stopAll").isLessThan(UNDELAYED_MILLIS);
      }
    }

    @Test
    @DisplayName("without jmxControl the control operations are refused and change nothing")
    void refusedWithoutControl() throws Exception {
      try (Child child =
              Child.start("-javaagent:" + System.getProperty("chaos.bootstrap.agentJar"));
          JMXConnector connector = connect(child.pid())) {
        final MBeanServerConnection server = connector.getMBeanServerConnection();
        final ObjectName name = new ObjectName(MBEAN);

        assertThat(server.getAttribute(name, "ControlEnabled")).isEqualTo(Boolean.FALSE);
        assertThatThrownBy(() -> activate(server, name)).hasStackTraceContaining("jmxControl=true");
        assertThat(child.sleepMillis()).isLessThan(UNDELAYED_MILLIS);
      }
    }
  }

  @Nested
  @DisplayName("by attaching the agent")
  class ByAttach {

    @Test
    @DisplayName("agentmain with a plan argument delays a call of an already loaded class")
    void attachWithPlan() throws Exception {
      final Path plan = Files.writeString(tempDir.resolve("attach-plan.json"), PLAN);
      final Path reports = Files.createDirectories(tempDir.resolve("reports"));
      try (Child child =
          Child.start(
              "-D" + ChaosAgentBootstrap.ALLOW_DYNAMIC_ATTACH_PROPERTY + "=true",
              "-XX:+EnableDynamicAgentLoading")) {
        // Thread is loaded and Thread.sleep has run long before the agent arrives.
        assertThat(child.sleepMillis()).isLessThan(UNDELAYED_MILLIS);

        final VirtualMachine vm = VirtualMachine.attach(String.valueOf(child.pid()));
        try {
          vm.loadAgent(
              System.getProperty("chaos.bootstrap.agentJar"),
              "configFile=" + plan + ";reports=on;reportsDir=" + reports);
        } finally {
          vm.detach();
        }

        assertThat(child.sleepMillis())
            .as("the attached agent's plan delays Thread.sleep")
            .isGreaterThanOrEqualTo(DELAYED_MILLIS);
        final Map<String, String> status =
            ChildJvmProbe.parse(Files.readAllLines(reports.resolve(".chaos-jvm.status")));
        assertThat(status).containsEntry("state", "ok").containsEntry("scenarios", "1");
        assertThat(status.get("source")).startsWith("file:");
      }
    }
  }

  private static String activate(final MBeanServerConnection server, final ObjectName name)
      throws Exception {
    return (String)
        server.invoke(
            name, "activatePlan", new Object[] {PLAN}, new String[] {String.class.getName()});
  }

  /** Connects to the child through the attach API's local JMX connector. */
  private static JMXConnector connect(final long pid) throws Exception {
    final VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
    final String address;
    try {
      address = vm.startLocalManagementAgent();
    } finally {
      vm.detach();
    }
    return JMXConnectorFactory.connect(new JMXServiceURL(address));
  }

  /** A running {@link InteractiveProbeMain}, driven line by line. */
  private static final class Child implements AutoCloseable {

    private static final long TIMEOUT_SECONDS = 30;

    private final Process process;
    private final Writer commands;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> output = new ArrayList<>();
    private final long pid;

    private Child(final Process process) throws Exception {
      this.process = process;
      this.commands = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
      final Thread reader =
          new Thread(
              () -> {
                try (BufferedReader in =
                    new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = in.readLine()) != null) {
                    lines.add(line);
                  }
                } catch (final IOException closed) {
                  // the child ended
                }
              },
              "interactive-child-reader");
      reader.setDaemon(true);
      reader.start();
      this.pid = Long.parseLong(await("pid="));
      await("ready");
    }

    static Child start(final String... jvmArgs) throws Exception {
      final List<String> command = new ArrayList<>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.addAll(ChildJvmProbe.coverageAgent());
      command.addAll(Arrays.asList(jvmArgs));
      command.add("-cp");
      command.add(System.getProperty("java.class.path"));
      command.add(InteractiveProbeMain.class.getName());
      final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      try {
        return new Child(process);
      } catch (final Exception | Error failure) {
        // The child never reported ready: no test closes it, so it is destroyed here.
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        throw failure;
      }
    }

    long pid() {
      return pid;
    }

    /** How long {@code Thread.sleep(1)} took on the child's main thread. */
    long sleepMillis() throws Exception {
      commands.write("sleep\n");
      commands.flush();
      return Long.parseLong(await("sleepMillis="));
    }

    /**
     * The rest of the first line that starts with {@code prefix}; the child's output on failure.
     */
    private String await(final String prefix) throws InterruptedException {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      while (true) {
        final String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (line == null) {
          throw new AssertionError(
              "the child printed no '" + prefix + "' within " + TIMEOUT_SECONDS + " s: " + output);
        }
        output.add(line);
        if (line.startsWith(prefix)) {
          return line.substring(prefix.length());
        }
      }
    }

    @Override
    public void close() throws Exception {
      try {
        commands.write("exit\n");
        commands.flush();
      } catch (final IOException gone) {
        // the child ended already
      }
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

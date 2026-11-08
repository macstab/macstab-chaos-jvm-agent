package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import net.bytebuddy.agent.ByteBuddyAgent;

/**
 * Child-JVM probe for the agent's start-up paths: dynamic attach with and without the opt-in,
 * concurrent and re-entrant initialization, failures while installing and while rolling back, an
 * MBean name that is taken or refused, a JVM without JFR and one without the attach mechanism.
 * Prints {@code key=value} lines for {@link StartupPathIntegrationTest}.
 */
public final class StartupPathProbeMain {

  private static final long WAIT_SECONDS = 20L;
  private static final String CHAOS_MBEAN = RefusingMBeanServerBuilder.CHAOS_MBEAN;

  private StartupPathProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "agentmain" -> agentmain();
      case "concurrent" -> concurrent(args[1]);
      case "local-install-lock" -> localInstallLock();
      case "startup-retry" -> startupRetry();
      case "mbean-taken" -> mbeanTaken();
      case "mbean-refused" -> mbeanRefused();
      case "rollback-failures" -> rollbackFailures();
      case "no-jfr" -> noJfr();
      case "no-attach" -> noAttach();
      case "bridge-retry" -> bridgeRetry();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /** Attaches the agent jar to this JVM; agentmain installs only when dynamic attach is allowed. */
  private static void agentmain() {
    System.out.println("installedBefore=" + describe(ChaosAgentBootstrap::current));
    attachAgentJar();
    System.out.println("installedAfterAttach=" + installed());
    if (!installed()) {
      System.setProperty(ChaosAgentBootstrap.ALLOW_DYNAMIC_ATTACH_PROPERTY, "true");
      attachAgentJar();
      System.out.println("installedAfterOptIn=" + installed());
    }
  }

  private static void attachAgentJar() {
    ByteBuddyAgent.attach(
        new File(System.getProperty("chaos.bootstrap.agentJar")),
        String.valueOf(ProcessHandle.current().pid()));
  }

  private static boolean installed() {
    try {
      ChaosAgentBootstrap.current();
      return true;
    } catch (final IllegalStateException notInstalled) {
      return false;
    }
  }

  /**
   * One thread installs through an instrumentation that holds it inside the installation (where it
   * also tries to re-enter); meanwhile another thread asks for the runtime and a third initializes
   * and waits for the outcome, which is success or the given failure: {@code runtime}, {@code
   * error} or {@code checked}.
   */
  private static void concurrent(final String outcome) throws Exception {
    final Instrumentation real = ByteBuddyAgent.install();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<String> reentry = new AtomicReference<>("not attempted");
    final ScriptedInstrumentation gated =
        new ScriptedInstrumentation(real)
            .onFirstInstallStep(
                () -> {
                  reentry.set(describe(() -> ChaosAgentBootstrap.initialize("", real, Map.of())));
                  entered.countDown();
                  await(release);
                });
    switch (outcome) {
      case "ok" -> {}
      case "runtime" -> gated.failBridgeInjection(new IllegalStateException("bridge refused"));
      case "error" -> gated.failBridgeInjection(new LinkageError("bridge refused"));
      case "checked" ->
          gated.failBridgeInjection(new java.util.concurrent.TimeoutException("bridge refused"));
      default -> throw new IllegalArgumentException(outcome);
    }

    final CompletableFuture<String> first =
        CompletableFuture.supplyAsync(
            () -> describe(() -> ChaosAgentBootstrap.initialize("", gated, Map.of())),
            runnable -> Thread.ofPlatform().start(runnable));
    await(entered);
    System.out.println("currentWhileInstalling=" + describe(ChaosAgentBootstrap::current));
    final AtomicReference<String> waited = new AtomicReference<>();
    final Thread waiter =
        Thread.ofPlatform()
            .start(
                () ->
                    waited.set(describe(() -> ChaosAgentBootstrap.initialize("", real, Map.of()))));
    awaitThread(
        waiter,
        thread ->
            thread.getState() == Thread.State.WAITING
                && java.util.Arrays.stream(thread.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("awaitInitialization")));
    release.countDown();
    waiter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

    System.out.println("reentry=" + reentry.get());
    System.out.println("first=" + first.get(WAIT_SECONDS, TimeUnit.SECONDS));
    System.out.println("waiter=" + waited.get());
    if (outcome.equals("ok")) {
      final ChaosControlPlane installed = ChaosAgentBootstrap.current();
      System.out.println(
          "localInstallReturnsInstalled=" + (ChaosPlatform.installLocally() == installed));
    }
  }

  /**
   * A local install that finds no runtime, then waits for the install lock while another thread
   * installs: it returns the runtime installed meanwhile instead of attaching again.
   */
  private static void localInstallLock() throws Exception {
    final java.lang.reflect.Field lockField =
        ChaosAgentBootstrap.class.getDeclaredField("INSTALL_LOCK");
    lockField.setAccessible(true);
    final Object lock = lockField.get(null);
    final CompletableFuture<ChaosControlPlane> local = new CompletableFuture<>();
    final ChaosRuntime direct;
    synchronized (lock) {
      final Thread localInstaller =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      local.complete(ChaosAgentBootstrap.installForLocalTests());
                    } catch (final RuntimeException failure) {
                      local.completeExceptionally(failure);
                    }
                  });
      awaitThread(localInstaller, thread -> thread.getState() == Thread.State.BLOCKED);
      direct = ChaosAgentBootstrap.initialize("", ByteBuddyAgent.install(), Map.of());
    }
    System.out.println(
        "localInstallReturnsInstalled=" + (local.get(WAIT_SECONDS, TimeUnit.SECONDS) == direct));
  }

  /**
   * A watched plan file whose plan the runtime refuses fails the start-up and stops the poller; a
   * retry with a valid watched plan that asks for a dump installs the poller and prints one dump.
   */
  private static void startupRetry() throws Exception {
    final Instrumentation instrumentation = ByteBuddyAgent.install();
    final Path reports = Path.of(System.getProperty("chaos.test.reports"));
    final String watch = ";configWatchInterval=3600000;reports=on;reportsDir=" + reports;
    System.out.println(
        "firstAttempt="
            + describe(
                () ->
                    ChaosAgentBootstrap.initialize(
                        "configFile=" + System.getProperty("chaos.test.badPlan") + watch,
                        instrumentation,
                        Map.of())));
    System.out.println("pollerAfterFailure=" + (ChaosAgentBootstrap.POLLER.get() != null));
    printStatus("failureStatus", reports);
    ChaosAgentBootstrap.initialize(
        "configFile=" + System.getProperty("chaos.test.goodPlan") + watch,
        instrumentation,
        Map.of());
    System.out.println("pollerAfterRetry=" + (ChaosAgentBootstrap.POLLER.get() != null));
    System.out.println(
        "watchedScenario="
            + ChaosAgentBootstrap.current()
                .diagnostics()
                .scenario("watched-sleep")
                .map(report -> report.state().name())
                .orElse("absent"));
    printStatus("retryStatus", reports);
  }

  private static void printStatus(final String key, final Path reports) throws Exception {
    final Path status = reports.resolve(ChaosReports.STATUS_FILE);
    final String content =
        Files.exists(status) ? Files.readString(status).replace('\n', '|') : "absent";
    System.out.println(key + "=" + content);
  }

  /**
   * The MBean name is already taken: the agent installs without its MBean and leaves the other one
   * alone. The plan and the agent args both ask for a dump; it is printed once. At shutdown the
   * reports are closed from a shutdown hook, when no hook can be removed any more.
   */
  private static void mbeanTaken() throws Exception {
    final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    final ObjectName name = new ObjectName(CHAOS_MBEAN);
    server.registerMBean(new StandardMBean(() -> {}, Runnable.class), name);
    ChaosAgentBootstrap.initialize(
        "configFile="
            + System.getProperty("chaos.test.goodPlan")
            + ";debugDumpOnStart=true;reports=on;reportsDir="
            + System.getProperty("chaos.test.reports"),
        ByteBuddyAgent.install(),
        Map.of());
    System.out.println(
        "takenBeanKept=" + server.getMBeanInfo(name).getClassName().contains("StartupPathProbe"));
    System.out.println("installed=" + installed());
    final ChaosReports reports = ChaosReports.current().orElseThrow();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  reports.close();
                  System.out.println("closedDuringShutdown=true");
                  // The coverage agent's own hook may have dumped already: record this close too.
                  dumpCoverage();
                }));
  }

  private static void dumpCoverage() {
    try {
      final Object agent =
          Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
      Class.forName("org.jacoco.agent.rt.IAgent")
          .getMethod("dump", boolean.class)
          .invoke(agent, false);
    } catch (final ReflectiveOperationException | RuntimeException noCoverageAgent) {
      // not running with the coverage agent
    }
  }

  /**
   * The MBean server refuses the agent's MBean: the agent installs without it. The agent args ask
   * for a dump of a plan that does not; it is printed.
   */
  private static void mbeanRefused() throws Exception {
    RefusingMBeanServerBuilder.refuseRegistration = true;
    ChaosAgentBootstrap.initialize(
        "debugDumpOnStart=true;configFile=" + System.getProperty("chaos.test.quietPlan"),
        ByteBuddyAgent.install(),
        Map.of());
    System.out.println("installed=" + installed());
    System.out.println(
        "chaosBeanRegistered="
            + ManagementFactory.getPlatformMBeanServer().isRegistered(new ObjectName(CHAOS_MBEAN)));
  }

  /**
   * A refused plan fails the start-up after the instrumentation and the MBean are installed;
   * undoing both fails too. The start-up failure carries those failures as suppressed exceptions.
   */
  private static void rollbackFailures() throws Exception {
    RefusingMBeanServerBuilder.refuseUnregistration = true;
    final ScriptedInstrumentation instrumentation =
        new ScriptedInstrumentation(ByteBuddyAgent.install()).failTransformerRemoval();
    try {
      ChaosAgentBootstrap.initialize(
          "instrumentation=eager;configFile="
              + System.getProperty("chaos.test.badPlan")
              + ";reports=on;reportsDir="
              + System.getProperty("chaos.test.reports"),
          instrumentation,
          Map.of());
      System.out.println("failure=none");
    } catch (final RuntimeException failure) {
      System.out.println("failure=" + failure.getClass().getSimpleName());
      for (final Throwable suppressed : failure.getSuppressed()) {
        System.out.println(
            "suppressed." + suppressed.getClass().getSimpleName() + "=" + suppressed.getMessage());
      }
    }
    System.out.println("installed=" + installed());
    printStatus("failureStatus", Path.of(System.getProperty("chaos.test.reports")));
  }

  /** Started with -javaagent on a JVM without the jdk.jfr module: the agent works without JFR. */
  private static void noJfr() throws Exception {
    System.out.println("jfrModule=" + ModuleLayer.boot().findModule("jdk.jfr").isPresent());
    ChaosAgentBootstrap.current()
        .activate(
            ChaosScenario.builder("no-jfr-sleep")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(
                    ChaosSelector.thread(
                        Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY))
                .effect(ChaosEffect.delay(Duration.ofMillis(200)))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final long start = System.nanoTime();
    Thread.sleep(1L);
    System.out.println("sleepMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  /** Started with the attach mechanism disabled: a local install fails with a clear message. */
  private static void noAttach() {
    System.out.println("localInstall=" + describe(ChaosAgentBootstrap::installForLocalTests));
  }

  /**
   * Initializes once with a bridge injection that fails with an unchecked exception, then again
   * with the real instrumentation: the retry must inject the bridge and install.
   */
  private static void bridgeRetry() {
    final Instrumentation real = ByteBuddyAgent.install();
    final ScriptedInstrumentation refusing =
        new ScriptedInstrumentation(real)
            .failBridgeInjection(new IllegalStateException("bridge refused"));
    System.out.println(
        "firstAttempt=" + describe(() -> ChaosAgentBootstrap.initialize("", refusing, Map.of())));
    System.out.println(
        "retry=" + describe(() -> ChaosAgentBootstrap.initialize("", real, Map.of())));
  }

  /** {@code runtime} for a returned runtime, else the exception's class, message and cause. */
  private static String describe(final Callable<?> action) {
    try {
      final Object result = action.call();
      return result instanceof ChaosControlPlane ? "runtime" : String.valueOf(result);
    } catch (final Throwable failure) {
      final Throwable cause = failure.getCause();
      return failure.getClass().getSimpleName()
          + ":"
          + failure.getMessage()
          + (cause == null
              ? ""
              : " <- " + cause.getClass().getSimpleName() + ":" + cause.getMessage());
    }
  }

  private static void await(final CountDownLatch latch) {
    try {
      if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for the probe's other thread");
      }
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static void awaitThread(final Thread thread, final Predicate<Thread> condition)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (!condition.test(thread)) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("timed out waiting for " + thread.getName());
      }
      Thread.sleep(5L);
    }
  }
}

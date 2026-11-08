package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller;
import com.macstab.chaos.jvm.startup.AgentSettings;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Probe main that drives {@link JdkInstrumentationInstaller} directly in a self-attached JVM,
 * without the bootstrap's own installation, and prints {@code key=value} lines.
 */
public final class InstallerProbeMain {

  /** Held so the configured logger is not collected before the installer looks it up. */
  private static final Logger AGENT_LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  private InstallerProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "lifecycle" -> lifecycle();
      case "session-hosts-failure" -> sessionHostsFailure();
      case "foreign-copy" -> foreignCopy();
      case "bridge-revision-claimed" -> bridgeOfAnotherRevision(true);
      case "bridge-revision-unclaimed" -> bridgeOfAnotherRevision(false);
      case "dispatcher-before-install" -> dispatcherBeforeInstall();
      case "reports-during-shutdown" -> reportsDuringShutdown();
      case "retransform-failure" -> retransformFailure();
      case "reset-failure" -> resetFailure();
      case "escapees" -> escapees(false);
      case "escapee-batch-failure" -> escapees(true);
      case "premain-retransform-failure" -> premainRetransformFailure(args[1]);
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * An uninstall before any install; an install whose bridge cannot be written (the temp directory
   * does not exist) and its retry; a second install of the same copy for another runtime; an
   * uninstall that removes the woven hooks, and a second uninstall.
   */
  private static void lifecycle() throws Exception {
    final Instrumentation instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
    final List<String> fineMessages = recordFineMessages();

    System.out.println(
        "uninstallBeforeInstall=" + JdkInstrumentationInstaller.uninstall(instrumentation));

    final String tmpDir = System.getProperty("java.io.tmpdir");
    final java.nio.file.Path missing =
        java.nio.file.Path.of(tmpDir, "missing-" + System.nanoTime());
    System.setProperty("java.io.tmpdir", missing.toString());
    try {
      JdkInstrumentationInstaller.install(
          instrumentation, new ChaosRuntime(), false, List.of(), List.of());
      System.out.println("injectFailure=none");
    } catch (final IllegalStateException failed) {
      System.out.println("injectFailure=" + failed.getMessage());
    } finally {
      System.setProperty("java.io.tmpdir", tmpDir);
    }

    final ChaosRuntime first = new ChaosRuntime();
    JdkInstrumentationInstaller.install(instrumentation, first, false, List.of(), List.of());
    final ChaosRuntime second = new ChaosRuntime();
    JdkInstrumentationInstaller.install(instrumentation, second, false, List.of(), List.of());
    first.activate(sleepDelay("first-sleep"));
    second.activate(sleepDelay("second-sleep"));
    System.out.println("installedSleepMillis=" + sleepMillis());
    System.out.println("appliedFirst=" + applied(first, "first-sleep"));
    System.out.println("appliedSecond=" + applied(second, "second-sleep"));

    System.out.println("uninstalled=" + JdkInstrumentationInstaller.uninstall(instrumentation));
    System.out.println("uninstalledSleepMillis=" + sleepMillis());
    System.out.println(
        "uninstalledAgain=" + JdkInstrumentationInstaller.uninstall(instrumentation));

    System.out.println(
        "sweepSkippedLogged="
            + fineMessages.stream().anyMatch(m -> m.startsWith("bridge jar sweep skipped")));
    System.out.println(
        "wovenLogged=" + fineMessages.stream().anyMatch(m -> m.startsWith("chaos hooks woven")));
  }

  /**
   * An install whose session-header hosts fail after the delegate is installed (a {@code null}
   * glob): the failed install must leave no delegate behind.
   */
  private static void sessionHostsFailure() throws Exception {
    final Instrumentation instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
    try {
      JdkInstrumentationInstaller.install(
          instrumentation, new ChaosRuntime(), false, List.of(), Arrays.asList((String) null));
      System.out.println("installFailed=false");
    } catch (final RuntimeException failed) {
      System.out.println("installFailed=true");
    }
    System.out.println("delegateLeft=" + (bootDelegate() != null));
  }

  /**
   * A second copy of the agent, in its own class loader, installing through a counting
   * instrumentation while the first copy is installed: the refused install must not touch the JVM
   * (no bridge jar appended, no module opened).
   */
  private static void foreignCopy() throws Exception {
    final Instrumentation real = net.bytebuddy.agent.ByteBuddyAgent.install();
    JdkInstrumentationInstaller.install(real, new ChaosRuntime(), false, List.of(), List.of());
    final ScriptedInstrumentation counting = new ScriptedInstrumentation(real);
    try (URLClassLoader isolated =
        new URLClassLoader(classpath(), ClassLoader.getPlatformClassLoader())) {
      final Class<?> runtimeClass = Class.forName(ChaosRuntime.class.getName(), true, isolated);
      final Class<?> installer =
          Class.forName(JdkInstrumentationInstaller.class.getName(), true, isolated);
      try {
        installer
            .getMethod(
                "install",
                Instrumentation.class,
                runtimeClass,
                boolean.class,
                List.class,
                List.class)
            .invoke(
                null,
                counting,
                runtimeClass.getConstructor().newInstance(),
                false,
                List.of(),
                List.of());
        System.out.println("foreignRefused=false");
      } catch (final java.lang.reflect.InvocationTargetException refused) {
        System.out.println(
            "foreignRefused=" + (refused.getCause() instanceof IllegalStateException));
      }
    }
    System.out.println("foreignAppends=" + counting.appends());
    System.out.println("foreignModuleRedefinitions=" + counting.moduleRedefinitions());
  }

  /**
   * A bridge already on the bootstrap class path that this installer did not inject: claimed by
   * another revision, or not claimed at all (a bridge placed there by hand, or by an agent version
   * that predates the claim). The install must be refused before it touches the JVM.
   */
  private static void bridgeOfAnotherRevision(final boolean preClaim) throws Exception {
    final Instrumentation real = net.bytebuddy.agent.ByteBuddyAgent.install();
    appendBridgeByHand(real);
    if (preClaim) {
      try {
        Class.forName(
                "com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher", true, null)
            .getMethod("claimRevision", String.class)
            .invoke(null, "0123456789abcdef");
        System.out.println("preClaimed=true");
      } catch (final NoSuchMethodException noClaim) {
        System.out.println("preClaimed=false");
      }
    }
    final ScriptedInstrumentation counting = new ScriptedInstrumentation(real);
    try {
      JdkInstrumentationInstaller.install(
          counting, new ChaosRuntime(), false, List.of(), List.of());
      System.out.println("installRefusal=none");
    } catch (final IllegalStateException refused) {
      System.out.println("installRefusal=" + refused.getMessage());
    }
    System.out.println("delegateLeft=" + (bootDelegate() != null));
    System.out.println("appends=" + counting.appends());
    System.out.println("moduleRedefinitions=" + counting.moduleRedefinitions());
  }

  /**
   * Appends the bridge classes to the bootstrap class path the way {@code -Xbootclasspath/a} does.
   */
  private static void appendBridgeByHand(final Instrumentation instrumentation) throws Exception {
    final java.lang.reflect.Field listField =
        Class.forName("com.macstab.chaos.jvm.instrumentation.BridgeInjector")
            .getDeclaredField("BRIDGE_CLASSES");
    listField.setAccessible(true);
    final java.nio.file.Path jar = java.nio.file.Files.createTempFile("hand-placed-bridge", ".jar");
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
      for (final Object entry : (List<?>) listField.get(null)) {
        final String path = (String) entry;
        try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream(path)) {
          out.putNextEntry(new java.util.jar.JarEntry(path));
          java.util.Objects.requireNonNull(in, path).transferTo(out);
          out.closeEntry();
        }
      }
    }
    instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jar.toFile()));
  }

  /**
   * A runtime that started a scenario before the agent was installed (so it looked up the
   * dispatcher while there was none), then installed: a clock skew one hour back must not move the
   * start of a later scenario's {@code activeFor} window, which would end it at once.
   */
  private static void dispatcherBeforeInstall() throws Exception {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(sleepDelay("before-install")).close();
    JdkInstrumentationInstaller.install(
        net.bytebuddy.agent.ByteBuddyAgent.install(), runtime, false, List.of(), List.of());
    runtime.activate(
        ChaosScenario.builder("skew-back")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.CLOCK_READ)))
            .effect(ChaosEffect.skewClock(Duration.ofHours(-1L), ChaosEffect.ClockSkewMode.FIXED))
            .activationPolicy(ActivationPolicy.always())
            .build());
    runtime.activate(
        ChaosScenario.builder("windowed-sleep")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.thread(
                    Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY))
            .effect(ChaosEffect.delay(Duration.ofMillis(300L)))
            .activationPolicy(ActivationPolicy.builder().activeFor(Duration.ofMinutes(5L)).build())
            .build());
    System.out.println("windowedSleepMillis=" + sleepMillis());
  }

  /**
   * Reports created while the JVM shuts down: the creation fails, and must leave no writer thread
   * and no listener behind.
   */
  private static void reportsDuringShutdown() throws Exception {
    final ChaosRuntime runtime = new ChaosRuntime();
    final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("reports-shutdown");
    final int listenersBefore = listenerCount(runtime);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    ChaosReports.createIfEnabled(
                        AgentSettings.of("reports=on;reportsDir=" + dir, java.util.Map.of()),
                        runtime);
                    System.out.println("reportsFailure=none");
                  } catch (final IllegalStateException failed) {
                    System.out.println("reportsFailure=" + failed.getMessage());
                  }
                  System.out.println(
                      "writerAlive="
                          + Thread.getAllStackTraces().keySet().stream()
                              .anyMatch(
                                  thread ->
                                      thread.getName().equals("macstab-chaos-hits-writer")
                                          && thread.isAlive()));
                  try {
                    System.out.println(
                        "listenersAdded=" + (listenerCount(runtime) - listenersBefore));
                  } catch (final ReflectiveOperationException unreadable) {
                    System.out.println("listenersAdded=" + unreadable);
                  }
                  System.out.flush();
                }));
  }

  /**
   * The JVM refuses to retransform {@code java.lang.Thread}: the THREAD_SLEEP activation fails, the
   * group is not recorded as woven, the failure is in the hook failures, and the next activation
   * weaves the group again.
   */
  private static void retransformFailure() throws Exception {
    final ScriptedInstrumentation scripted =
        new ScriptedInstrumentation(net.bytebuddy.agent.ByteBuddyAgent.install())
            .failRetransformOf("java.lang.Thread");
    final ChaosRuntime runtime = new ChaosRuntime();
    JdkInstrumentationInstaller.install(scripted, runtime, false, List.of(), List.of());
    try {
      runtime.activate(sleepDelay("refused-sleep"));
      System.out.println("refusedActivation=activated");
    } catch (final RuntimeException refused) {
      System.out.println("refusedActivation=" + refused.getClass().getSimpleName());
    }
    final java.util.Map<String, String> details = runtime.diagnostics().snapshot().runtimeDetails();
    System.out.println("wovenAfterRefusal=" + details.get("wovenHookGroups"));
    System.out.println(
        "hookFailureNamesGroup="
            + String.valueOf(details.get("hookFailures"))
                .contains("THREAD_SLEEP: java.lang.IllegalStateException"));
    scripted.failRetransformOf();
    runtime.activate(sleepDelay("retried-sleep"));
    System.out.println(
        "threadRetransforms="
            + scripted.retransformCalls().stream()
                .filter(call -> call.contains("java.lang.Thread"))
                .count());
    System.out.println("retriedSleepMillis=" + sleepMillis());
  }

  /**
   * The JVM refuses to retransform a woven class back: the uninstall reports it and still clears.
   */
  private static void resetFailure() throws Exception {
    final ScriptedInstrumentation scripted =
        new ScriptedInstrumentation(net.bytebuddy.agent.ByteBuddyAgent.install());
    final ChaosRuntime runtime = new ChaosRuntime();
    JdkInstrumentationInstaller.install(scripted, runtime, false, List.of(), List.of());
    runtime.activate(sleepDelay("woven-sleep"));
    scripted.failRetransformOf("java.lang.Thread");
    try {
      JdkInstrumentationInstaller.uninstall(scripted);
      System.out.println("uninstallFailure=none");
    } catch (final IllegalStateException failed) {
      System.out.println("uninstallFailure=" + failed.getMessage());
    }
    System.out.println("uninstalledAgain=" + JdkInstrumentationInstaller.uninstall(scripted));
  }

  /** The classes woven after an installation because they escaped it; see the installer. */
  private static final List<String> ESCAPEES =
      List.of(
          "java.io.ObjectOutputStream",
          "java.io.ObjectInputStream",
          "java.lang.VirtualThread",
          "java.net.Socket",
          "java.net.ServerSocket",
          "sun.nio.ch.NioSocketImpl");

  /**
   * Weaves a group: the classes that escape an installation are retransformed in one call, and
   * (with {@code failBatch}) a refused batch is retried one class at a time.
   */
  private static void escapees(final boolean failBatch) throws Exception {
    final Instrumentation real = net.bytebuddy.agent.ByteBuddyAgent.install();
    final ScriptedInstrumentation scripted = new ScriptedInstrumentation(real);
    final ChaosRuntime runtime = new ChaosRuntime();
    JdkInstrumentationInstaller.install(scripted, runtime, false, List.of(), List.of());
    if (failBatch) {
      scripted.failRetransformOf("java.io.ObjectOutputStream");
    }
    runtime.activate(sleepDelay("escapee-sleep"));
    final List<List<String>> escapeeCalls =
        scripted.retransformCalls().stream()
            .filter(call -> call.stream().anyMatch(ESCAPEES::contains))
            .toList();
    System.out.println(
        "batchCalls="
            + escapeeCalls.stream()
                .filter(
                    call ->
                        call.contains("java.io.ObjectOutputStream")
                            && call.contains("java.net.Socket"))
                .count());
    System.out.println("escapeeCalls=" + escapeeCalls.size());
    System.out.println(
        "socketAlone="
            + escapeeCalls.stream().anyMatch(call -> call.equals(List.of("java.net.Socket"))));
  }

  /**
   * {@code premain} with a startup plan whose weave the JVM refuses: it throws, as for every other
   * startup-plan failure, after writing the error to the status file.
   */
  private static void premainRetransformFailure(final String agentArgs) {
    final ScriptedInstrumentation scripted =
        new ScriptedInstrumentation(net.bytebuddy.agent.ByteBuddyAgent.install())
            .failRetransformOf("java.lang.Thread");
    ChaosAgentBootstrap.premain(agentArgs, scripted);
    System.out.println("premainReturned=true");
  }

  /** The listeners registered on {@code runtime}'s event bus. */
  private static int listenerCount(final ChaosRuntime runtime) throws ReflectiveOperationException {
    final java.lang.reflect.Field planeField = ChaosRuntime.class.getDeclaredField("controlPlane");
    planeField.setAccessible(true);
    final Object plane = planeField.get(runtime);
    final java.lang.reflect.Field busField = plane.getClass().getDeclaredField("observabilityBus");
    busField.setAccessible(true);
    final Object bus = busField.get(plane);
    final java.lang.reflect.Field listenersField = bus.getClass().getDeclaredField("listeners");
    listenersField.setAccessible(true);
    return ((List<?>) listenersField.get(bus)).size();
  }

  /** The delegate of the bootstrap copy of the dispatcher, the one woven advice calls. */
  private static Object bootDelegate() throws ReflectiveOperationException {
    return Class.forName(
            "com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher", true, null)
        .getMethod("installedDelegate")
        .invoke(null);
  }

  private static URL[] classpath() {
    return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
        .map(
            entry -> {
              try {
                return java.nio.file.Path.of(entry).toUri().toURL();
              } catch (final java.net.MalformedURLException exception) {
                throw new IllegalStateException(exception);
              }
            })
        .toArray(URL[]::new);
  }

  private static ChaosScenario sleepDelay(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(
            ChaosSelector.thread(Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY))
        .effect(ChaosEffect.delay(Duration.ofMillis(300L)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static long applied(final ChaosRuntime runtime, final String id) {
    return runtime.diagnostics().snapshot().scenarios().stream()
        .filter(report -> report.id().equals(id))
        .mapToLong(report -> report.appliedCount())
        .sum();
  }

  private static long sleepMillis() throws InterruptedException {
    final long start = System.nanoTime();
    Thread.sleep(1L);
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /** Records the agent's FINE log messages. */
  private static List<String> recordFineMessages() {
    final List<String> messages = new CopyOnWriteArrayList<>();
    AGENT_LOGGER.setLevel(Level.FINE);
    AGENT_LOGGER.addHandler(
        new Handler() {
          @Override
          public void publish(final LogRecord logRecord) {
            messages.add(String.valueOf(logRecord.getMessage()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        });
    return messages;
  }
}

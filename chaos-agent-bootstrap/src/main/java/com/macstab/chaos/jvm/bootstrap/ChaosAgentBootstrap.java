package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.jfr.JfrIntegration;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller;
import com.macstab.chaos.jvm.startup.AgentArgs;
import com.macstab.chaos.jvm.startup.AgentArgsParser;
import com.macstab.chaos.jvm.startup.AgentSettings;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import com.macstab.chaos.jvm.startup.LogText;
import com.macstab.chaos.jvm.startup.StartupConfigLoader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.ObjectName;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jspecify.annotations.Nullable;

/**
 * JVM agent entry point and runtime bootstrap for the chaos testing agent.
 *
 * <h2>Agent attachment modes</h2>
 *
 * <ul>
 *   <li><b>Static attachment</b> ({@link #premain}): the agent is specified via {@code
 *       -javaagent:chaos-agent.jar=<args>} on the JVM command line. All Phase 1 and Phase 2
 *       interception points are installed before application classes are loaded, giving the agent
 *       full access to JDK bootstrap and platform classes. This is the mode used in production and
 *       CI test runs.
 *   <li><b>Dynamic attachment</b> ({@link #agentmain}): the agent is attached to a running JVM via
 *       the Attach API (e.g. {@code VirtualMachine.attach()}). Phase 2 interception points (Socket,
 *       NIO, HTTP, ...) are rewritten via JVMTI class retransformation on already-loaded JDK
 *       classes, so the same chaos surface is available as in static attachment.
 *   <li><b>Test helper</b> ({@link #installForLocalTests}): installs the agent into the current JVM
 *       using {@link net.bytebuddy.agent.ByteBuddyAgent#install()}, intended for use in
 *       unit/integration tests that run without {@code -javaagent:}.
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>{@link #initialize} claims the static {@link #INITIALIZATION} slot with a compare-and-set
 * before installing anything. If two threads race during startup (e.g., concurrent dynamic-attach
 * calls), only one installs; the other waits for its outcome and returns the same runtime (or the
 * same failure).
 *
 * <h2>Post-initialization steps</h2>
 *
 * <p>After the runtime is created and instrumentation is installed, the bootstrap:
 *
 * <ul>
 *   <li>Registers a {@link ChaosDiagnosticsMXBean} with the platform MBean server under the object
 *       name {@code com.macstab.chaos.jvm:type=ChaosDiagnostics}.
 *   <li>Registers JFR integration if {@code jdk.jfr.FlightRecorder} is accessible on the running
 *       JVM (stripped JREs without the {@code jdk.jfr} module are silently skipped).
 *   <li>Loads and activates a startup {@link com.macstab.chaos.jvm.api.ChaosPlan} from the
 *       configured source (inline JSON, Base64, or file path), if one is present.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>All public methods are thread-safe. The initialization CAS in {@link #initialize} ensures that
 * at most one runtime is installed at a time, and {@link #current()} only ever returns a runtime
 * whose installation completed.
 */
public final class ChaosAgentBootstrap {
  /**
   * Initialization of the single {@link ChaosRuntime} for this JVM. {@code null} until an
   * initialization starts; the future completes with the runtime once instrumentation, the bridge,
   * the MBean, JFR and the startup plan are installed. Claiming the slot with a future (instead of
   * publishing the runtime itself) keeps a runtime that is still being installed — or that is
   * rolled back after a failure — invisible to {@link #current()} and to concurrent initializers,
   * which wait for the outcome instead.
   */
  private static final AtomicReference<@Nullable CompletableFuture<ChaosRuntime>> INITIALIZATION =
      new AtomicReference<>();

  /** Thread running the current initialization; detects re-entrant initialization. */
  private static volatile @Nullable Thread initializingThread;

  /** The agent args of the installed runtime, to name what a later attach asks for in vain. */
  private static volatile @Nullable AgentArgs installedArgs;

  private static final String MBEAN_NAME = "com.macstab.chaos.jvm:type=ChaosDiagnostics";

  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger(ChaosAgentBootstrap.class.getName());

  /**
   * ByteBuddy's switch for class files newer than it supports, as a user sets it. Built at run
   * time: the agent jar relocates ByteBuddy, and with it every {@code "net.bytebuddy..."} literal.
   */
  private static final String BYTE_BUDDY_EXPERIMENTAL =
      String.join(".", "net", "bytebuddy", "experimental");

  /**
   * The same switch as the agent's own ByteBuddy reads it: the literal is relocated with ByteBuddy
   * (in the agent jar {@code com.macstab.chaos.jvm.shaded.net.bytebuddy.experimental}; unshaded it
   * equals {@link #BYTE_BUDDY_EXPERIMENTAL}).
   */
  private static final String AGENT_BYTE_BUDDY_EXPERIMENTAL = "net.bytebuddy.experimental";

  /**
   * The active config-file poller, or {@code null} when watch mode is disabled or the plan was
   * loaded from an inline / base64 source (which has no backing file to watch).
   */
  static final AtomicReference<@Nullable StartupConfigPoller> POLLER = new AtomicReference<>();

  /** Serialises concurrent {@link #installForLocalTests} calls; see that method for rationale. */
  private static final Object INSTALL_LOCK = new Object();

  /**
   * System property / env-var name that opts in to installing the agent via {@link #agentmain}
   * (dynamic attach). Without this opt-in, {@code agentmain} is a no-op: an attacker who has
   * already achieved code execution sufficient to call the Attach API on a live JVM (HotSpotAttach
   * / JcmdAttach) would otherwise be able to turn the chaos agent into an instrumentation RCE
   * primitive — the agent has permission to rewrite bootstrap JDK classes, which is strictly more
   * than arbitrary process-level code exec.
   */
  static final String ALLOW_DYNAMIC_ATTACH_PROPERTY = "macstab.chaos.allow-dynamic-attach";

  /**
   * Env-var equivalent of {@link #ALLOW_DYNAMIC_ATTACH_PROPERTY}. Both forms are checked so
   * operators running under container orchestrators that only expose env vars can opt in without
   * having to juggle {@code -D} flags on a command line they do not control.
   */
  static final String ALLOW_DYNAMIC_ATTACH_ENV = "MACSTAB_CHAOS_ALLOW_DYNAMIC_ATTACH";

  /** Agent arg selecting the weaving mode ({@code eager} or the default on-demand weaving). */
  static final String INSTRUMENTATION_ARG = "instrumentation";

  /** System property equivalent of {@link #INSTRUMENTATION_ARG}. */
  static final String INSTRUMENTATION_PROPERTY = "macstab.chaos.instrumentation";

  /** Environment variable equivalent of {@link #INSTRUMENTATION_ARG}. */
  static final String INSTRUMENTATION_ENV = "MACSTAB_CHAOS_INSTRUMENTATION";

  /**
   * Agent arg naming the package prefixes whose {@code System.currentTimeMillis()} and {@code
   * nanoTime()} call sites are rewritten for clock skew, comma-separated.
   */
  static final String CLOCK_CALL_SITES_ARG = "clockCallSites";

  /** System property equivalent of {@link #CLOCK_CALL_SITES_ARG}. */
  static final String CLOCK_CALL_SITES_PROPERTY = "macstab.chaos.clockCallSites";

  /** Environment variable equivalent of {@link #CLOCK_CALL_SITES_ARG}. */
  static final String CLOCK_CALL_SITES_ENV = "MACSTAB_CHAOS_CLOCK_CALL_SITES";

  /**
   * Agent arg adding host globs that receive the {@code X-Chaos-Session} header besides internal
   * hosts, comma-separated; {@code *} for every host.
   */
  static final String SESSION_HEADER_HOSTS_ARG = "sessionHeaderHosts";

  /** System property equivalent of {@link #SESSION_HEADER_HOSTS_ARG}. */
  static final String SESSION_HEADER_HOSTS_PROPERTY = "macstab.chaos.sessionHeaderHosts";

  /** Environment variable equivalent of {@link #SESSION_HEADER_HOSTS_ARG}. */
  static final String SESSION_HEADER_HOSTS_ENV = "MACSTAB_CHAOS_SESSION_HEADER_HOSTS";

  private ChaosAgentBootstrap() {}

  /**
   * Static agent entry point, called by the JVM when the agent is specified via {@code
   * -javaagent:chaos-agent.jar=<agentArgs>}.
   *
   * <p>This method is invoked by the JVM infrastructure before the application's {@code main}
   * method, ensuring that all instrumentation is in place before any application class is loaded.
   *
   * @param agentArgs the raw argument string from the {@code -javaagent} command-line option, e.g.
   *     {@code configFile=/etc/chaos/plan.json;debugDumpOnStart=true}. May be {@code null} or blank
   *     if no arguments were provided.
   * @param instrumentation the {@link Instrumentation} instance provided by the JVM; used to
   *     install class-file transformers for all interception points
   */
  public static void premain(
      final @Nullable String agentArgs, final Instrumentation instrumentation) {
    copyByteBuddyExperimental();
    initialize(agentArgs, instrumentation, System.getenv());
  }

  /**
   * Hands {@code -Dnet.bytebuddy.experimental} to the agent's relocated ByteBuddy, which reads its
   * own relocated name. Runs first in every entry point: ByteBuddy reads the switch in static
   * initialisers, so the copy must come before any ByteBuddy class initialises. A switch the user
   * set under the relocated name wins.
   */
  private static void copyByteBuddyExperimental() {
    copyProperty(BYTE_BUDDY_EXPERIMENTAL, AGENT_BYTE_BUDDY_EXPERIMENTAL);
  }

  /**
   * Sets system property {@code to} to the value of {@code from} when {@code from} is set and
   * {@code to} is not. Package-private as a test seam: unshaded, both ByteBuddy names are equal.
   */
  static void copyProperty(final String from, final String to) {
    try {
      final String value = System.getProperty(from);
      if (value != null && System.getProperty(to) == null) {
        System.setProperty(to, value);
      }
    } catch (final SecurityException denied) {
      LOGGER.fine(() -> "chaos-agent: cannot hand " + from + " on: " + denied);
    }
  }

  /**
   * Dynamic agent entry point, called by the JVM when the agent is attached to an already-running
   * JVM via the Attach API.
   *
   * <p>Because application and JDK classes may already be loaded when this method is invoked,
   * retransformation of previously loaded classes depends on whether the JVM supports it and
   * whether the class-file transformer requests it. Interception points that require transforming
   * bootstrap-loaded JDK classes may be ineffective if those classes were loaded before attachment.
   *
   * <p><b>Security gate</b>: dynamic attach requires an explicit opt-in via the {@link
   * #ALLOW_DYNAMIC_ATTACH_PROPERTY} system property or {@link #ALLOW_DYNAMIC_ATTACH_ENV}
   * environment variable. Without the opt-in, {@code agentmain} is a no-op and returns silently.
   * Rationale: an attacker who can call the Attach API on a running JVM already has process-level
   * code execution, but installing a chaos agent gives them the ability to rewrite bootstrap JDK
   * classes, which is a strictly stronger primitive. Requiring an explicit opt-in prevents a chaos
   * jar that happens to be on the classpath from being trivially weaponised by a lower-privilege
   * attacker via {@code VirtualMachine.attach}.
   *
   * @param agentArgs the raw argument string passed to the attach call; may be {@code null}
   * @param instrumentation the {@link Instrumentation} instance provided by the JVM
   */
  public static void agentmain(
      final @Nullable String agentArgs, final Instrumentation instrumentation) {
    copyByteBuddyExperimental();
    if (!dynamicAttachAllowed(System.getProperties(), System.getenv())) {
      System.err.println(
          "[chaos-agent] ignoring dynamic attach: set -D"
              + ALLOW_DYNAMIC_ATTACH_PROPERTY
              + "=true or "
              + ALLOW_DYNAMIC_ATTACH_ENV
              + "=true to opt in");
      return;
    }
    initialize(agentArgs, instrumentation, System.getenv());
  }

  static boolean dynamicAttachAllowed(
      final java.util.Properties systemProperties, final Map<String, String> environment) {
    return AgentArgs.parseBoolean(
            ALLOW_DYNAMIC_ATTACH_PROPERTY,
            systemProperties.getProperty(ALLOW_DYNAMIC_ATTACH_PROPERTY),
            false)
        || AgentArgs.parseBoolean(
            ALLOW_DYNAMIC_ATTACH_ENV, environment.get(ALLOW_DYNAMIC_ATTACH_ENV), false);
  }

  /**
   * Installs the chaos agent into the current JVM using {@link ByteBuddyAgent#install()}, without
   * requiring the {@code -javaagent:} command-line flag.
   *
   * <p>Used by the test extensions and by the framework integrations when chaos is enabled without
   * {@code -javaagent:} on the command line. It performs a self-attach via the JVM Attach API,
   * which may require the JVM to be started with {@code -Djdk.attach.allowAttachSelf=true} on some
   * JDK versions.
   *
   * <p>If the agent is already installed, the existing {@link ChaosControlPlane} is returned
   * immediately without reinitializing.
   *
   * @return the installed {@link ChaosControlPlane}; never null
   * @throws IllegalStateException if dynamic self-attach is unavailable on this JVM (e.g., the JDK
   *     tools JAR is missing or the security manager prevents attachment). In this case, run tests
   *     with {@code -javaagent:chaos-agent.jar} instead.
   */
  public static ChaosControlPlane installForLocalTests() {
    copyByteBuddyExperimental();
    final ChaosRuntime existing = installedRuntime();
    if (existing != null) {
      return existing;
    }
    // Double-checked under INSTALL_LOCK so two test threads racing their first call do not
    // both perform the expensive self-attach. ByteBuddyAgent.install() caches internally, but
    // initialize() below has non-trivial side effects (bridge JAR materialisation, MBean
    // registration attempts, JFR probe). The inner initialize() claims INITIALIZATION with a
    // CAS so correctness doesn't depend on this lock, but serialising here avoids doing the
    // attach work twice when the outcome is identical.
    synchronized (INSTALL_LOCK) {
      final ChaosRuntime existingAfterLock = installedRuntime();
      if (existingAfterLock != null) {
        return existingAfterLock;
      }
      try {
        final Instrumentation instrumentation = ByteBuddyAgent.install();
        // The process environment, as for -javaagent: the framework starters self-attach in
        // production too, and the documented MACSTAB_CHAOS_* switches must apply there.
        return initialize("", instrumentation, System.getenv());
      } catch (final RuntimeException runtimeException) {
        throw runtimeException;
      } catch (final Exception exception) {
        throw new IllegalStateException(
            "failed to self-attach JVM agent; run tests with -javaagent if dynamic attach is unavailable",
            exception);
      }
    }
  }

  /**
   * Returns the {@link ChaosControlPlane} for the currently installed chaos runtime.
   *
   * <p>The control plane is available after any of {@link #premain}, {@link #agentmain}, or {@link
   * #installForLocalTests} has completed successfully.
   *
   * @return the initialized {@link ChaosControlPlane}; never null
   * @throws IllegalStateException if the chaos agent has not yet been installed in this JVM
   */
  public static ChaosControlPlane current() {
    final ChaosRuntime runtime = installedRuntime();
    if (runtime == null) {
      throw new IllegalStateException("chaos agent is not installed");
    }
    return runtime;
  }

  /** Returns the fully installed runtime, or {@code null} when none is (yet) installed. */
  static @Nullable ChaosRuntime installedRuntime() {
    final CompletableFuture<ChaosRuntime> initialization = INITIALIZATION.get();
    if (initialization == null
        || !initialization.isDone()
        || initialization.isCompletedExceptionally()) {
      return null;
    }
    return initialization.join();
  }

  /**
   * Core initialization routine; creates, wires, and publishes a {@link ChaosRuntime} for this JVM.
   *
   * <p>This method is idempotent: if an initialization already completed, its runtime is returned
   * without further work; if one is in progress on another thread, this call waits for it. If the
   * initialization fails, everything it installed is rolled back and the slot is freed, so a later
   * call can retry.
   *
   * <p>On successful initialization, this method:
   *
   * <ol>
   *   <li>Creates a new {@link ChaosRuntime}.
   *   <li>Installs all JDK instrumentation via {@link
   *       com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller}.
   *   <li>Registers a {@link ChaosDiagnosticsMBean} with the platform MBean server.
   *   <li>Installs JFR integration (if {@code jdk.jfr} is available).
   *   <li>Loads a startup chaos plan from the agent args / environment (if configured) and
   *       activates it. If {@code debugDumpOnStart} is set, prints a diagnostics dump to {@code
   *       System.err}.
   *   <li>Completes the {@link #INITIALIZATION} future, which publishes the runtime.
   * </ol>
   *
   * @param agentArgs raw argument string from the {@code -javaagent} option or attach call; parsed
   *     by {@link com.macstab.chaos.jvm.startup.AgentArgsParser}. May be null.
   * @param instrumentation the JVM {@link Instrumentation} handle used to install transformers
   * @param environment a snapshot of the process environment variables (typically {@link
   *     System#getenv()}); used to resolve config sources such as {@code MACSTAB_CHAOS_CONFIG_FILE}
   * @return the initialized (or already-initialized) {@link ChaosRuntime}; never null
   */
  static ChaosRuntime initialize(
      final @Nullable String agentArgs,
      final Instrumentation instrumentation,
      final Map<String, String> environment) {
    final CompletableFuture<ChaosRuntime> claim = new CompletableFuture<>();
    final CompletableFuture<ChaosRuntime> existing = INITIALIZATION.compareAndExchange(null, claim);
    if (existing != null) {
      final ChaosRuntime installed = awaitInitialization(existing);
      reportIgnoredArgs(agentArgs);
      return installed;
    }
    // This thread won the slot. Installation has JVM-wide side effects (transformers, the bridge
    // delegate, the MBean), so exactly one initializer performs it; concurrent callers wait for
    // the outcome above instead of seeing a half-installed runtime.
    initializingThread = Thread.currentThread();
    try {
      // Resolved once: every setting reads these, and a parse warning is printed once.
      final AgentSettings settings = AgentSettings.of(agentArgs, environment);
      final ChaosRuntime runtime = installRuntime(settings, instrumentation);
      installedArgs = settings.args();
      claim.complete(runtime);
      return runtime;
    } catch (final Throwable installFailure) {
      // Free the slot before failing the future so waiters that retry find it empty.
      INITIALIZATION.compareAndSet(claim, null);
      claim.completeExceptionally(installFailure);
      if (installFailure instanceof RuntimeException re) {
        throw re;
      }
      if (installFailure instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("agent initialization failed", installFailure);
    } finally {
      initializingThread = null;
    }
  }

  /**
   * A runtime is already installed, so the agent args of this attach are not applied: names, in one
   * line, those that differ from the installed ones (keys only; a value can hold a plan).
   */
  private static void reportIgnoredArgs(final @Nullable String agentArgs) {
    // Set before the runtime is published, so it is present for every caller that gets here.
    final AgentArgs active = Objects.requireNonNull(installedArgs, "installedArgs");
    String ignored;
    try {
      ignored =
          String.join(
              ", ",
              AgentArgsParser.parse(agentArgs).values().entrySet().stream()
                  .filter(entry -> !entry.getValue().equals(active.get(entry.getKey())))
                  .map(Map.Entry::getKey)
                  .sorted()
                  .toList());
    } catch (final IllegalArgumentException malformed) {
      // Not the parser's message: it quotes the token, whose value can hold a plan.
      ignored = "all (malformed)";
    }
    if (!ignored.isEmpty()) {
      System.err.println(
          "[chaos-agent] the agent is already installed; these agent args are ignored: "
              + LogText.sanitize(ignored));
    }
  }

  private static ChaosRuntime awaitInitialization(final CompletableFuture<ChaosRuntime> pending) {
    if (initializingThread == Thread.currentThread()) {
      throw new IllegalStateException(
          "chaos agent initialization re-entered on the initializing thread");
    }
    try {
      return pending.join();
    } catch (final CompletionException failure) {
      final Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("agent initialization failed", cause);
    }
  }

  /**
   * Installs a new runtime: instrumentation, MBean, JFR and the startup plan. On failure every step
   * that already ran is undone — the transformers and the bridge delegate are removed, the MBean is
   * unregistered and the runtime (with its JFR listener) is closed — so a retry starts from a clean
   * JVM. Clearing only the published reference is not enough: a retry would install a second set of
   * transformers (advice runs twice) and the MBean would keep reporting the dead runtime.
   */
  private static ChaosRuntime installRuntime(
      final AgentSettings settings, final Instrumentation instrumentation) {
    final ChaosRuntime runtime = new ChaosRuntime();
    boolean instrumentationInstalled = false;
    ObjectName registeredMBean = null;
    Optional<ChaosReports> reports = Optional.empty();
    try {
      JdkInstrumentationInstaller.install(
          instrumentation,
          runtime,
          eagerInstrumentation(settings),
          clockCallSites(settings),
          listSetting(
              settings,
              SESSION_HEADER_HOSTS_ARG,
              SESSION_HEADER_HOSTS_PROPERTY,
              SESSION_HEADER_HOSTS_ENV));
      instrumentationInstalled = true;
      registeredMBean = registerMBean(runtime, jmxControl(settings));
      installJfrIntegration(runtime);
      reports = ChaosReports.createIfEnabled(settings, runtime);
      final Optional<ChaosReports> report = reports;
      final Optional<StartupConfigLoader.LoadedPlan> loadedPlan;
      try {
        loadedPlan = StartupConfigLoader.load(settings);
      } catch (final ConfigLoadException loadFailure) {
        report.ifPresent(
            r ->
                r.statusError(
                    loadFailure.getSource(),
                    loadFailure.line(),
                    loadFailure.column(),
                    loadFailure.reason()));
        // Without its cause: the JVM prints the cause chain of a failed premain, and Jackson's
        // message in it quotes an excerpt of the plan, which can hold credentials.
        throw new ConfigLoadException(
            String.valueOf(loadFailure.getMessage()),
            loadFailure.getSource(),
            null,
            loadFailure.line(),
            loadFailure.column(),
            loadFailure.reason());
      }
      loadedPlan.ifPresent(
          loaded -> {
            try {
              activateLoadedPlan(loaded, settings, runtime);
            } catch (final RuntimeException activationFailure) {
              report.ifPresent(
                  r ->
                      r.statusError(
                          loaded.source(), 0, 0, String.valueOf(activationFailure.getMessage())));
              throw activationFailure;
            }
            report.ifPresent(r -> r.statusOk(loaded.plan().scenarios().size(), loaded.source()));
          });
      return runtime;
    } catch (final Throwable installFailure) {
      reports.ifPresent(ChaosReports::close);
      rollBack(instrumentation, runtime, instrumentationInstalled, registeredMBean, installFailure);
      throw installFailure;
    }
  }

  /**
   * Whether every hook group is woven at start instead of on demand: agent arg {@code
   * instrumentation=eager}, system property {@value #INSTRUMENTATION_PROPERTY}{@code =eager} or
   * environment variable {@value #INSTRUMENTATION_ENV}{@code =eager}. On demand is the default.
   */
  static boolean eagerInstrumentation(final AgentSettings settings) {
    return "eager"
        .equalsIgnoreCase(
            settings.valueOrEmpty(
                INSTRUMENTATION_ARG, INSTRUMENTATION_PROPERTY, INSTRUMENTATION_ENV));
  }

  /**
   * The package prefixes for clock call-site weaving: agent arg {@code clockCallSites}, system
   * property {@value #CLOCK_CALL_SITES_PROPERTY} or environment variable {@value
   * #CLOCK_CALL_SITES_ENV}, comma-separated. Off (empty) by default.
   */
  static List<String> clockCallSites(final AgentSettings settings) {
    return listSetting(
        settings, CLOCK_CALL_SITES_ARG, CLOCK_CALL_SITES_PROPERTY, CLOCK_CALL_SITES_ENV);
  }

  /** A comma-separated list setting: agent argument, then system property, then environment. */
  static List<String> listSetting(
      final AgentSettings settings, final String arg, final String property, final String env) {
    final String value = settings.valueOrEmpty(arg, property, env);
    return java.util.Arrays.stream(value.split(","))
        .map(String::strip)
        .filter(entry -> !entry.isEmpty())
        .toList();
  }

  private static void rollBack(
      final Instrumentation instrumentation,
      final ChaosRuntime runtime,
      final boolean instrumentationInstalled,
      final @Nullable ObjectName registeredMBean,
      final Throwable installFailure) {
    if (instrumentationInstalled) {
      try {
        JdkInstrumentationInstaller.uninstall(instrumentation);
      } catch (final RuntimeException rollbackFailure) {
        installFailure.addSuppressed(rollbackFailure);
      }
    }
    if (registeredMBean != null) {
      try {
        ManagementFactory.getPlatformMBeanServer().unregisterMBean(registeredMBean);
      } catch (final Exception rollbackFailure) {
        installFailure.addSuppressed(rollbackFailure);
      }
    }
    try {
      runtime.close();
    } catch (final RuntimeException rollbackFailure) {
      installFailure.addSuppressed(rollbackFailure);
    }
  }

  private static void activateLoadedPlan(
      final StartupConfigLoader.LoadedPlan loaded,
      final AgentSettings settings,
      final ChaosRuntime runtime) {
    if (loaded.filePath() != null) {
      final Optional<StartupConfigPoller> poller =
          StartupConfigPoller.createIfEnabled(settings, loaded.filePath(), runtime);
      if (poller.isPresent()) {
        startPollerWithInitialPlan(poller.get(), loaded);
      } else {
        runtime.activate(loaded.plan());
      }
    } else {
      runtime.activate(loaded.plan());
    }
    // runtime.activate(plan) prints the plan's own debugDumpOnStart; the poller path does not.
    final boolean planDumps = loaded.plan().observability().debugDumpOnStart();
    final boolean pollerActivated = POLLER.get() != null;
    if ((loaded.debugDumpOnStart() && !planDumps) || (planDumps && pollerActivated)) {
      System.err.println(runtime.diagnostics().debugDump());
    }
  }

  private static void startPollerWithInitialPlan(
      final StartupConfigPoller poller, final StartupConfigLoader.LoadedPlan loaded) {
    POLLER.set(poller);
    try {
      poller.startWithInitialPlan(loaded.plan(), loaded.planFile());
    } catch (final RuntimeException startFailure) {
      // startWithInitialPlan can throw after POLLER has been populated — e.g. an
      // activation failure on the initial plan, a scheduler rejection, or an I/O
      // hiccup reading the plan file. Without this, the poller scheduler daemon
      // thread is leaked for the JVM lifetime and POLLER holds a stray reference
      // even though startup failed. The rollback in installRuntime() uninstalls the agent —
      // but the poller thread would continue running, calling runtime.activate() on a
      // runtime that is no longer published. Close it before propagating.
      try {
        poller.close();
      } catch (final RuntimeException closeFailure) {
        startFailure.addSuppressed(closeFailure);
      }
      POLLER.compareAndSet(poller, null);
      throw startFailure;
    }
  }

  private static void installJfrIntegration(final ChaosRuntime runtime) {
    try {
      JfrIntegration.installIfAvailable(runtime);
    } catch (final Throwable throwable) {
      System.err.println("[chaos-agent] JFR integration skipped: " + throwable.getMessage());
    }
  }

  /**
   * Whether the MBean's control operations are enabled: agent argument {@code jmxControl=true},
   * system property {@code macstab.chaos.jmx.control=true} or environment variable {@code
   * MACSTAB_CHAOS_JMX_CONTROL=true}. Off by default.
   */
  static boolean jmxControl(final AgentSettings settings) {
    return AgentArgs.parseBoolean(
        JMX_CONTROL_ARG,
        settings.valueOrEmpty(JMX_CONTROL_ARG, JMX_CONTROL_PROPERTY, JMX_CONTROL_ENV),
        false);
  }

  private static final String JMX_CONTROL_ARG = "jmxControl";
  private static final String JMX_CONTROL_PROPERTY = "macstab.chaos.jmx.control";
  private static final String JMX_CONTROL_ENV = "MACSTAB_CHAOS_JMX_CONTROL";

  /**
   * Registers the diagnostics MBean.
   *
   * @return the registered name, or {@code null} when nothing was registered
   */
  private static @Nullable ObjectName registerMBean(
      final ChaosRuntime runtime, final boolean control) {
    try {
      final ObjectName objectName = new ObjectName(MBEAN_NAME);
      ManagementFactory.getPlatformMBeanServer()
          .registerMBean(new ChaosDiagnosticsMBean(runtime, control), objectName);
      return objectName;
    } catch (final javax.management.InstanceAlreadyExistsException alreadyRegistered) {
      System.err.println(
          "[chaos-agent] MBean registration skipped: "
              + MBEAN_NAME
              + " is already registered in this JVM");
      return null;
    } catch (final Exception exception) {
      System.err.println("[chaos-agent] MBean registration skipped: " + exception.getMessage());
      return null;
    }
  }
}

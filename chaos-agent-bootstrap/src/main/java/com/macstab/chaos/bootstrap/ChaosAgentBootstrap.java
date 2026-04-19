package com.macstab.chaos.bootstrap;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.bootstrap.jfr.JfrIntegration;
import com.macstab.chaos.core.ChaosRuntime;
import com.macstab.chaos.instrumentation.JdkInstrumentationInstaller;
import com.macstab.chaos.startup.StartupConfigLoader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.ObjectName;
import net.bytebuddy.agent.ByteBuddyAgent;

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
 * <p>{@link #initialize} is protected by a compare-and-set on the static {@link #RUNTIME}
 * reference. If two threads race during startup (e.g., concurrent dynamic-attach calls), only one
 * {@link ChaosRuntime} is ever created; the loser discards its newly constructed instance and
 * returns the winner's runtime.
 *
 * <h2>Post-initialization steps</h2>
 *
 * <p>After the runtime is created and instrumentation is installed, the bootstrap:
 *
 * <ul>
 *   <li>Registers a {@link ChaosDiagnosticsMXBean} with the platform MBean server under the object
 *       name {@code com.macstab.chaos:type=ChaosDiagnostics}.
 *   <li>Registers JFR integration if {@code jdk.jfr.FlightRecorder} is accessible on the running
 *       JVM (stripped JREs without the {@code jdk.jfr} module are silently skipped).
 *   <li>Loads and activates a startup {@link com.macstab.chaos.api.ChaosPlan} from the configured
 *       source (inline JSON, Base64, or file path), if one is present.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>All public methods are thread-safe. {@link #RUNTIME} is an {@link AtomicReference}; the
 * initialization CAS in {@link #initialize} ensures that exactly one runtime is ever installed per
 * JVM lifetime.
 */
public final class ChaosAgentBootstrap {
  /**
   * The single {@link ChaosRuntime} for this JVM. {@code null} until {@link #initialize} completes
   * successfully. Written exactly once via compare-and-set; subsequent reads are non-blocking.
   */
  private static final AtomicReference<ChaosRuntime> RUNTIME = new AtomicReference<>();

  /**
   * The active config-file poller, or {@code null} when watch mode is disabled or the plan was
   * loaded from an inline / base64 source (which has no backing file to watch).
   */
  static final AtomicReference<StartupConfigPoller> POLLER = new AtomicReference<>();

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
  public static void premain(final String agentArgs, final Instrumentation instrumentation) {
    initialize(agentArgs, instrumentation, System.getenv(), true);
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
   * @param agentArgs the raw argument string passed to the attach call; may be {@code null}
   * @param instrumentation the {@link Instrumentation} instance provided by the JVM
   */
  public static void agentmain(final String agentArgs, final Instrumentation instrumentation) {
    initialize(agentArgs, instrumentation, System.getenv(), true);
  }

  /**
   * Installs the chaos agent into the current JVM using {@link ByteBuddyAgent#install()}, without
   * requiring the {@code -javaagent:} command-line flag.
   *
   * <p>This method is intended exclusively for use in unit and integration test environments where
   * adding {@code -javaagent:} to every test runner invocation is inconvenient. It performs a
   * self-attach via the JVM Attach API, which may require the JVM to be started with {@code
   * -Djdk.attach.allowAttachSelf=true} on some JDK versions.
   *
   * <p>If the agent is already installed (i.e. {@link #RUNTIME} is non-null), the existing {@link
   * ChaosControlPlane} is returned immediately without reinitializing.
   *
   * @return the installed {@link ChaosControlPlane}; never null
   * @throws IllegalStateException if dynamic self-attach is unavailable on this JVM (e.g., the JDK
   *     tools JAR is missing or the security manager prevents attachment). In this case, run tests
   *     with {@code -javaagent:chaos-agent.jar} instead.
   */
  public static ChaosControlPlane installForLocalTests() {
    final ChaosRuntime existing = RUNTIME.get();
    if (existing != null) {
      return existing;
    }
    try {
      final Instrumentation instrumentation = ByteBuddyAgent.install();
      return initialize("", instrumentation, Map.of(), true);
    } catch (RuntimeException runtimeException) {
      throw runtimeException;
    } catch (Exception exception) {
      throw new IllegalStateException(
          "failed to self-attach JVM agent; run tests with -javaagent if dynamic attach is unavailable",
          exception);
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
    final ChaosRuntime runtime = RUNTIME.get();
    if (runtime == null) {
      throw new IllegalStateException("chaos agent is not installed");
    }
    return runtime;
  }

  /**
   * Core initialization routine; creates, wires, and publishes a {@link ChaosRuntime} for this JVM.
   *
   * <p>This method is idempotent: if {@link #RUNTIME} is already set when this method is entered,
   * the existing runtime is returned immediately without performing any further work. If two
   * threads race to initialize, only one will succeed in the compare-and-set; the other will
   * discard its freshly constructed runtime and return the winner's instance.
   *
   * <p>On successful initialization, this method:
   *
   * <ol>
   *   <li>Creates a new {@link ChaosRuntime}.
   *   <li>Installs all JDK instrumentation via {@link
   *       com.macstab.chaos.instrumentation.JdkInstrumentationInstaller}.
   *   <li>Registers a {@link ChaosDiagnosticsMBean} with the platform MBean server.
   *   <li>Installs JFR integration (if {@code jdk.jfr} is available).
   *   <li>Loads a startup chaos plan from the agent args / environment (if configured) and
   *       activates it. If {@code debugDumpOnStart} is set, prints a diagnostics dump to {@code
   *       System.err}.
   *   <li>Publishes the runtime via {@link AtomicReference#compareAndSet}.
   * </ol>
   *
   * @param agentArgs raw argument string from the {@code -javaagent} option or attach call; parsed
   *     by {@link com.macstab.chaos.startup.AgentArgsParser}. May be null.
   * @param instrumentation the JVM {@link Instrumentation} handle used to install transformers
   * @param environment a snapshot of the process environment variables (typically {@link
   *     System#getenv()}); used to resolve config sources such as {@code MACSTAB_CHAOS_CONFIG_FILE}
   * @param premainMode {@code true} when called from {@link #premain} (static attach, before
   *     application main); {@code false} when called from {@link #agentmain} or {@link
   *     #installForLocalTests} (dynamic attach or test self-attach). The instrumentation installer
   *     uses this flag to decide which retransformation strategies are safe.
   * @return the initialized (or already-initialized) {@link ChaosRuntime}; never null
   */
  static ChaosRuntime initialize(
      final String agentArgs,
      final Instrumentation instrumentation,
      final Map<String, String> environment,
      final boolean premainMode) {
    final ChaosRuntime existing = RUNTIME.get();
    if (existing != null) {
      return existing;
    }
    final ChaosRuntime runtime = new ChaosRuntime();
    JdkInstrumentationInstaller.install(instrumentation, runtime, premainMode);
    registerMBean(runtime);
    installJfrIntegration(runtime);
    final Optional<StartupConfigLoader.LoadedPlan> loadedPlan =
        StartupConfigLoader.load(agentArgs, environment);
    loadedPlan.ifPresent(
        loaded -> {
          if (loaded.filePath() != null) {
            final Optional<StartupConfigPoller> poller =
                StartupConfigPoller.createIfEnabled(
                    agentArgs, environment, loaded.filePath(), runtime);
            if (poller.isPresent()) {
              POLLER.set(poller.get());
              poller.get().startWithInitialPlan(loaded.plan().scenarios());
            } else {
              runtime.activate(loaded.plan());
            }
          } else {
            runtime.activate(loaded.plan());
          }
          if (loaded.debugDumpOnStart()) {
            System.err.println(runtime.diagnostics().debugDump());
          }
        });
    if (!RUNTIME.compareAndSet(null, runtime)) {
      return RUNTIME.get();
    }
    return runtime;
  }

  private static void installJfrIntegration(final ChaosRuntime runtime) {
    try {
      JfrIntegration.installIfAvailable(runtime);
    } catch (Throwable throwable) {
      System.err.println("[chaos-agent] JFR integration skipped: " + throwable.getMessage());
    }
  }

  private static void registerMBean(final ChaosRuntime runtime) {
    try {
      final ObjectName objectName = new ObjectName("com.macstab.chaos:type=ChaosDiagnostics");
      if (!ManagementFactory.getPlatformMBeanServer().isRegistered(objectName)) {
        ManagementFactory.getPlatformMBeanServer()
            .registerMBean(new ChaosDiagnosticsMBean(runtime.diagnostics()), objectName);
      }
    } catch (Exception exception) {
      System.err.println("[chaos-agent] MBean registration skipped: " + exception.getMessage());
    }
  }
}

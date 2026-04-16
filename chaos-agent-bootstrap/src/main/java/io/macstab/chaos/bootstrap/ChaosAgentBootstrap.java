package io.macstab.chaos.bootstrap;

import io.macstab.chaos.api.ChaosControlPlane;
import io.macstab.chaos.bootstrap.jfr.JfrIntegration;
import io.macstab.chaos.core.ChaosRuntime;
import io.macstab.chaos.instrumentation.JdkInstrumentationInstaller;
import io.macstab.chaos.startup.StartupConfigLoader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.ObjectName;
import net.bytebuddy.agent.ByteBuddyAgent;

public final class ChaosAgentBootstrap {
  private static final AtomicReference<ChaosRuntime> RUNTIME = new AtomicReference<>();

  private ChaosAgentBootstrap() {}

  public static void premain(final String agentArgs, final Instrumentation instrumentation) {
    initialize(agentArgs, instrumentation, System.getenv(), true);
  }

  public static void agentmain(final String agentArgs, final Instrumentation instrumentation) {
    initialize(agentArgs, instrumentation, System.getenv(), true);
  }

  public static ChaosControlPlane installForLocalTests() {
    final ChaosRuntime existing = RUNTIME.get();
    if (existing != null) {
      return existing;
    }
    try {
      final Instrumentation instrumentation = ByteBuddyAgent.install();
      return initialize("", instrumentation, Map.of(), false);
    } catch (RuntimeException runtimeException) {
      throw runtimeException;
    } catch (Exception exception) {
      throw new IllegalStateException(
          "failed to self-attach JVM agent; run tests with -javaagent if dynamic attach is unavailable",
          exception);
    }
  }

  public static ChaosControlPlane current() {
    final ChaosRuntime runtime = RUNTIME.get();
    if (runtime == null) {
      throw new IllegalStateException("chaos agent is not installed");
    }
    return runtime;
  }

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
          runtime.activate(loaded.plan());
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
      final ObjectName objectName = new ObjectName("io.macstab.chaos:type=ChaosDiagnostics");
      if (!ManagementFactory.getPlatformMBeanServer().isRegistered(objectName)) {
        ManagementFactory.getPlatformMBeanServer()
            .registerMBean(new ChaosDiagnosticsMBean(runtime.diagnostics()), objectName);
      }
    } catch (Exception exception) {
      System.err.println("[chaos-agent] MBean registration skipped: " + exception.getMessage());
    }
  }
}

package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Opens the JDK internal packages the agent needs, without {@code --add-opens} flags. */
final class ModuleOpener {
  private static final Logger LOGGER =
      Logger.getLogger(JdkInstrumentationInstaller.class.getName());

  private ModuleOpener() {}

  /**
   * Self-grants reflective and instrumentation access to the JDK internal packages this agent needs
   * to function, without requiring the user to set {@code --add-opens} JVM flags.
   *
   * <p>The agent JAR's {@code MANIFEST.MF} carries a matching {@code Add-Opens} attribute (see
   * {@code chaos-agent-bootstrap/build.gradle.kts}) that the JVM honours on the {@code -javaagent:}
   * (premain) attach path before {@code premain} runs. Manifest opens are <b>not</b> honoured for
   * {@code agentmain} or runtime self-attach via {@code ByteBuddyAgent.install()}, so for those
   * paths this method calls {@link Instrumentation#redefineModule} programmatically. Same pattern
   * OpenTelemetry, Datadog, and New Relic agents use.
   *
   * <p>The opens go to every module agent code runs in: the unnamed module of the system class
   * loader (the agent jar on the class path), the agent's own module (the agent classes in a class
   * loader of their own, as in a fat jar) and the unnamed module of the bootstrap class loader (the
   * bridge, which must be injected before this call).
   *
   * <p>Each entry below is paired with the agent feature that depends on it. If a future feature
   * needs a new internal package, add it both to the manifest <i>and</i> to this list.
   *
   * <p>Failures are tolerated and logged at FINE level: the JVM may legitimately deny {@code
   * redefineModule} on stripped or future runtimes. Downstream code that depends on the open
   * already handles {@code java.lang.reflect.InaccessibleObjectException} via try/catch (see {@code
   * HttpUrlExtractor}, {@code JdbcTargetExtractor}, {@code DirectBufferPressureStressor}).
   *
   * @param instrumentation the JVM-supplied {@link Instrumentation} handle, used for the {@code
   *     redefineModule} call
   */
  static void grantInternalModuleOpens(final Instrumentation instrumentation) {
    final Set<Module> openTo = new HashSet<>();
    openTo.add(ClassLoader.getSystemClassLoader().getUnnamedModule());
    openTo.add(ModuleOpener.class.getModule());
    try {
      openTo.add(Class.forName(BootstrapDispatcher.class.getName(), false, null).getModule());
    } catch (final ClassNotFoundException notInjected) {
      LOGGER.log(Level.FINE, "bootstrap bridge not injected; its module is not opened to");
    }

    // moduleClass → packages to open. Loaded reflectively so this code stays compilable on
    // toolchains that expose only some of these modules. Each lookup tolerates an absent module.
    final List<String[]> opens =
        List.of(
            new String[] {"java.net.http", "jdk.internal.net.http"},
            // Named reflectively by the shaded ByteBuddy (ClassInjector.UsingUnsafe).
            new String[] {"java.base", "jdk.internal.misc"},
            new String[] {"java.base", "sun.nio.ch"},
            new String[] {"java.base", "jdk.internal.ref"},
            // DelayScheduler$ScheduledForkJoinTask.runnable, read by ExecutorDispatch (JDK 25).
            new String[] {"java.base", "java.util.concurrent"});

    for (final String[] entry : opens) {
      final String moduleName = entry[0];
      final String packageName = entry[1];
      final Module module = ModuleLayer.boot().findModule(moduleName).orElse(null);
      if (module == null) {
        LOGGER.log(
            Level.FINE,
            "module {0} not present; skipping open of {1}",
            new Object[] {moduleName, packageName});
        continue;
      }
      if (!module.getPackages().contains(packageName)) {
        LOGGER.log(
            Level.FINE,
            "module {0} does not contain package {1}; skipping",
            new Object[] {moduleName, packageName});
        continue;
      }
      try {
        final Map<String, Set<Module>> extraOpens = new LinkedHashMap<>();
        extraOpens.put(packageName, openTo);
        instrumentation.redefineModule(
            module,
            Collections.emptySet(), // extraReads
            Collections.emptyMap(), // extraExports
            extraOpens, // extraOpens
            Collections.emptySet(), // extraUses
            Collections.emptyMap()); // extraProvides
      } catch (final RuntimeException failure) {
        LOGGER.log(
            Level.FINE,
            failure,
            () ->
                "failed to redefineModule open for "
                    + moduleName
                    + "/"
                    + packageName
                    + " — chaos features depending on this open may degrade gracefully");
      }
    }
  }
}

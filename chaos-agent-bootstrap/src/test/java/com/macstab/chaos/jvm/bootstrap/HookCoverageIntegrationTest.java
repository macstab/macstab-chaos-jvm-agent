package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every hook the agent weaves must match a method of the running JDK (and of the client libraries
 * it supports): hooks name JDK methods by name and signature, and a JDK release that changes one
 * turns the hook silently inert. A child JVM weaves every hook group, loads every class a hook
 * targets and reports the hooks that matched.
 */
@DisplayName("Hook coverage on the running JDK")
class HookCoverageIntegrationTest {

  /** The classes holding the agent's advice, each a set of nested advice classes. */
  private static final List<String> ADVICE_HOLDERS =
      List.of(
          "ClassLoaderAdvice",
          "ClientCallAdvice",
          "ClockAdvice",
          "CompletableFutureAdvice",
          "DatagramAdvice",
          "DnsAdvice",
          "ExecutorAdvice",
          "FileIoAdvice",
          "ForkJoinAdvice",
          "HttpClientAdvice",
          "JdbcAdvice",
          "JvmRuntimeAdvice",
          "NioAdvice",
          "ProcessAdvice",
          "QueueAdvice",
          "ScheduledExecutorAdvice",
          "ShutdownAdvice",
          "SocketAdvice",
          "ThreadAdvice");

  /**
   * Advice whose only target type exists on some JDKs only, with that type: on a JDK without it the
   * advice is absent, not unmatched.
   */
  private static final java.util.Map<String, String> VERSION_SPECIFIC =
      java.util.Map.of(
          "ScheduledExecutorAdvice.JdkTimerDelayAdvice",
          "java.util.concurrent.CompletableFuture$Delayer");

  @TempDir Path tempDir;

  @Test
  @DisplayName("every advice matches a method, and no woven hook matched nothing")
  void everyHookMatches() throws Exception {
    final String classpath =
        String.join(
            File.pathSeparator,
            System.getProperty("chaos.test.jdbcClasspath"),
            // Before the HTTP clients: Reactor Netty brings an older Netty than the client probes.
            System.getProperty("chaos.test.clientClasspath"),
            System.getProperty("chaos.test.httpClasspath"),
            System.getProperty("chaos.test.coverageClasspath"));
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlanOnClasspath(
                DefectProbeMain.class,
                "hook-coverage",
                classpath,
                "-Dmacstab.chaos.instrumentation=eager");

    final String runtime = result.value("runtime");
    assertThat(result.values().keySet())
        .as("hook targets missing from the coverage classpath: their hooks are not verified")
        // A JDK class a hook targets may exist on one JDK version only (CompletableFuture$Delayer
        // on JDK 21); the libraries must all be on the probe classpath.
        .filteredOn(key -> key.startsWith("absent.") && !isJdkClass(key.substring(7)))
        // Tomcat's pool has no jar on the probe classpaths (none is a test dependency).
        .containsOnly("absent.org.apache.tomcat.util.threads.ThreadPoolExecutor");
    assertThat(result.value("nettyVersions"))
        .as("the Netty the probe loaded, one version for every Netty artifact")
        .isNotEmpty()
        .satisfies(
            versions ->
                assertThat(versions.split(","))
                    .allSatisfy(version -> assertThat(version).contains(":4.")));

    assertThat(result.value("unmatchedHooks"))
        .as(
            "hooks woven into a type that matched no method on JDK %s: a signature changed",
            runtime)
        .isEmpty();

    // Netty probes its optional logging back ends: a logger adapter whose library (Log4j 2) is not
    // on the class path fails to resolve its superclass while the hook matchers inspect it, and
    // cannot be loaded either. It is no hook target, so no hook is lost.
    assertThat(
            Arrays.stream(result.value("hookFailures").split("; "))
                .filter(failure -> !failure.isEmpty())
                .filter(
                    failure ->
                        !(failure.startsWith("io.netty.util.internal.logging.")
                            && failure.contains("NoSuchTypeException")))
                .toList())
        .as("types whose weaving failed on JDK %s: none of their hooks is applied", runtime)
        .isEmpty();

    final Set<String> matched =
        Arrays.stream(result.value("matchedHooks").split("\\|"))
            .map(hook -> hook.substring(0, hook.indexOf(" on ")))
            .collect(Collectors.toCollection(TreeSet::new));
    final Set<String> missing = new TreeSet<>(adviceClasses());
    missing.removeAll(matched);
    missing.removeIf(advice -> absentOnThisJdk(VERSION_SPECIFIC.get(advice)));
    assertThat(missing).as("advice that matched no method on JDK %s", runtime).isEmpty();
  }

  /** Whether {@code type} (a version-specific target, or {@code null}) is absent on this JDK. */
  private static boolean absentOnThisJdk(final String type) {
    if (type == null) {
      return false;
    }
    try {
      Class.forName(type, false, null);
      return false;
    } catch (final ClassNotFoundException absent) {
      return true;
    }
  }

  private static boolean isJdkClass(final String className) {
    return className.startsWith("java.")
        || className.startsWith("javax.")
        || className.startsWith("jdk.")
        || className.startsWith("sun.");
  }

  /** {@code Holder.Advice} of every nested class with an enter or exit advice method. */
  private static Set<String> adviceClasses() throws ClassNotFoundException {
    final Set<String> names = new TreeSet<>();
    for (final String holder : ADVICE_HOLDERS) {
      for (final Class<?> nested :
          Class.forName("com.macstab.chaos.jvm.instrumentation." + holder).getDeclaredClasses()) {
        for (final Method method : nested.getDeclaredMethods()) {
          if (method.isAnnotationPresent(net.bytebuddy.asm.Advice.OnMethodEnter.class)
              || method.isAnnotationPresent(net.bytebuddy.asm.Advice.OnMethodExit.class)) {
            names.add(holder + "." + nested.getSimpleName());
            break;
          }
        }
      }
    }
    return names;
  }
}

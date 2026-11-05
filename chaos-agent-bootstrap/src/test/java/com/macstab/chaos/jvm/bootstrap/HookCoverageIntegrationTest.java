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

  @TempDir Path tempDir;

  @Test
  @DisplayName("every advice matches a method, and no woven hook matched nothing")
  void everyHookMatches() throws Exception {
    final String classpath =
        String.join(
            File.pathSeparator,
            System.getProperty("chaos.test.jdbcClasspath"),
            System.getProperty("chaos.test.httpClasspath"),
            System.getProperty("chaos.test.clientClasspath"),
            System.getProperty("chaos.test.coverageClasspath"));
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir)
            .runWithoutPlanOnClasspath(
                DefectProbeMain.class,
                "hook-coverage",
                classpath,
                "-Dmacstab.chaos.instrumentation=eager");

    final String runtime = result.value("runtime");
    assertThat(result.value("unmatchedHooks"))
        .as(
            "hooks woven into a type that matched no method on JDK %s: a signature changed",
            runtime)
        .isEmpty();

    assertThat(result.value("hookFailures"))
        .as("types whose weaving failed on JDK %s: none of their hooks is applied", runtime)
        .isEmpty();

    final Set<String> matched =
        Arrays.stream(result.value("matchedHooks").split("\\|"))
            .map(hook -> hook.substring(0, hook.indexOf(" on ")))
            .collect(Collectors.toCollection(TreeSet::new));
    final Set<String> missing = new TreeSet<>(adviceClasses());
    missing.removeAll(matched);
    assertThat(missing).as("advice that matched no method on JDK %s", runtime).isEmpty();
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
